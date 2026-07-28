package com.mezon.mobile.home.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

private const val TAG = "PeerConnectionWrapper"

class PeerConnectionWrapper(
    private val context: Context,
    private val listener: Listener,
    private val infra: WebRtcInfra,
) {

    interface Listener {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onIceConnected()
        fun onIceDisconnected()
        fun onIceFailed()
        fun onRemoteVideoTrack(track: VideoTrack)
        fun onRemoteAudioTrack(track: AudioTrack)
        fun onInboundSignalingSetupFailed(error: String?) {}
    }

    private val factory: PeerConnectionFactory = infra.factory
    private val eglContext = infra.eglContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private val pendingLocalIceBeforeAccept = mutableListOf<IceCandidate>()
    private var holdLocalCandidates = false
    private var remoteDescriptionSet = false
    private val answerLock = Any()
    private var pendingAnswerCallback: ((SessionDescription) -> Unit)? = null
    private var iceReconnectRunnable: Runnable? = null
    private var disposed = false
    private val attachedLocalSinks = mutableSetOf<SurfaceViewRenderer>()
    private val attachedRemoteSinks = mutableSetOf<SurfaceViewRenderer>()
    private var captureStarted = false
    @Volatile private var localCameraFrontFacing = true

    private fun VideoTrack.addRemoteVideoSinks() {
        attachedRemoteSinks.forEach { addSink(it) }
    }

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            mainHandler.post {
                if (disposed) return@post
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        cancelIceReconnectTimer()
                        listener.onIceConnected()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        startIceReconnectTimer()
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        cancelIceReconnectTimer()
                        listener.onIceFailed()
                    }
                    else -> {}
                }
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            mainHandler.post {
                if (disposed) return@post
                if (holdLocalCandidates) {
                    synchronized(pendingLocalIceBeforeAccept) {
                        pendingLocalIceBeforeAccept.add(candidate)
                    }
                } else {
                    listener.onLocalIceCandidate(candidate)
                }
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

        override fun onAddStream(stream: MediaStream?) {
            stream?.let { ms ->
                mainHandler.post {
                    if (disposed) return@post
                    ms.videoTracks?.firstOrNull()?.let { track ->
                        track.addRemoteVideoSinks()
                        listener.onRemoteVideoTrack(track)
                    }
                    ms.audioTracks?.firstOrNull()?.let { listener.onRemoteAudioTrack(it) }
                }
            }
        }

        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(dc: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track() ?: return
            mainHandler.post {
                if (disposed) return@post
                when (track) {
                    is VideoTrack -> {
                        track.addRemoteVideoSinks()
                        listener.onRemoteVideoTrack(track)
                    }
                    is AudioTrack -> listener.onRemoteAudioTrack(track)
                }
            }
        }
    }

    fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder(com.mezon.mobile.BuildConfig.MEZON_WEBRTC_ICESERVERS_URL)
                .setUsername(com.mezon.mobile.BuildConfig.MEZON_WEBRTC_ICESERVERS_USERNAME)
                .setPassword(com.mezon.mobile.BuildConfig.MEZON_WEBRTC_ICESERVERS_CREDENTIAL)
                .createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, peerConnectionObserver)
    }

    fun createOffer(isVideo: Boolean, callback: (SessionDescription) -> Unit) {
        createPeerConnection()
        holdLocalCandidates = true 
        addLocalMedia(isVideo)

        val constraints = sessionOfferConstraints(offerToReceiveVideo = isVideo)

        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let { offer ->
                    val preferredSdp = preferVp8Codec(offer)
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), preferredSdp)
                    mainHandler.post { callback(preferredSdp) }
                }
            }
        }, constraints)
    }

    fun handleRemoteOffer(sdp: SessionDescription, callback: (SessionDescription) -> Unit) {
        createPeerConnection()
        if (peerConnection == null) {
            android.util.Log.e(TAG, "handleRemoteOffer: createPeerConnection returned null!")
            return
        }
        android.util.Log.d(TAG, "handleRemoteOffer: add local audio then remote SDP, sdp length=${sdp.description.length}")
        addLocalAudioTrackIfPermitted()

        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                android.util.Log.d(TAG, "handleRemoteOffer: setRemoteDescription SUCCESS")
                remoteDescriptionSet = true
                flushPendingIce()

                peerConnection?.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        android.util.Log.d(TAG, "handleRemoteOffer: createAnswer SUCCESS, answer=${answer != null}")
                        answer?.let {
                            val preferredAnswer = preferVp8Codec(it)
                            peerConnection?.setLocalDescription(SimpleSdpObserver(), preferredAnswer)
                            mainHandler.post { callback(preferredAnswer) }
                        }
                    }
                }, MediaConstraints())
            }

            override fun onSetFailure(error: String?) {
                android.util.Log.e(TAG, "handleRemoteOffer: setRemoteDescription FAILED: $error")
                mainHandler.post {
                    listener.onInboundSignalingSetupFailed(error ?: "setRemoteDescription failed")
                }
            }
        }, sdp)
    }

    fun handleRemoteOfferEager(sdp: SessionDescription) {
        if (peerConnection != null) {
            android.util.Log.d(TAG, "handleRemoteOfferEager: peerConnection already exists, skipping")
            return
        }
        createPeerConnection()
        if (peerConnection == null) {
            android.util.Log.e(TAG, "handleRemoteOfferEager: createPeerConnection returned null")
            mainHandler.post {
                listener.onInboundSignalingSetupFailed("createPeerConnection returned null")
            }
            return
        }
        holdLocalCandidates = true
        android.util.Log.d(TAG, "handleRemoteOfferEager: add local audio then remote SDP, sdp length=${sdp.description.length}")
        addLocalAudioTrackIfPermitted()

        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                android.util.Log.d(TAG, "handleRemoteOfferEager: setRemoteDescription SUCCESS")
                remoteDescriptionSet = true
                flushPendingIce()
                drainPendingAnswerAfterRemoteSet()
            }

            override fun onSetFailure(error: String?) {
                android.util.Log.e(TAG, "handleRemoteOfferEager: setRemoteDescription FAILED: $error")
                synchronized(answerLock) {
                    pendingAnswerCallback = null
                }
                mainHandler.post {
                    listener.onInboundSignalingSetupFailed(error ?: "eager setRemoteDescription failed")
                }
            }
        }, sdp)
    }

    fun requestAnswerWhenRemoteReady(callback: (SessionDescription) -> Unit) {
        if (disposed) return
        val runNow = synchronized(answerLock) {
            if (remoteDescriptionSet) {
                true
            } else {
                pendingAnswerCallback = callback
                false
            }
        }
        if (runNow) {
            if (localAudioTrack == null) {
                addLocalAudioTrackIfPermitted()
            }
            createAnswerAndFlush(callback)
        }
    }

    private fun drainPendingAnswerAfterRemoteSet() {
        val cb = synchronized(answerLock) {
            val p = pendingAnswerCallback
            pendingAnswerCallback = null
            p
        } ?: return
        if (disposed) return
        if (localAudioTrack == null) {
            addLocalAudioTrackIfPermitted()
        }
        createAnswerAndFlush(cb)
    }

    fun createAnswerAndFlush(callback: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: run {
            android.util.Log.e(TAG, "createAnswerAndFlush: no peerConnection")
            return
        }
        if (localAudioTrack == null) {
            addLocalAudioTrackIfPermitted()
        }
        pc.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(answer: SessionDescription?) {
                android.util.Log.d(TAG, "createAnswerAndFlush: createAnswer SUCCESS, answer=${answer != null}")
                if (answer == null) {
                    mainHandler.post {
                        listener.onInboundSignalingSetupFailed("createAnswer returned null sdp")
                    }
                    return
                }
                val preferredAnswer = preferVp8Codec(answer)
                pc.setLocalDescription(SimpleSdpObserver(), preferredAnswer)
                mainHandler.post {
                    callback(preferredAnswer)
                    flushPendingLocalIce()
                }
            }

            override fun onCreateFailure(error: String?) {
                android.util.Log.e(TAG, "createAnswerAndFlush: onCreateFailure: $error")
                mainHandler.post {
                    listener.onInboundSignalingSetupFailed(error ?: "createAnswer failed")
                }
            }
        }, MediaConstraints())
    }

    private fun flushPendingLocalIce() {
        holdLocalCandidates = false
        val toFlush: List<IceCandidate> = synchronized(pendingLocalIceBeforeAccept) {
            val copy = pendingLocalIceBeforeAccept.toList()
            pendingLocalIceBeforeAccept.clear()
            copy
        }
        if (toFlush.isEmpty()) return
        android.util.Log.d(TAG, "flushPendingLocalIce: flushing ${toFlush.size} buffered local candidates")
        for (candidate in toFlush) {
            if (disposed) return
            listener.onLocalIceCandidate(candidate)
        }
    }

    fun handleRenegotiationRemoteOffer(sdp: SessionDescription, callback: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: return
        android.util.Log.d(TAG, "handleRenegotiationRemoteOffer: sdp length=${sdp.description.length}")
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                flushPendingIce()
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        answer?.let {
                            val preferredAnswer = preferVp8Codec(it)
                            pc.setLocalDescription(SimpleSdpObserver(), preferredAnswer)
                            mainHandler.post { callback(preferredAnswer) }
                        }
                    }
                }, MediaConstraints())
            }

            override fun onSetFailure(error: String?) {
                android.util.Log.e(TAG, "handleRenegotiationRemoteOffer: setRemoteDescription FAILED: $error")
            }
        }, sdp)
    }

    fun createRenegotiationOffer(callback: (SessionDescription) -> Unit) {
        val constraints = sessionOfferConstraints(offerToReceiveVideo = true)
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let { offer ->
                    val preferredSdp = preferVp8Codec(offer)
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), preferredSdp)
                    mainHandler.post { callback(preferredSdp) }
                }
            }
        }, constraints)
    }

    fun addLocalVideoTrackIfAbsent(): Boolean {
        if (localVideoTrack != null) return false
        addLocalVideoTrackIfPermitted()
        return localVideoTrack != null
    }

    fun hasLocalVideoTrack(): Boolean = localVideoTrack != null

    fun handleRemoteAnswer(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                flushPendingIce()
                mainHandler.post { flushPendingLocalIce() }
            }
        }, sdp)
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        if (remoteDescriptionSet) {
            peerConnection?.addIceCandidate(candidate)
        } else {
            synchronized(pendingRemoteIce) {
                pendingRemoteIce.add(candidate)
            }
        }
    }

    private fun flushPendingIce() {
        synchronized(pendingRemoteIce) {
            for (candidate in pendingRemoteIce) {
                peerConnection?.addIceCandidate(candidate)
            }
            pendingRemoteIce.clear()
        }
    }

    fun setLocalAudioEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun setLocalVideoEnabled(enabled: Boolean) {
        val capturer = videoCapturer
        if (enabled) {
            if (capturer != null && !captureStarted) {
                try {
                    capturer.startCapture(1280, 720, 30)
                    captureStarted = true
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "startCapture failed", e)
                }
            }
        } else {
            if (capturer != null && captureStarted) {
                try {
                    capturer.stopCapture()
                    captureStarted = false
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "stopCapture failed", e)
                }
            }
        }
        localVideoTrack?.setEnabled(enabled)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                mainHandler.post {
                    if (disposed) return@post
                    localCameraFrontFacing = isFrontCamera
                    applyLocalRendererMirror()
                }
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                android.util.Log.w(TAG, "switchCamera failed: $errorDescription")
            }
        })
    }

    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        renderer.setMirror(localCameraFrontFacing)
        if (!attachedLocalSinks.add(renderer)) return
        localVideoTrack?.addSink(renderer)
    }

    fun detachLocalRenderer(renderer: SurfaceViewRenderer) {
        if (!attachedLocalSinks.remove(renderer)) return
        localVideoTrack?.removeSink(renderer)
    }

    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        if (!attachedRemoteSinks.add(renderer)) return
        peerConnection?.receivers?.forEach { receiver ->
            val track = receiver.track()
            if (track is VideoTrack) {
                track.addSink(renderer)
            }
        }
    }

    fun detachRemoteRenderer(renderer: SurfaceViewRenderer) {
        if (!attachedRemoteSinks.remove(renderer)) return
        peerConnection?.receivers?.forEach { receiver ->
            val track = receiver.track()
            if (track is VideoTrack) {
                track.removeSink(renderer)
            }
        }
    }

    fun dispose() {
        disposed = true
        cancelIceReconnectTimer()

        attachedLocalSinks.toList().forEach { sink ->
            try { localVideoTrack?.removeSink(sink) } catch (_: Exception) {}
        }
        attachedLocalSinks.clear()
        attachedRemoteSinks.toList().forEach { sink ->
            peerConnection?.receivers?.forEach { receiver ->
                val track = receiver.track()
                if (track is VideoTrack) {
                    try { track.removeSink(sink) } catch (_: Exception) {}
                }
            }
        }
        attachedRemoteSinks.clear()

        try {
            if (captureStarted) videoCapturer?.stopCapture()
        } catch (_: InterruptedException) {}
        captureStarted = false
        videoCapturer?.dispose()
        videoCapturer = null

        surfaceHelper?.dispose()
        surfaceHelper = null

        localVideoTrack?.dispose()
        localVideoTrack = null

        localAudioTrack?.dispose()
        localAudioTrack = null

        videoSource?.dispose()
        videoSource = null

        audioSource?.dispose()
        audioSource = null

        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null

        synchronized(pendingLocalIceBeforeAccept) { pendingLocalIceBeforeAccept.clear() }
        synchronized(answerLock) { pendingAnswerCallback = null }
    }

    private fun addLocalAudioTrackIfPermitted() {
        if (localAudioTrack != null) return
        val hasAudioPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (hasAudioPermission) {
            audioSource = factory.createAudioSource(MediaConstraints())
            localAudioTrack = factory.createAudioTrack("audio0", audioSource)
            peerConnection?.addTrack(localAudioTrack, listOf("stream0"))
        } else {
            android.util.Log.w(TAG, "addLocalAudioTrackIfPermitted: RECORD_AUDIO not granted, skipping audio track")
        }
    }

    private fun addLocalVideoTrackIfPermitted() {
        val hasCameraPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (hasCameraPermission) {
            val enumerator = Camera2Enumerator(context)
            val frontCamera = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            frontCamera?.let { cameraName ->
                localCameraFrontFacing = enumerator.isFrontFacing(cameraName)
                videoCapturer = enumerator.createCapturer(cameraName, null)
                videoCapturer?.let { addLocalVideoTrackWithoutStartingCapture(it) }
            }
        } else {
            android.util.Log.w(TAG, "addLocalVideoTrackIfPermitted: CAMERA not granted, skipping video track")
        }
    }

    private fun addLocalMedia(isVideo: Boolean) {
        addLocalAudioTrackIfPermitted()
        if (isVideo) {
            addLocalVideoTrackIfPermitted()
        }
    }

    private fun sessionOfferConstraints(offerToReceiveVideo: Boolean): MediaConstraints {
        return MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (offerToReceiveVideo) "true" else "false"))
            mandatory.add(MediaConstraints.KeyValuePair("VoiceActivityDetection", "true"))
        }
    }

    private fun surfaceTextureHelperForCapture(): SurfaceTextureHelper? {
        repeat(4) { attempt ->
            SurfaceTextureHelper.create("CaptureThread", eglContext)?.let { return it }
            if (attempt < 3) {
                try {
                    Thread.sleep((24 * (attempt + 1)).toLong())
                } catch (_: InterruptedException) {
                    return null
                }
            }
        }
        android.util.Log.w(TAG, "surfaceTextureHelperForCapture: shared EGL failed after retries, using isolated context")
        return SurfaceTextureHelper.create("CaptureThread", null)
    }

    private fun addLocalVideoTrackWithoutStartingCapture(capturer: CameraVideoCapturer) {
        val vs = factory.createVideoSource(capturer.isScreencast)
        videoSource = vs
        val helper = surfaceTextureHelperForCapture()
        if (helper == null) {
            android.util.Log.e(TAG, "SurfaceTextureHelper.create returned null")
            runCatching { vs.dispose() }
            videoSource = null
            runCatching { capturer.dispose() }
            videoCapturer = null
            return
        }
        surfaceHelper = helper
        try {
            capturer.initialize(helper, context, vs.capturerObserver)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Camera capturer initialize failed", e)
            runCatching { helper.dispose() }
            surfaceHelper = null
            runCatching { vs.dispose() }
            videoSource = null
            runCatching { capturer.dispose() }
            videoCapturer = null
            return
        }
        localVideoTrack = factory.createVideoTrack("video0", vs)
        localVideoTrack?.setEnabled(false)
        peerConnection?.addTrack(localVideoTrack, listOf("stream0"))
    }

    private fun applyLocalRendererMirror() {
        attachedLocalSinks.forEach { it.setMirror(localCameraFrontFacing) }
    }

    private fun startIceReconnectTimer() {
        cancelIceReconnectTimer()
        iceReconnectRunnable = Runnable {
            if (!disposed) listener.onIceDisconnected()
        }
        mainHandler.postDelayed(iceReconnectRunnable!!, ICE_RECONNECT_TIMEOUT_MS)
    }

    private fun cancelIceReconnectTimer() {
        iceReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        iceReconnectRunnable = null
    }

    private fun preferVp8Codec(sdp: SessionDescription): SessionDescription {
        val lines = sdp.description.split("\r\n").toMutableList()
        val videoMLineIndex = lines.indexOfFirst { it.startsWith("m=video") }
        if (videoMLineIndex < 0) return sdp

        val mLine = lines[videoMLineIndex]
        val parts = mLine.split(" ").toMutableList()
        if (parts.size < 4) return sdp

        var vp8Payload: String? = null
        for (i in videoMLineIndex + 1 until lines.size) {
            val line = lines[i]
            if (line.startsWith("m=")) break
            if (line.contains("VP8/90000", ignoreCase = true)) {
                val match = Regex("a=rtpmap:(\\d+)\\s+VP8/90000").find(line)
                if (match != null) {
                    vp8Payload = match.groupValues[1]
                    break
                }
            }
        }

        if (vp8Payload != null) {
            val payloads = parts.subList(3, parts.size).toMutableList()
            payloads.remove(vp8Payload)
            payloads.add(0, vp8Payload)
            val newMLine = parts.subList(0, 3).joinToString(" ") + " " + payloads.joinToString(" ")
            lines[videoMLineIndex] = newMLine
        }

        return SessionDescription(sdp.type, lines.joinToString("\r\n"))
    }

    companion object {
        private const val ICE_RECONNECT_TIMEOUT_MS = 15_000L
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {
        android.util.Log.e("WebRTC-SDP", "onCreateFailure: $error")
    }
    override fun onSetFailure(error: String?) {
        android.util.Log.e("WebRTC-SDP", "onSetFailure: $error")
    }
}
