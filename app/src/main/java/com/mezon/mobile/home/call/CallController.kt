package com.mezon.mobile.home.call

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.mezon.mobile.MainActivity
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.network.ConnectionState
import com.mezon.mobile.network.MezonSocket
import com.mezon.mobile.network.SocketEventDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.AudioTrack
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CallController"
private const val CALL_TIMEOUT_MS = 30_000L

@Singleton
class CallController @Inject constructor(
    private val socket: MezonSocket,
    private val dispatcher: SocketEventDispatcher,
    private val notificationCenter: NotificationCenter,
    private val userController: UserController,
    private val webRtcInfra: WebRtcInfra,
    private val callManager: CallManager,
    @ApplicationContext private val appContext: Context,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : PeerConnectionWrapper.Listener {

    @Volatile
    var callState: CallState = CallState.Idle
        private set

    @Volatile var isLocalAudioEnabled = true; private set
    @Volatile var isLocalVideoEnabled = false; private set
    @Volatile var isRemoteAudioEnabled = true; private set
    @Volatile var isRemoteVideoEnabled = false; private set
    @Volatile var isSpeakerOn = false; private set

    var remoteVideoTrack: VideoTrack? = null
        private set
    var remoteAudioTrack: AudioTrack? = null
        private set

    private var peerConnection: PeerConnectionWrapper? = null
    private var callAudioManager: CallAudioManager? = null
    private var timeoutJob: Job? = null
    private var localOffer: SessionDescription? = null
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    init {
        instance = this
        appScope.launch {
            dispatcher.webrtcSignalingFwdEvents.collect { event ->
                withContext(Dispatchers.Main) {
                    handleSignaling(
                        callerId = event.callerId,
                        receiverId = event.receiverId,
                        channelId = event.channelId,
                        dataType = event.dataType,
                        jsonData = event.jsonData
                    )
                }
            }
        }
    }

    fun startCall(peerId: Long, peerName: String, peerAvatar: String?, channelId: Long, isVideo: Boolean) {
        if (callState !is CallState.Idle) {
            Log.w(TAG, "Cannot start call: already in call state ${callState::class.simpleName}")
            return
        }

        val callInfo = CallInfo(
            peerId = peerId,
            peerName = peerName,
            peerAvatar = peerAvatar,
            channelId = channelId,
            isVideo = isVideo,
            isInitiator = true
        )

        isLocalAudioEnabled = true
        isLocalVideoEnabled = false
        isSpeakerOn = isVideo

        callAudioManager = CallAudioManager(appContext).also { it.start(isVideo) }
        callAudioManager?.playDialTone()

        webRtcInfra.prewarm()
        peerConnection = PeerConnectionWrapper(appContext, this, webRtcInfra)
        peerConnection!!.createOffer(isVideo) { offer ->
            localOffer = offer
            callState = CallState.Outgoing(callInfo, SystemClock.elapsedRealtime())

            val callerName = userController.displayName.ifEmpty { userController.username }
            val callerAvatar = userController.avatarUrl
            val offerJson = JSONObject().apply {
                put("sdp", offer.description)
                put("type", "offer")
                put("isVideo", isVideo)
                put("callerName", callerName)
                put("callerAvatar", callerAvatar)
            }.toString()
            val compressedPayload = SdpCompressor.compress(offerJson)

            val fcmPayload = JSONObject().apply {
                put("offer", compressedPayload)
                put("isVideo", isVideo)
                put("callerName", callerName)
                put("callerAvatar", callerAvatar)
                put("callerId", userController.userIdStr)
                put("channelId", channelId.toString())
            }.toString()

            Log.d(TAG, "startCall: sending offer to peer=$peerId")
            appScope.launch(ioDispatcher) {
                try {
                    socket.forwardWebrtcSignaling(
                        receiverId = peerId,
                        dataType = WebrtcSignalingType.SDP_OFFER,
                        jsonData = compressedPayload,
                        channelId = channelId,
                        callerId = userController.userId
                    )
                    socket.makeCallPush(
                        receiverId = peerId,
                        jsonData = fcmPayload,
                        channelId = channelId,
                        callerId = userController.userId
                    )
                    Log.d(TAG, "startCall: offer sent successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send offer", e)
                }
            }

            startTimeout()
            notificationCenter.postNotificationOnMainThread(NotificationCenter.outgoingCallStarted, callInfo)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, callState)
        }
    }

    fun acceptCall() {
        val state = callState
        if (state !is CallState.Incoming) {
            Log.w(TAG, "Cannot accept: not in Incoming state, current=${callState::class.simpleName}")
            return
        }

        Log.d(TAG, "acceptCall: peer=${state.callInfo.peerName}, video=${state.callInfo.isVideo}")
        cancelTimeout()
        callAudioManager?.stopTone()

        val callInfo = state.callInfo
        isLocalAudioEnabled = true
        isLocalVideoEnabled = false
        isSpeakerOn = callInfo.isVideo

        callAudioManager = callAudioManager ?: CallAudioManager(appContext).also { it.start(callInfo.isVideo) }
        if (!callAudioManager!!.isStarted) callAudioManager!!.start(callInfo.isVideo)

        callState = CallState.Connecting(callInfo)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, callState)

        val pc = peerConnection
        if (pc != null) {
            Log.d(TAG, "acceptCall: peerConnection already prepared, only createAnswer")
            pc.createAnswerAndFlush { answer -> sendAnswer(callInfo, answer) }
            return
        }

        Log.d(TAG, "acceptCall: creating PeerConnection (no eager prep)")
        webRtcInfra.prewarm()
        peerConnection = PeerConnectionWrapper(appContext, this, webRtcInfra)
        flushPendingIceCandidates()

        Log.d(TAG, "acceptCall: handling remote offer, sdp length=${state.offer.description.length}")
        peerConnection!!.handleRemoteOffer(state.offer) { answer -> sendAnswer(callInfo, answer) }
    }

    private fun sendAnswer(callInfo: CallInfo, answer: SessionDescription) {
        Log.d(TAG, "sendAnswer: sdp length=${answer.description.length}")
        if (callState !is CallState.Connecting && callState !is CallState.Connected) {
            callState = CallState.Connecting(callInfo)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, callState)
        }

        val answerJson = JSONObject().apply {
            put("sdp", answer.description)
            put("type", "answer")
        }.toString()
        val compressedPayload = SdpCompressor.compress(answerJson)

        appScope.launch(ioDispatcher) {
            try {
                socket.forwardWebrtcSignaling(
                    receiverId = callInfo.peerId,
                    dataType = WebrtcSignalingType.SDP_ANSWER,
                    jsonData = compressedPayload,
                    channelId = callInfo.channelId,
                    callerId = userController.userId
                )
                Log.d(TAG, "sendAnswer: answer sent to peer=${callInfo.peerId}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send answer", e)
            }
        }
    }

    fun acceptCallFromFcm(offerJson: String) {
        try {
            Log.d(TAG, "acceptCallFromFcm: parsing FCM offer data")
            val parsed = JSONObject(offerJson)
            val callerName = parsed.optString("callerName", "Unknown")
            val callerAvatar = parsed.optString("callerAvatar", "")
            val callerIdStr = parsed.optString("callerId", "0")
            val channelIdStr = parsed.optString("channelId", "0")

            val compressedOffer = parsed.optString("offer", parsed.optString("sdp", ""))
            val decompressedOffer = SdpCompressor.decompress(compressedOffer)
            val offerParsed = JSONObject(decompressedOffer)
            val rawSdp = offerParsed.optString("sdp", "")
            val sdpString = if (rawSdp.startsWith("v=")) rawSdp else SdpCompressor.decompress(rawSdp)

            val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
            val isVideo = parsed.optBoolean("isVideo", sdpString.contains("m=video"))

            val callInfo = CallInfo(
                peerId = callerIdStr.toLongOrNull() ?: 0L,
                peerName = callerName,
                peerAvatar = callerAvatar.ifEmpty { null },
                channelId = channelIdStr.toLongOrNull() ?: 0L,
                isVideo = isVideo,
                isInitiator = false
            )

            Log.d(TAG, "acceptCallFromFcm: caller=$callerName, sdpLen=${sdpString.length}")
            callState = CallState.Incoming(callInfo, sdp)
            synchronized(pendingIceCandidates) { pendingIceCandidates.clear() }
            acceptCall()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept call from FCM", e)
            endCall(CallEndReason.ERROR)
        }
    }

    fun handleIncomingOfferFromFcm(
        callerName: String,
        callerAvatar: String,
        callerId: String,
        channelId: String,
        offerJson: String
    ) {
        if (callState !is CallState.Idle) {
            Log.d(TAG, "handleIncomingOfferFromFcm: not idle, state=${callState::class.simpleName}")
            return
        }

        try {
            val parsed = parseSignalingData(offerJson)
            val rawSdp = parsed.optString("sdp", parsed.optString("offer", ""))
            if (rawSdp.isEmpty()) {
                Log.w(TAG, "handleIncomingOfferFromFcm: no SDP found in offer")
                return
            }

            val sdpString = if (rawSdp.startsWith("v=")) rawSdp else SdpCompressor.decompress(rawSdp)
            val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
            val isVideo = parsed.optBoolean("isVideo", sdpString.contains("m=video"))
            val peerIdLong = callerId.toLongOrNull() ?: 0L
            val channelIdLong = channelId.toLongOrNull() ?: 0L

            Log.d(TAG, "handleIncomingOfferFromFcm: caller=$callerName, video=$isVideo, sdpLen=${sdpString.length}")

            val callInfo = CallInfo(
                peerId = peerIdLong,
                peerName = callerName,
                peerAvatar = callerAvatar.ifEmpty { null },
                channelId = channelIdLong,
                isVideo = isVideo,
                isInitiator = false
            )

            callState = CallState.Incoming(callInfo, sdp)
            synchronized(pendingIceCandidates) { pendingIceCandidates.clear() }
            prepareIncomingPeerConnection(sdp)
            callAudioManager = CallAudioManager(appContext).also { it.start(isVideo) }
            callAudioManager?.playRingtone()

            startTimeout()
            notificationCenter.postNotificationOnMainThread(NotificationCenter.incomingCall, callInfo)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, callState)
        } catch (e: Exception) {
            Log.e(TAG, "handleIncomingOfferFromFcm: failed", e)
        }
    }

    private fun prepareIncomingPeerConnection(sdp: SessionDescription) {
        if (peerConnection != null) {
            Log.d(TAG, "prepareIncomingPeerConnection: already prepared, skip")
            return
        }
        try {
            webRtcInfra.prewarm()
            val wrapper = PeerConnectionWrapper(appContext, this, webRtcInfra)
            peerConnection = wrapper
            wrapper.handleRemoteOfferEager(sdp)
            flushPendingIceCandidates()
        } catch (e: Exception) {
            Log.e(TAG, "prepareIncomingPeerConnection failed", e)
            peerConnection?.dispose()
            peerConnection = null
        }
    }

    private fun isAppInForegroundForIncomingCall(): Boolean = MainActivity.isResumed

    private fun tryPresentIncomingCallBackgroundUi(callInfo: CallInfo, offerJsonPayload: String) {
        if (isAppInForegroundForIncomingCall()) return
        try {
            callManager.showIncomingCall(
                callInfo.peerName,
                callInfo.peerId.toString(),
                callInfo.channelId.toString(),
                offerJsonPayload
            )
        } catch (e: Exception) {
            Log.w(TAG, "Telecom showIncomingCall failed", e)
        }
        val useFsi = callManager.canUseFullScreenIntent()
        try {
            CallForegroundService.startRinging(
                appContext,
                callInfo.peerName,
                callInfo.peerAvatar ?: "",
                callInfo.peerId.toString(),
                callInfo.channelId.toString(),
                offerJsonPayload,
                useFsi
            )
        } catch (e: Exception) {
            Log.e(TAG, "incoming ring FGS failed, fallback notify", e)
            try {
                CallNotificationManager(appContext).showIncomingCallNotification(
                    callerName = callInfo.peerName,
                    callerAvatar = callInfo.peerAvatar,
                    callerId = callInfo.peerId.toString(),
                    channelId = callInfo.channelId.toString(),
                    offerJson = offerJsonPayload,
                    useFullScreenIntent = useFsi
                )
            } catch (e2: Exception) {
                Log.e(TAG, "fallback notification failed", e2)
            }
        }
    }

    fun rejectCall() {
        val state = callState
        val callInfo = when (state) {
            is CallState.Incoming -> state.callInfo
            else -> {
                Log.w(TAG, "Cannot reject: not in Incoming state")
                return
            }
        }

        cancelTimeout()
        sendSignaling(callInfo.peerId, callInfo.channelId, WebrtcSignalingType.SDP_QUIT, "")
        endCall(CallEndReason.LOCAL_REJECT)
    }

    fun hangup() {
        val callInfo = currentCallInfo() ?: return
        sendSignaling(callInfo.peerId, callInfo.channelId, WebrtcSignalingType.SDP_QUIT, "")
        endCall(CallEndReason.LOCAL_HANGUP)
    }

    fun toggleMic() {
        isLocalAudioEnabled = !isLocalAudioEnabled
        peerConnection?.setLocalAudioEnabled(isLocalAudioEnabled)
        sendMediaStatus()
        notificationCenter.postNotificationOnMainThread(NotificationCenter.callMediaChanged)
    }

    fun toggleVideo() {
        val next = !isLocalVideoEnabled
        if (next) {
            val hadTrack = peerConnection?.hasLocalVideoTrack() == true
            val addedNew = peerConnection?.addLocalVideoTrackIfAbsent() == true
            if (addedNew) {
                sendRenegotiationOffer()
            }
            if (!hadTrack && !addedNew) {
                return
            }
        }
        isLocalVideoEnabled = next
        peerConnection?.setLocalVideoEnabled(isLocalVideoEnabled)
        sendMediaStatus()
        notificationCenter.postNotificationOnMainThread(NotificationCenter.callMediaChanged)
    }

    fun toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn
        if (isSpeakerOn) {
            callAudioManager?.setSpeaker()
        } else {
            callAudioManager?.setEarpiece()
        }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.callMediaChanged)
    }

    fun switchCamera() {
        peerConnection?.switchCamera()
    }

    fun endCall(reason: CallEndReason) {
        cancelTimeout()

        callAudioManager?.stopTone()
        when (reason) {
            CallEndReason.BUSY -> callAudioManager?.playBusyTone()
            CallEndReason.LOCAL_HANGUP, CallEndReason.REMOTE_HANGUP -> callAudioManager?.playEndCallTone()
            else -> {}
        }

        val prevInfo = currentCallInfo()

        peerConnection?.dispose()
        peerConnection = null
        remoteVideoTrack = null
        remoteAudioTrack = null

        callState = CallState.Idle
        isLocalAudioEnabled = true
        isLocalVideoEnabled = false
        isRemoteAudioEnabled = true
        isRemoteVideoEnabled = false
        isSpeakerOn = false
        localOffer = null
        synchronized(pendingIceCandidates) { pendingIceCandidates.clear() }

        try {
            appContext.getSharedPreferences("call_data", android.content.Context.MODE_PRIVATE)
                .edit().remove("incoming_call").apply()
        } catch (_: Exception) {}

        appScope.launch {
            delay(if (reason == CallEndReason.BUSY) 2000L else 500L)
            withContext(Dispatchers.Main) {
                callAudioManager?.stop()
                callAudioManager = null
            }
        }

        notificationCenter.postNotificationOnMainThread(NotificationCenter.callEnded, reason, prevInfo)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, CallState.Idle)
    }

    private fun handleSignaling(
        callerId: Long,
        receiverId: Long,
        channelId: Long,
        dataType: Int,
        jsonData: String
    ) {
        when (dataType) {
            WebrtcSignalingType.SDP_OFFER -> handleOffer(callerId, channelId, jsonData)
            WebrtcSignalingType.SDP_ANSWER -> handleAnswer(jsonData)
            WebrtcSignalingType.ICE_CANDIDATE -> handleIceCandidate(jsonData)
            WebrtcSignalingType.SDP_QUIT -> handleRemoteQuit()
            WebrtcSignalingType.SDP_TIMEOUT -> handleRemoteTimeout()
            WebrtcSignalingType.SDP_JOINED_OTHER_CALL -> handleBusy()
            WebrtcSignalingType.STATUS_REMOTE_MEDIA -> handleRemoteMedia(jsonData)
            WebrtcSignalingType.CLEAR_CALL -> handleClearCall()
            WebrtcSignalingType.SDP_INIT -> handleSdpInit()
        }
    }

    private fun handleOffer(callerId: Long, channelId: Long, jsonData: String) {
        if (callState is CallState.Connected) {
            handleRenegotiationOffer(callerId, channelId, jsonData)
            return
        }
        if (callState !is CallState.Idle) {
            Log.d(TAG, "handleOffer: busy, current state=${callState::class.simpleName}")
            sendSignaling(callerId, channelId, WebrtcSignalingType.SDP_JOINED_OTHER_CALL, "")
            return
        }

        try {
            val parsed = parseSignalingData(jsonData)
            val rawSdp = parsed.optString("sdp", parsed.optString("offer", ""))
            val callerName = parsed.optString("callerName", "Unknown")
            val callerAvatar = parsed.optString("callerAvatar", "")

            val sdpString = if (rawSdp.startsWith("v=")) rawSdp else SdpCompressor.decompress(rawSdp)
            val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
            val isVideo = parsed.optBoolean("isVideo", sdpString.contains("m=video"))

            Log.d(TAG, "handleOffer: caller=$callerName, video=$isVideo, sdpLen=${sdpString.length}")

            val callInfo = CallInfo(
                peerId = callerId,
                peerName = callerName,
                peerAvatar = callerAvatar.ifEmpty { null },
                channelId = channelId,
                isVideo = isVideo,
                isInitiator = false
            )

            callState = CallState.Incoming(callInfo, sdp)
            synchronized(pendingIceCandidates) { pendingIceCandidates.clear() }
            prepareIncomingPeerConnection(sdp)
            callAudioManager = CallAudioManager(appContext).also { it.start(isVideo) }
            callAudioManager?.playRingtone()

            startTimeout()
            notificationCenter.postNotificationOnMainThread(NotificationCenter.incomingCall, callInfo)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, callState)
            try {
                appContext.getSharedPreferences("call_data", Context.MODE_PRIVATE).edit()
                    .putString("incoming_call", jsonData)
                    .commit()
            } catch (_: Exception) {}
            tryPresentIncomingCallBackgroundUi(callInfo, jsonData)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle offer", e)
        }
    }

    private fun handleAnswer(jsonData: String) {
        val state = callState
        if (state !is CallState.Outgoing && state !is CallState.Connected) {
            Log.w(TAG, "handleAnswer: unexpected state, current=${callState::class.simpleName}")
            return
        }

        Log.d(TAG, "handleAnswer: received answer")
        if (state is CallState.Outgoing) {
            cancelTimeout()
            callAudioManager?.stopTone()
        }

        try {
            val parsed = parseSignalingData(jsonData)
            val rawSdp = parsed.optString("sdp", "")
            val sdpString = if (rawSdp.startsWith("v=")) rawSdp else SdpCompressor.decompress(rawSdp)
            val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpString)

            Log.d(TAG, "handleAnswer: setting remote answer, sdpLen=${sdpString.length}")
            peerConnection?.handleRemoteAnswer(sdp)
            if (state is CallState.Outgoing) {
                callState = CallState.Connecting(state.callInfo)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, callState)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle answer", e)
            endCall(CallEndReason.ERROR)
        }
    }

    private fun handleIceCandidate(jsonData: String) {
        try {
            val json = JSONObject(jsonData)
            val candidate = IceCandidate(
                json.getString("sdpMid"),
                json.getInt("sdpMLineIndex"),
                json.getString("candidate")
            )
            val pc = peerConnection
            if (pc != null) {
                pc.addRemoteIceCandidate(candidate)
            } else {
                synchronized(pendingIceCandidates) {
                    pendingIceCandidates.add(candidate)
                }
                Log.d(TAG, "Queued ICE candidate (no PeerConnection yet), total=${pendingIceCandidates.size}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ICE candidate", e)
        }
    }

    private fun flushPendingIceCandidates() {
        val pc = peerConnection ?: return
        synchronized(pendingIceCandidates) {
            if (pendingIceCandidates.isNotEmpty()) {
                Log.d(TAG, "Flushing ${pendingIceCandidates.size} pending ICE candidates")
                for (candidate in pendingIceCandidates) {
                    pc.addRemoteIceCandidate(candidate)
                }
                pendingIceCandidates.clear()
            }
        }
    }

    private fun handleRemoteQuit() {
        endCall(CallEndReason.REMOTE_HANGUP)
    }

    private fun handleRemoteTimeout() {
        endCall(CallEndReason.REMOTE_TIMEOUT)
    }

    private fun handleBusy() {
        endCall(CallEndReason.BUSY)
    }

    private fun handleRemoteMedia(jsonData: String) {
        try {
            val json = JSONObject(jsonData)
            if (json.has("micEnabled")) {
                isRemoteAudioEnabled = json.getBoolean("micEnabled")
            }
            if (json.has("cameraEnabled")) {
                isRemoteVideoEnabled = json.getBoolean("cameraEnabled")
            }
            notificationCenter.postNotificationOnMainThread(NotificationCenter.callMediaChanged)
        } catch (_: Exception) {}
    }

    private fun handleClearCall() {
        endCall(CallEndReason.CLEAR_CALL)
    }

    private fun handleSdpInit() {
        val state = callState
        if (state is CallState.Connecting) {
            callState = CallState.Connected(state.callInfo, SystemClock.elapsedRealtime())
            callAudioManager?.stopTone()
            sendMediaStatus()
            notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, callState)
        }
    }

    override fun onLocalIceCandidate(candidate: IceCandidate) {
        val callInfo = currentCallInfo() ?: return
        val json = JSONObject().apply {
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        }.toString()

        sendSignaling(callInfo.peerId, callInfo.channelId, WebrtcSignalingType.ICE_CANDIDATE, json)
    }

    override fun onIceConnected() {
        val state = callState
        if (state is CallState.Connecting || state is CallState.Outgoing) {
            val callInfo = currentCallInfo() ?: return
            callState = CallState.Connected(callInfo, SystemClock.elapsedRealtime())
            cancelTimeout()
            callAudioManager?.stopTone()

            sendSignaling(callInfo.peerId, callInfo.channelId, WebrtcSignalingType.SDP_INIT, "")
            sendMediaStatus()
            notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, callState)
        }
    }

    override fun onIceDisconnected() {
        endCall(CallEndReason.ICE_FAILED)
    }

    override fun onIceFailed() {
        endCall(CallEndReason.ICE_FAILED)
    }

    override fun onRemoteVideoTrack(track: VideoTrack) {
        remoteVideoTrack = track
        notificationCenter.postNotificationOnMainThread(NotificationCenter.callMediaChanged)
    }

    override fun onRemoteAudioTrack(track: AudioTrack) {
        remoteAudioTrack = track
    }

    fun currentCallInfo(): CallInfo? = when (val s = callState) {
        is CallState.Outgoing -> s.callInfo
        is CallState.Incoming -> s.callInfo
        is CallState.Connecting -> s.callInfo
        is CallState.Connected -> s.callInfo
        is CallState.Idle -> null
    }

    fun getCallDurationMs(): Long {
        val state = callState
        return if (state is CallState.Connected) {
            SystemClock.elapsedRealtime() - state.connectedTime
        } else 0L
    }

    fun getPeerConnection(): PeerConnectionWrapper? = peerConnection

    private fun startTimeout() {
        cancelTimeout()
        timeoutJob = appScope.launch(Dispatchers.Main) {
            delay(CALL_TIMEOUT_MS)
            val callInfo = currentCallInfo() ?: return@launch
            sendSignaling(callInfo.peerId, callInfo.channelId, WebrtcSignalingType.SDP_TIMEOUT, "")
            endCall(CallEndReason.TIMEOUT)
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private fun sendSignaling(peerId: Long, channelId: Long, dataType: Int, jsonData: String) {
        appScope.launch(ioDispatcher) {
            try {
                if (socket.connectionState.value != ConnectionState.CONNECTED) {
                    Log.d(TAG, "sendSignaling: socket not connected, waiting... (type=$dataType)")
                    val connected = socket.awaitConnected(15_000L)
                    if (!connected) {
                        Log.e(TAG, "sendSignaling: socket connection timeout, dropping type=$dataType")
                        return@launch
                    }
                    Log.d(TAG, "sendSignaling: socket connected, sending type=$dataType")
                }
                socket.forwardWebrtcSignaling(
                    receiverId = peerId,
                    dataType = dataType,
                    jsonData = jsonData,
                    channelId = channelId,
                    callerId = userController.userId
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send signaling type=$dataType", e)
            }
        }
    }

    private fun sendRenegotiationOffer() {
        val callInfo = currentCallInfo() ?: return
        val pc = peerConnection ?: return
        pc.createRenegotiationOffer { offer ->
            val offerJson = JSONObject().apply {
                put("sdp", offer.description)
                put("type", "offer")
                put("isVideo", true)
            }.toString()
            val compressedPayload = SdpCompressor.compress(offerJson)
            appScope.launch(ioDispatcher) {
                try {
                    socket.forwardWebrtcSignaling(
                        receiverId = callInfo.peerId,
                        dataType = WebrtcSignalingType.SDP_OFFER,
                        jsonData = compressedPayload,
                        channelId = callInfo.channelId,
                        callerId = userController.userId
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "sendRenegotiationOffer failed", e)
                }
            }
        }
    }

    private fun handleRenegotiationOffer(callerId: Long, channelId: Long, jsonData: String) {
        val state = callState
        if (state !is CallState.Connected) return
        val info = state.callInfo
        if (info.peerId != callerId || info.channelId != channelId) {
            Log.w(TAG, "handleRenegotiationOffer: peer mismatch")
            return
        }
        try {
            val parsed = parseSignalingData(jsonData)
            val rawSdp = parsed.optString("sdp", parsed.optString("offer", ""))
            if (rawSdp.isEmpty()) {
                Log.w(TAG, "handleRenegotiationOffer: empty sdp")
                return
            }
            val sdpString = if (rawSdp.startsWith("v=")) rawSdp else SdpCompressor.decompress(rawSdp)
            val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
            peerConnection?.handleRenegotiationRemoteOffer(sdp) { answer ->
                val answerJson = JSONObject().apply {
                    put("sdp", answer.description)
                    put("type", "answer")
                }.toString()
                val compressedPayload = SdpCompressor.compress(answerJson)
                appScope.launch(ioDispatcher) {
                    try {
                        socket.forwardWebrtcSignaling(
                            receiverId = info.peerId,
                            dataType = WebrtcSignalingType.SDP_ANSWER,
                            jsonData = compressedPayload,
                            channelId = info.channelId,
                            callerId = userController.userId
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "handleRenegotiationOffer: send answer failed", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleRenegotiationOffer failed", e)
        }
    }

    private fun sendMediaStatus() {
        val callInfo = currentCallInfo() ?: return
        val json = JSONObject().apply {
            put("micEnabled", isLocalAudioEnabled)
            put("cameraEnabled", isLocalVideoEnabled)
        }.toString()
        sendSignaling(callInfo.peerId, callInfo.channelId, WebrtcSignalingType.STATUS_REMOTE_MEDIA, json)
    }

    private fun parseSignalingData(jsonData: String): JSONObject {
        return try {
            JSONObject(jsonData)
        } catch (_: Exception) {
            Log.d(TAG, "parseSignalingData: not plain JSON, trying decompress...")
            val decompressed = SdpCompressor.decompress(jsonData)
            JSONObject(decompressed)
        }
    }

    companion object {
        @Volatile
        var instance: CallController? = null
            private set
    }
}
