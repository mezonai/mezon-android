package com.mezon.mobile.home.voice.sfu

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.MainDispatcher
import com.mezon.mobile.home.call.WebRtcInfra
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

private const val TAG = "MezonSfuSession"
private const val MID_AUDIO = "0"
private const val MID_CAMERA = "1"
private const val MID_SCREEN = "2"
private const val CAPTURE_WIDTH = 640
private const val CAPTURE_HEIGHT = 360
private const val CAPTURE_FPS = 24
private const val SCREEN_WIDTH = 1280
private const val SCREEN_HEIGHT = 720
private const val SCREEN_FPS = 15
private const val SPEAKING_POLL_MS = 300L
private const val RECONNECT_POLL_MS = 3000L
private const val MAX_RECONNECT_ATTEMPTS = 40
private const val SPEAKING_THRESHOLD = 0.02

@Singleton
class MezonSfuSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webRtcInfra: WebRtcInfra,
    private val okHttpClient: OkHttpClient,
    @ApplicationScope private val appScope: CoroutineScope,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) {
    var onConnectionState: ((SfuConnectionState) -> Unit)? = null
    var onParticipants: ((List<SfuParticipant>) -> Unit)? = null
    var onRoleChanged: ((SfuRole) -> Unit)? = null
    var onError: ((String, String?) -> Unit)? = null
    var onLocalVideoTrack: ((VideoTrack?) -> Unit)? = null
    var onLocalScreenTrack: ((VideoTrack?) -> Unit)? = null
    var onPushToTalkActive: ((Boolean) -> Unit)? = null
    var onSpeaking: ((Set<String>) -> Unit)? = null
    var tokenProvider: (suspend () -> String?)? = null

    @Volatile var role: SfuRole = SfuRole.SPEAKER
        private set

    private var scope: CoroutineScope? = null
    private var webSocket: WebSocket? = null
    private var peerConnection: PeerConnection? = null

    private var channelId: Long = 0
    private var userId: String = ""
    private var token: String = ""

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var cameraSource: VideoSource? = null
    private var cameraTrack: VideoTrack? = null
    private var cameraHelper: SurfaceTextureHelper? = null
    private var cameraCapturing = false
    private var screenCapturer: VideoCapturer? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var screenHelper: SurfaceTextureHelper? = null

    private var joined = false
    private var localTracksAdded = false
    private var micEnabled = false
    private var cameraEnabled = false
    private var screenOn = false
    private var pttActive = false

    private var active = false
    @Volatile private var socketOpen = false
    private var connecting = false
    private var connectionGen = 0
    private var stateRestored = false
    private var reconnectAttempts = 0

    private var negotiating = false
    private var pendingOffer: Pair<Long, String>? = null

    private var transceiverCache: List<RtpTransceiver> = emptyList()

    private val userIdByMid = HashMap<String, String>()
    private val peerIdByMid = HashMap<String, String>()
    private val roleByMid = HashMap<String, SfuRole>()
    private val leftMids = HashSet<String>()
    private val remote = LinkedHashMap<String, RemoteEntry>()

    private class RemoteEntry(val id: String) {
        var userId: String? = null
        var peerId: String? = null
        var role: SfuRole? = null
        var muted: Boolean = false
        var audio: AudioTrack? = null
        var video: VideoTrack? = null
        var screen: VideoTrack? = null
        var screenActive: Boolean = false
        var cameraActive: Boolean = false
    }

    fun join(channelId: Long, clanId: Long, userId: String, token: String, role: SfuRole) {
        leave()
        this.channelId = channelId
        this.userId = userId
        this.token = token
        this.role = role
        this.micEnabled = false
        this.cameraEnabled = false
        this.screenOn = false
        this.pttActive = false
        this.joined = false
        this.localTracksAdded = false
        this.active = true
        this.reconnectAttempts = 0
        val roomScope = CoroutineScope(SupervisorJob() + mainDispatcher)
        scope = roomScope

        webRtcInfra.ensureFactoryReady()
        createLocalAudioTrack()

        if (buildWsUrl(token).isEmpty()) {
            Log.e(TAG, "join failed: empty MEZON_SFU_WS_URL")
            emitState(SfuConnectionState.FAILED)
            return
        }
        Log.d(TAG, "join channelId=$channelId role=${role.wire}")
        openConnection(initial = true)
        roomScope.launch {
            while (isActive) {
                delay(SPEAKING_POLL_MS)
                peerConnection?.let { pollSpeaking(it) }
            }
        }
        roomScope.launch {
            while (isActive) {
                delay(RECONNECT_POLL_MS)
                if (active && joined && !socketOpen && !connecting) {
                    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                        active = false
                        emitState(SfuConnectionState.FAILED)
                        break
                    }
                    reconnectAttempts++
                    val fresh = runCatching { tokenProvider?.invoke() }.getOrNull()
                    if (!fresh.isNullOrEmpty()) this@MezonSfuSession.token = fresh
                    openConnection(initial = false)
                }
            }
        }
    }

    private fun openConnection(initial: Boolean) {
        connecting = true
        connectionGen++
        val gen = connectionGen
        stateRestored = false
        if (!initial) {
            runCatching { webSocket?.close(1000, null) }
            runCatching { peerConnection?.close() }
            negotiating = false
            pendingOffer = null
            localTracksAdded = false
            userIdByMid.clear()
            peerIdByMid.clear()
            roleByMid.clear()
            leftMids.clear()
            remote.clear()
            emitParticipants()
        }
        transceiverCache = emptyList()
        val pc = createPeerConnection(gen)
        if (pc == null) {
            connecting = false
            emitState(SfuConnectionState.FAILED)
            return
        }
        peerConnection = pc
        emitState(if (initial) SfuConnectionState.CONNECTING else SfuConnectionState.DISCONNECTED)
        val request = Request.Builder().url(buildWsUrl(token)).build()
        webSocket = okHttpClient.newWebSocket(request, SfuSocketListener(gen))
        Log.d(TAG, "openConnection initial=$initial gen=$gen")
    }

    private fun pollSpeaking(pc: PeerConnection) {
        pc.getStats { report ->
            appScope.launch(mainDispatcher) {
                val speaking = HashSet<String>()
                for (stats in report.statsMap.values) {
                    if (stats.members["kind"] != "audio") continue
                    val level = (stats.members["audioLevel"] as? Number)?.toDouble() ?: continue
                    if (level <= SPEAKING_THRESHOLD) continue
                    when (stats.type) {
                        "media-source" -> if (micEnabled || pttActive) speaking.add(userId)
                        "inbound-rtp" -> {
                            val mid = stats.members["mid"] as? String
                            val uid = mid?.let { userIdByMid[it] }
                            if (uid != null) speaking.add(uid)
                        }
                    }
                }
                onSpeaking?.invoke(speaking)
            }
        }
    }

    fun leave() {
        Log.d(TAG, "leave() joined=$joined")
        active = false
        connectionGen++
        socketOpen = false
        connecting = false
        stateRestored = false
        scope?.cancel()
        scope = null
        webSocket?.close(1000, "leave")
        webSocket = null
        stopCameraCapture()
        stopScreenShare()
        runCatching { cameraTrack?.dispose() }
        runCatching { cameraSource?.dispose() }
        runCatching { cameraHelper?.dispose() }
        cameraTrack = null; cameraSource = null; cameraHelper = null; cameraCapturer = null
        runCatching { localAudioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        localAudioTrack = null; audioSource = null
        transceiverCache = emptyList()
        runCatching { peerConnection?.close() }
        peerConnection = null
        joined = false
        localTracksAdded = false
        negotiating = false
        pendingOffer = null
        userIdByMid.clear(); peerIdByMid.clear(); roleByMid.clear(); leftMids.clear()
        remote.clear()
    }

    fun setMicEnabled(on: Boolean) {
        Log.d(TAG, "setMicEnabled=$on role=${role.wire}")
        micEnabled = on
        localAudioTrack?.setEnabled(on)
        send(JSONObject().put("type", "mute").put("is_mute", !on))
    }

    fun setCameraEnabled(on: Boolean) {
        Log.d(TAG, "setCameraEnabled=$on role=${role.wire}")
        cameraEnabled = on
        scope?.launch {
            if (on) {
                if (peerConnection != null) prepareVideoSender()
                ensureCameraCapturer()
                startCameraCapture()
                cameraTrack?.setEnabled(true)
                cameraTrack?.let { onLocalVideoTrack?.invoke(it) }
            } else {
                stopCameraCapture()
                cameraTrack?.setEnabled(false)
            }
            send(JSONObject().put("type", "camera").put("active", on))
        }
    }

    fun switchCamera() {
        Log.d(TAG, "switchCamera")
        cameraCapturer?.switchCamera(null)
    }

    fun setScreenShare(on: Boolean, permissionData: Intent?) {
        Log.d(TAG, "setScreenShare=$on hasPermission=${permissionData != null}")
        scope?.launch {
            if (on) {
                if (permissionData == null) return@launch
                startScreenCapture(permissionData)
                send(JSONObject().put("type", "share_screen").put("active", true))
                screenOn = true
            } else {
                stopScreenShare()
                send(JSONObject().put("type", "share_screen").put("active", false))
                screenOn = false
            }
        }
    }

    fun pttPress() {
        if (role != SfuRole.AUDIENCE) return
        Log.d(TAG, "pttPress")
        send(JSONObject().put("type", "mute").put("is_mute", false))
        send(JSONObject().put("type", "push_to_talk").put("active", true))
    }

    fun pttRelease() {
        if (role != SfuRole.AUDIENCE) return
        Log.d(TAG, "pttRelease")
        send(JSONObject().put("type", "push_to_talk").put("active", false))
        send(JSONObject().put("type", "mute").put("is_mute", true))
    }

    private fun createPeerConnection(gen: Int): PeerConnection? {
        val iceServers = ArrayList<PeerConnection.IceServer>()
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        if (BuildConfig.MEZON_WEBRTC_ICESERVERS_URL.isNotEmpty()) {
            iceServers.add(
                PeerConnection.IceServer.builder(BuildConfig.MEZON_WEBRTC_ICESERVERS_URL)
                    .setUsername(BuildConfig.MEZON_WEBRTC_ICESERVERS_USERNAME)
                    .setPassword(BuildConfig.MEZON_WEBRTC_ICESERVERS_CREDENTIAL)
                    .createIceServer()
            )
        }
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        return webRtcInfra.factory.createPeerConnection(config, makePeerObserver(gen))
    }

    private fun createLocalAudioTrack() {
        if (localAudioTrack != null) return
        val source = webRtcInfra.factory.createAudioSource(MediaConstraints())
        audioSource = source
        val track = webRtcInfra.factory.createAudioTrack("sfu_audio", source)
        track.setEnabled(false)
        localAudioTrack = track
    }

    private fun buildWsUrl(token: String): String {
        val base = BuildConfig.MEZON_SFU_WS_URL.trim()
        if (base.isEmpty()) return ""
        val encoded = URLEncoder.encode(token, Charsets.UTF_8.name())
        val sep = if (base.contains("?")) "&" else "?"
        return "$base${sep}access_token=$encoded"
    }

    private inner class SfuSocketListener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            appScope.launch(mainDispatcher) {
                if (gen != connectionGen) {
                    runCatching { webSocket.close(1000, null) }
                    return@launch
                }
                socketOpen = true
                connecting = false
                Log.d(TAG, "ws onOpen gen=$gen -> join room=$channelId role=${role.wire}")
                emitState(SfuConnectionState.JOINING)
                send(
                    JSONObject()
                        .put("type", "join")
                        .put("room", channelId.toString())
                        .put("token", token)
                        .put("role", role.wire)
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            appScope.launch(mainDispatcher) {
                if (gen != connectionGen) return@launch
                Log.d(TAG, "recv=${runCatching { JSONObject(text).optString("type") }.getOrDefault("?")}")
                handleMessage(text)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "ws onClosing code=$code reason='$reason'")
            runCatching { webSocket.close(code, null) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            appScope.launch(mainDispatcher) {
                if (gen != connectionGen) return@launch
                socketOpen = false
                connecting = false
                Log.e(TAG, "ws failure ${t.javaClass.simpleName}: ${t.message} respCode=${response?.code}")
                if (active && joined) emitState(SfuConnectionState.DISCONNECTED)
                else if (active) emitState(SfuConnectionState.FAILED)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            appScope.launch(mainDispatcher) {
                if (gen != connectionGen) return@launch
                socketOpen = false
                connecting = false
                Log.d(TAG, "ws onClosed code=$code reason='$reason'")
                if (active && joined) emitState(SfuConnectionState.DISCONNECTED)
                else if (active) emitState(SfuConnectionState.FAILED)
            }
        }
    }

    private fun handleMessage(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "ping" -> send(JSONObject().put("type", "pong"))
            "pong" -> {}
            "joined" -> {
                Log.d(TAG, "joined -> awaiting offer")
                emitState(SfuConnectionState.AWAITING_OFFER)
            }
            "room_snapshot" -> {
                Log.d(TAG, "room_snapshot members=${msg.optJSONArray("members")?.length() ?: 0} stateRestored=$stateRestored role=${role.wire}")
                applyPeers(msg.optJSONArray("members"))
                joined = true
                reconnectAttempts = 0
                if (!stateRestored) {
                    stateRestored = true
                    send(JSONObject().put("type", "mute").put("is_mute", !micEnabled))
                    if (role == SfuRole.SPEAKER) {
                        send(JSONObject().put("type", "camera").put("active", cameraEnabled))
                        if (screenOn) send(JSONObject().put("type", "share_screen").put("active", true))
                    }
                    send(JSONObject().put("type", "visibility").put("visible", true))
                }
                emitParticipants()
            }
            "peer_joined", "peer_updated" -> {
                val peer = msg.optJSONObject("peer")
                Log.d(TAG, "${msg.optString("type")} peer_id=${peer?.opt("peer_id")} role=${peer?.optString("role")} mute=${peer?.opt("is_mute")} cam=${peer?.opt("camera_active")} screen=${peer?.opt("screen_active")}")
                if (peer != null) applyPeers(org.json.JSONArray().put(peer))
                emitParticipants()
            }
            "peer_left" -> {
                handlePeerLeft(msg)
                emitParticipants()
            }
            "push_to_talk_changed" -> {
                val active = msg.optBoolean("active")
                Log.d(TAG, "push_to_talk_changed active=$active peer_id=${msg.opt("peer_id")}")
                pttActive = active
                localAudioTrack?.setEnabled(active)
                onPushToTalkActive?.invoke(active)
            }
            "role_changed" -> {
                val newRole = SfuRole.fromWire(msg.optString("role"))
                Log.d(TAG, "recv role_changed role='${msg.optString("role")}' -> $newRole")
                scope?.launch { handleRoleChanged(newRole) }
            }
            "offer" -> {
                val sdp = msg.optString("sdp")
                Log.d(TAG, "recv offer gen=${msg.optLong("offer_generation")} sdpLen=${sdp.length}")
                if (sdp.isNotEmpty()) onOffer(msg.optLong("offer_generation"), sdp)
            }
            "error" -> {
                val detail = msg.optString("message")
                when {
                    detail == "invalid_push_to_talk" || detail == "push_to_talk_rejected" -> {
                        pttActive = false
                        localAudioTrack?.setEnabled(false)
                        onPushToTalkActive?.invoke(false)
                    }
                    active && joined -> {
                        Log.e(TAG, "sfu error during session: $detail (will retry with fresh token)")
                        runCatching { webSocket?.close(1000, null) }
                    }
                    else -> {
                        Log.e(TAG, "sfu error: $detail")
                        onError?.invoke(detail, msg.optString("message"))
                        emitState(SfuConnectionState.FAILED)
                    }
                }
            }
        }
    }

    private fun onOffer(generation: Long, sdp: String) {
        parseMsids(sdp)
        scope?.launch { negotiate(generation, sdp) }
    }

    private suspend fun negotiate(firstGeneration: Long, firstSdp: String) {
        if (negotiating) {
            pendingOffer = firstGeneration to firstSdp
            return
        }
        negotiating = true
        var offer: Pair<Long, String>? = firstGeneration to firstSdp
        while (offer != null) {
            val (generation, sdp) = offer
            val pc = peerConnection ?: break
            Log.d(TAG, "negotiate gen=$generation")
            try {
                val stableSdp = stabilizeInactiveVideoSections(sdp, pc.remoteDescription?.description)
                awaitSetRemote(pc, SessionDescription(SessionDescription.Type.OFFER, stableSdp))
                transceiverCache = pc.transceivers
                attachLocalTracks(pc)
                val answer = awaitCreateAnswer(pc)
                awaitSetLocal(pc, SessionDescription(SessionDescription.Type.ANSWER, answer.description))
                syncRemoteMedia()
                val local = pc.localDescription
                if (local != null) {
                    Log.d(TAG, "answer gen=$generation transceivers=${transceiverCache.size}")
                    send(
                        JSONObject()
                            .put("type", "answer")
                            .put("offer_generation", generation)
                            .put("sdp", patchAnswerForSfu(local.description))
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "negotiate failed: ${e.message}")
            } finally {
                offer = pendingOffer
                pendingOffer = null
                if (offer != null) delay(50)
            }
        }
        negotiating = false
    }

    private fun attachLocalTracks(pc: PeerConnection) {
        if (localTracksAdded) {
            if (role == SfuRole.SPEAKER && screenOn) reattachScreen()
            return
        }
        Log.d(TAG, "attachLocalTracks role=${role.wire} hasAudio=${localAudioTrack != null} screenOn=$screenOn")
        val audio = localAudioTrack
        if (audio != null) {
            audio.setEnabled(if (role == SfuRole.AUDIENCE) pttActive else micEnabled)
            val tc = findTransceiver(MID_AUDIO, "audio")
            if (tc != null) {
                tc.sender.setTrack(audio, false)
                tc.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            } else {
                pc.addTrack(audio, listOf("sfu"))
            }
        }
        if (role == SfuRole.SPEAKER) {
            prepareVideoSender()
            if (screenOn) reattachScreen()
        }
        localTracksAdded = true
    }

    private suspend fun handleRoleChanged(newRole: SfuRole) {
        Log.d(TAG, "handleRoleChanged ${role.wire} -> ${newRole.wire}")
        role = newRole
        val pc = peerConnection
        val audio = localAudioTrack
        val tc = if (pc != null) findTransceiver(MID_AUDIO, "audio") else null
        if (newRole == SfuRole.SPEAKER) {
            audio?.setEnabled(true)
            if (tc != null && audio != null) {
                tc.sender.setTrack(audio, false)
                tc.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            }
            pttActive = true
            onPushToTalkActive?.invoke(true)
        } else {
            audio?.setEnabled(false)
            tc?.sender?.setTrack(null, false)
            tc?.direction = RtpTransceiver.RtpTransceiverDirection.INACTIVE
            pttActive = false
            onPushToTalkActive?.invoke(false)
        }
        onRoleChanged?.invoke(newRole)
    }

    private fun findTransceiver(mid: String, kind: String): RtpTransceiver? {
        val tcs = transceiverCache
        return tcs.firstOrNull { it.mid == mid }
            ?: tcs.firstOrNull {
                val t = it.receiver?.track()
                t != null && t.kind() == kind && it.mid == null
            }
    }

    private fun prepareVideoSender() {
        if (cameraTrack == null) {
            val source = webRtcInfra.factory.createVideoSource(false)
            val track = webRtcInfra.factory.createVideoTrack("sfu_camera", source)
            track.setEnabled(cameraEnabled)
            cameraSource = source
            cameraTrack = track
        }
        val tc = findTransceiver(MID_CAMERA, "video") ?: return
        if (tc.sender.track() !== cameraTrack) {
            tc.sender.setTrack(cameraTrack, false)
            tc.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
        }
    }

    private fun ensureCameraCapturer() {
        if (cameraCapturer != null) return
        val source = cameraSource ?: return
        val enumerator = Camera2Enumerator(context)
        val front = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: return
        val capturer = enumerator.createCapturer(front, null) ?: return
        val helper = SurfaceTextureHelper.create("SfuCameraThread", webRtcInfra.eglContext) ?: return
        capturer.initialize(helper, context, source.capturerObserver)
        cameraCapturer = capturer
        cameraHelper = helper
    }

    private fun startCameraCapture() {
        if (cameraCapturing) return
        runCatching {
            cameraCapturer?.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
            cameraCapturing = true
        }.onFailure { Log.e(TAG, "startCameraCapture failed", it) }
    }

    private fun stopCameraCapture() {
        if (!cameraCapturing) return
        runCatching { cameraCapturer?.stopCapture() }
        cameraCapturing = false
    }

    private fun startScreenCapture(permissionData: Intent) {
        if (screenTrack != null) return
        val capturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
            override fun onStop() {
                appScope.launch(mainDispatcher) { setScreenShare(false, null) }
            }
        })
        val source = webRtcInfra.factory.createVideoSource(true)
        val helper = SurfaceTextureHelper.create("SfuScreenThread", webRtcInfra.eglContext) ?: return
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_FPS)
        Log.d(TAG, "screen capture started ${SCREEN_WIDTH}x${SCREEN_HEIGHT}@$SCREEN_FPS")
        val track = webRtcInfra.factory.createVideoTrack("sfu_screen", source)
        track.setEnabled(true)
        screenCapturer = capturer
        screenSource = source
        screenHelper = helper
        screenTrack = track
        onLocalScreenTrack?.invoke(track)
        if (peerConnection != null) reattachScreen()
    }

    private fun reattachScreen() {
        val track = screenTrack ?: return
        val tc = findTransceiver(MID_SCREEN, "video") ?: return
        if (tc.sender.track() !== track) {
            Log.d(TAG, "reattachScreen -> mid=$MID_SCREEN")
            tc.sender.setTrack(track, false)
            tc.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
        }
    }

    private fun stopScreenShare() {
        Log.d(TAG, "stopScreenShare")
        runCatching { screenCapturer?.stopCapture() }
        runCatching { screenTrack?.dispose() }
        runCatching { screenSource?.dispose() }
        runCatching { screenHelper?.dispose() }
        if (screenTrack != null) onLocalScreenTrack?.invoke(null)
        screenCapturer = null
        screenSource = null
        screenTrack = null
        screenHelper = null
    }

    private fun applyPeers(members: org.json.JSONArray?) {
        members ?: return
        Log.d(TAG, "applyPeers members=${members.length()} remoteBefore=${remote.size}")
        for (i in 0 until members.length()) {
            val peer = members.optJSONObject(i) ?: continue
            val peerId = peer.opt("peer_id")?.toString() ?: continue
            val userIdValue = peer.optString("user_id").takeIf { it.isNotEmpty() }
            val peerRole = if (peer.has("role")) SfuRole.fromWire(peer.optString("role")) else null
            val mids = listOf(
                peer.opt("mid_audio"), peer.opt("mid_video"), peer.opt("mid_screen")
            ).mapNotNull { it?.toString() }.filter { it.isNotEmpty() && it != "0" }
            for (mid in mids) {
                peerIdByMid[mid] = peerId
                if (userIdValue != null) userIdByMid[mid] = userIdValue
                if (peerRole != null) roleByMid[mid] = peerRole
            }
            val existing = remote.entries.firstOrNull { it.value.peerId == peerId }?.key
            val participantId = existing ?: mids.firstOrNull()?.let { remoteParticipantId(it) } ?: continue
            val entry = remote.getOrPut(participantId) { RemoteEntry(participantId) }
            entry.peerId = peerId
            if (userIdValue != null) entry.userId = userIdValue
            if (peerRole != null) entry.role = peerRole
            if (peer.has("is_mute")) entry.muted = peer.optBoolean("is_mute")
            if (peer.has("camera_active")) entry.cameraActive = peer.optBoolean("camera_active")
            if (peer.has("screen_active")) entry.screenActive = peer.optBoolean("screen_active")
        }
    }

    private fun handlePeerLeft(msg: JSONObject) {
        val mids = listOf(
            msg.opt("mid_audio"), msg.opt("mid_video"), msg.opt("mid_screen")
        ).mapNotNull { it?.toString() }.filter { it.isNotEmpty() && it != "0" }
        Log.d(TAG, "peer_left mids=$mids")
        for (mid in mids) {
            leftMids.add(mid)
            remote.remove(remoteParticipantId(mid))
        }
    }

    private fun syncRemoteMedia() {
        for (tc in transceiverCache) {
            val mid = tc.mid ?: continue
            if (mid == MID_AUDIO || mid == MID_CAMERA || mid == MID_SCREEN) continue
            if (leftMids.contains(mid)) continue
            val direction = tc.currentDirection ?: tc.direction
            val id = remoteParticipantId(mid)
            val kind = remoteKind(mid)
            if (direction == RtpTransceiver.RtpTransceiverDirection.INACTIVE ||
                direction == RtpTransceiver.RtpTransceiverDirection.STOPPED
            ) {
                val entry = remote[id] ?: continue
                Log.d(TAG, "syncRemote mid=$mid kind=$kind INACTIVE -> clear id=$id")
                when (kind) {
                    "audio" -> entry.audio = null
                    "camera" -> entry.video = null
                    "screen" -> entry.screen = null
                }
                if (entry.audio == null && entry.video == null && entry.screen == null) {
                    remote.remove(id)
                }
                continue
            }
            val track = tc.receiver?.track() ?: continue
            val entry = remote.getOrPut(id) { RemoteEntry(id) }
            userIdByMid[mid]?.let { entry.userId = it }
            peerIdByMid[mid]?.let { entry.peerId = it }
            roleByMid[mid]?.let { entry.role = it }
            when {
                track is AudioTrack -> entry.audio = track
                kind == "camera" && track is VideoTrack -> entry.video = track
                kind == "screen" && track is VideoTrack -> {
                    entry.screen = track
                }
            }
            Log.d(TAG, "syncRemote mid=$mid kind=$kind track=${track.javaClass.simpleName} id=$id")
            if (entry.audio == null && entry.video == null && entry.screen == null) {
                remote.remove(id)
            }
        }
        emitParticipants()
    }

    private fun emitParticipants() {
        val list = remote.values.map {
            SfuParticipant(
                id = it.id,
                userId = it.userId,
                role = it.role,
                muted = it.muted,
                audio = it.audio,
                video = it.video,
                screen = it.screen,
                screenActive = it.screenActive,
                cameraActive = it.cameraActive,
            )
        }
        Log.d(TAG, "emitParticipants n=${list.size} " + list.joinToString(" ") { p ->
            "${p.id}/${p.role?.wire}${if (p.audio != null) "A" else ""}${if (p.video != null) "V" else ""}${if (p.screen != null) "S" else ""}${if (p.muted) "m" else ""}"
        })
        onParticipants?.invoke(list)
    }

    private fun remoteParticipantId(mid: String): String {
        val n = mid.toIntOrNull()
        return if (n != null && n >= 3) "peer-${(n - 3) / 3}" else "mid-$mid"
    }

    private fun remoteKind(mid: String): String? {
        val n = mid.toIntOrNull() ?: return null
        if (n < 3) return null
        return when ((n - 3) % 3) {
            0 -> "audio"
            1 -> "camera"
            else -> "screen"
        }
    }

    private fun parseMsids(sdp: String) {
        var currentMid: String? = null
        val userRegex = Regex("(?:^|-)u(\\d+)(?:-|$)")
        for (raw in sdp.split("\r\n", "\n")) {
            val line = raw.trim()
            when {
                line.startsWith("m=") -> currentMid = null
                line.startsWith("a=mid:") -> currentMid = line.removePrefix("a=mid:").trim()
                currentMid != null && line.startsWith("a=msid:") -> {
                    val parts = line.removePrefix("a=msid:").trim().split(Regex("\\s+"))
                    val uid = parts.firstNotNullOfOrNull { userRegex.find(it)?.groupValues?.get(1) }
                    if (uid != null) userIdByMid[currentMid!!] = uid
                }
            }
        }
    }

    private fun patchAnswerForSfu(sdp: String): String {
        if (role != SfuRole.AUDIENCE) return sdp
        val lines = sdp.split("\r\n", "\n").filter { it.isNotEmpty() }.toMutableList()
        var currentIsVideo = false
        var sectionHasMid1 = false
        var inactiveIdx = -1
        var changed = false
        fun applySection() {
            if (sectionHasMid1 && inactiveIdx >= 0) {
                lines[inactiveIdx] = "a=sendonly"
                changed = true
            }
            sectionHasMid1 = false
            inactiveIdx = -1
        }
        for (i in lines.indices) {
            val line = lines[i]
            when {
                line.startsWith("m=") -> {
                    applySection()
                    currentIsVideo = line.startsWith("m=video")
                }
                !currentIsVideo -> {}
                line == "a=mid:1" -> sectionHasMid1 = true
                line == "a=inactive" -> inactiveIdx = i
            }
        }
        applySection()
        if (!changed) return sdp
        val sb = StringBuilder()
        for (line in lines) sb.append(line).append("\r\n")
        return sb.toString()
    }

    private fun stabilizeInactiveVideoSections(offerSdp: String, currentRemoteSdp: String?): String {
        if (currentRemoteSdp.isNullOrEmpty()) return offerSdp

        fun splitSections(sdp: String): Pair<MutableList<String>, MutableList<MutableList<String>>> {
            val sessionLines = ArrayList<String>()
            val mediaSections = ArrayList<MutableList<String>>()
            for (line in sdp.split("\r\n", "\n")) {
                if (line.isEmpty()) continue
                when {
                    line.startsWith("m=") -> mediaSections.add(mutableListOf(line))
                    mediaSections.isNotEmpty() -> mediaSections.last().add(line)
                    else -> sessionLines.add(line)
                }
            }
            return sessionLines to mediaSections
        }
        fun midOf(section: List<String>): String? =
            section.firstOrNull { it.startsWith("a=mid:") }?.removePrefix("a=mid:")
        fun isCodecLine(line: String): Boolean =
            line.startsWith("a=rtpmap:") || line.startsWith("a=fmtp:") || line.startsWith("a=rtcp-fb:")

        val previousByMid = HashMap<String, List<String>>()
        for (s in splitSections(currentRemoteSdp).second) midOf(s)?.let { previousByMid[it] = s }

        val (nextSession, nextSections) = splitSections(offerSdp)
        var changed = false
        val stabilized = nextSections.map { section ->
            if (!section[0].startsWith("m=video ") || !section.contains("a=inactive")) return@map section
            val mid = midOf(section) ?: return@map section
            if ((mid.toIntOrNull() ?: 0) < 3) return@map section
            val prev = previousByMid[mid] ?: return@map section
            if (prev.isEmpty() || !prev[0].startsWith("m=video ")) return@map section
            val prevCodecLines = prev.filter { isCodecLine(it) }
            if (prevCodecLines.isEmpty()) return@map section
            val out = section.filterNot { isCodecLine(it) }.toMutableList()
            out[0] = prev[0]
            val insertIdx = out.indexOf("a=rtcp-mux")
            if (insertIdx >= 0) out.addAll(insertIdx + 1, prevCodecLines) else out.addAll(prevCodecLines)
            changed = true
            out
        }
        if (!changed) return offerSdp
        val sb = StringBuilder()
        for (line in nextSession) sb.append(line).append("\r\n")
        for (section in stabilized) for (line in section) sb.append(line).append("\r\n")
        return sb.toString()
    }

    private fun send(json: JSONObject) {
        val ws = webSocket ?: return
        val type = json.optString("type")
        if (type != "pong") Log.d(TAG, "send=$type")
        ws.send(json.toString())
    }

    private fun emitState(state: SfuConnectionState) {
        onConnectionState?.invoke(state)
    }

    private suspend fun awaitSetRemote(pc: PeerConnection, desc: SessionDescription) =
        suspendCancellableCoroutine { cont ->
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() { cont.resume(Unit) }
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(p0: String?) { cont.resumeWithException(RuntimeException("setRemote: $p0")) }
            }, desc)
        }

    private suspend fun awaitSetLocal(pc: PeerConnection, desc: SessionDescription) =
        suspendCancellableCoroutine { cont ->
            pc.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() { cont.resume(Unit) }
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(p0: String?) { cont.resumeWithException(RuntimeException("setLocal: $p0")) }
            }, desc)
        }

    private suspend fun awaitCreateAnswer(pc: PeerConnection): SessionDescription =
        suspendCancellableCoroutine { cont ->
            pc.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {
                    if (p0 != null) cont.resume(p0) else cont.resumeWithException(RuntimeException("createAnswer null"))
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) { cont.resumeWithException(RuntimeException("createAnswer: $p0")) }
                override fun onSetFailure(p0: String?) {}
            }, MediaConstraints())
        }

    private fun makePeerObserver(gen: Int) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            Log.d(TAG, "connectionState=$newState")
        }
        override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "iceConnectionState=$state")
            appScope.launch(mainDispatcher) {
                if (gen != connectionGen) return@launch
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> emitState(SfuConnectionState.CONNECTED)
                    PeerConnection.IceConnectionState.FAILED -> {
                        if (active && joined) {
                            emitState(SfuConnectionState.DISCONNECTED)
                            runCatching { webSocket?.close(1000, null) }
                        } else if (active) {
                            emitState(SfuConnectionState.FAILED)
                        }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> emitState(SfuConnectionState.DISCONNECTED)
                    else -> {}
                }
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(candidate: IceCandidate?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            appScope.launch(mainDispatcher) {
                if (gen != connectionGen) return@launch
                if (peerConnection == null) return@launch
                Log.d(TAG, "onAddTrack kind=${receiver?.track()?.kind()} id=${receiver?.track()?.id()}")
                syncRemoteMedia()
            }
        }
    }
}
