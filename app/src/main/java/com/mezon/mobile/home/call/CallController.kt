package com.mezon.mobile.home.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.mezon.mobile.MainActivity
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.ChatController
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
import dagger.Lazy

private const val TAG = "CallController"
private const val CALL_TIMEOUT_MS = 30_000L
private const val CONNECTING_PHASE_TIMEOUT_MS = 60_000L
private const val REMOTE_VIDEO_INITIAL_SUPPRESS_MS = 2000L
private const val WEBRTC_CANDIDATE_LOG_MAX = 200

private fun sdpMLineCount(sdp: String): Int = sdp.lineSequence().count { it.startsWith("m=") }

private fun sdpPreviewForLog(sdp: String): String {
    val head = sdp.replace("\r\n", "\n").lineSequence()
        .take(4)
        .joinToString(" ¦ ") { it.trim().take(96) }
    return "len=${sdp.length} mLines=${sdpMLineCount(sdp)} head=[$head]"
}

private fun iceCandidatePreviewForLog(c: IceCandidate): String {
    val s = c.sdp
    val clip = s.take(WEBRTC_CANDIDATE_LOG_MAX)
    val more = if (s.length > WEBRTC_CANDIDATE_LOG_MAX) "…(+${s.length - WEBRTC_CANDIDATE_LOG_MAX})" else ""
    return "mid=${c.sdpMid} mline=${c.sdpMLineIndex} $clip$more"
}

private fun iceCandidateKindForLog(sdp: String): String = when {
    sdp.contains(" typ relay ") -> "relay"
    sdp.contains(" typ srflx ") -> "srflx"
    sdp.contains(" typ host ") -> "host"
    sdp.contains(" typ prflx ") -> "prflx"
    sdp.contains("end-of-candidates", ignoreCase = true) -> "eoc"
    sdp.isBlank() -> "empty"
    else -> "other"
}

@Singleton
class CallController @Inject constructor(
    private val socket: MezonSocket,
    private val dispatcher: SocketEventDispatcher,
    private val notificationCenter: NotificationCenter,
    private val userController: UserController,
    private val webRtcInfra: WebRtcInfra,
    private val callManager: CallManager,
    private val chatController: Lazy<ChatController>,
    private val callLogHelper: CallLogHelper,
    @ApplicationContext private val appContext: Context,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : PeerConnectionWrapper.Listener {

    @Volatile
    var callState: CallState = CallState.Idle
        private set

    fun isCallSessionActive(): Boolean = callState !is CallState.Idle

    fun shouldShowRemoteVideoForUi(): Boolean {
        val state = callState as? CallState.Connected ?: return false
        if (remoteVideoTrack == null) return false
        val info = state.callInfo
        if (!info.isVideo) return isRemoteVideoEnabled
        if (SystemClock.elapsedRealtime() - state.connectedTime < REMOTE_VIDEO_INITIAL_SUPPRESS_MS) return false
        if (isRemoteVideoEnabled) return true
        if (!remoteCameraEverSignaled) return true
        return false
    }

    fun stopIncomingCallRingtone() {
        callAudioManager?.stopTone()
    }

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
    private var connectingPhaseTimeoutJob: Job? = null
    private var localOffer: SessionDescription? = null
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    private var handshakeIceOutSeq = 0
    private var handshakeIceInWireSeq = 0

    private val incomingPrepareHandler = Handler(Looper.getMainLooper())

    @Volatile private var suppressDuplicateOfferFingerprint: String? = null
    @Volatile private var suppressDuplicateOfferUntilElapsed: Long = 0L
    private var remoteVideoRevealRunnable: Runnable? = null
    @Volatile private var remoteCameraEverSignaled: Boolean = false

    @Volatile private var activeCallLogMessageId: Long = 0L
    private var activeCallClanId: Long = 0L
    private var activeCallChannelType: Int = com.mezon.mobile.network.CHANNEL_TYPE_DM
    private var activeCallIsPrivate: Boolean = false

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

    private fun resetHandshakeDebugCounters() {
        handshakeIceOutSeq = 0
        handshakeIceInWireSeq = 0
    }

    fun startCall(
        peerId: Long,
        peerName: String,
        peerAvatar: String?,
        channelId: Long,
        clanId: Long,
        channelType: Int,
        isChannelPrivate: Boolean,
        isVideo: Boolean
    ) {
        if (callState !is CallState.Idle) {
            Log.w(TAG, "Cannot start call: already in call state ${callState::class.simpleName}")
            return
        }

        resetHandshakeDebugCounters()

        activeCallClanId = clanId
        activeCallChannelType = channelType
        activeCallIsPrivate = isChannelPrivate
        activeCallLogMessageId = 0L

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

        appScope.launch(ioDispatcher) {
            val usernameLine = userController.username
            val startLine = buildStartCallLine(usernameLine, isVideo)
            val startJson = buildCallLogJson(startLine, CallLogMessageType.STARTCALL, isVideo)
            val msgId = try {
                chatController.get().sendRawChannelMessage(
                    channelId, clanId, channelType, isChannelPrivate, startJson
                )
            } catch (e: Exception) {
                Log.e(TAG, "STARTCALL send failed", e)
                0L
            }
            withContext(Dispatchers.Main) {
                activeCallLogMessageId = msgId
            }
            val pc = peerConnection
            if (pc == null) return@launch
            pc.createOffer(isVideo) { offer ->
                appScope.launch(Dispatchers.Main) {
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

                    Log.d(TAG, "[WEBRTC:OFFER_LOCAL] peer=$peerId ch=$channelId video=$isVideo ${sdpPreviewForLog(offer.description)}")
                    Log.d(TAG, "startCall: payload offerJson=${offerJson.length} compressed=${compressedPayload.length} fcm=${fcmPayload.length} peer=$peerId")
                    appScope.launch(ioDispatcher) {
                        val signalingOk = sendWithRetry("startCall:SDP_OFFER") {
                            socket.forwardWebrtcSignaling(
                                receiverId = peerId,
                                dataType = WebrtcSignalingType.SDP_OFFER,
                                jsonData = compressedPayload,
                                channelId = channelId,
                                callerId = userController.userId
                            )
                        }
                        val fcmOk = sendWithRetry("startCall:FCM_PUSH") {
                            socket.makeCallPush(
                                receiverId = peerId,
                                jsonData = fcmPayload,
                                channelId = channelId,
                                callerId = userController.userId
                            )
                        }
                        if (!signalingOk && !fcmOk) {
                            Log.e(TAG, "startCall: both signaling and FCM failed, aborting call")
                            withContext(Dispatchers.Main) {
                                if (callState is CallState.Outgoing) endCall(CallEndReason.ERROR)
                            }
                        } else {
                            Log.d(TAG, "[WEBRTC:OFFER_SENT] peer=$peerId ch=$channelId signaling=$signalingOk fcm=$fcmOk")
                            Log.d(TAG, "startCall: offer dispatched (signaling=$signalingOk fcm=$fcmOk)")
                        }
                    }

                    startTimeout()
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.outgoingCallStarted, callInfo)
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, callState)
                }
            }
        }
    }

    fun acceptCall() {
        val state = callState
        if (state !is CallState.Incoming) {
            Log.w(TAG, "Cannot accept: not in Incoming state, current=${callState::class.simpleName}")
            return
        }

        val offer = state.offer
        val sdp = offer.description ?: ""
        val sdpLen = sdp.length
        val sdpBlank = sdp.isBlank()
        val sdpHasOfferShape = sdp.contains("v=0") && (sdp.contains("\nm=") || sdp.contains("\r\nm=") || sdp.startsWith("m="))
        val pcPrepared = peerConnection != null
        val pendingIce = synchronized(pendingIceCandidates) { pendingIceCandidates.size }
        val socketState = socket.connectionState.value
        Log.d(
            TAG,
            "acceptCall: incoming offer present=${!sdpBlank} offerType=${offer.type} sdpLen=$sdpLen sdpLooksLikeOffer=$sdpHasOfferShape sdpBlank=$sdpBlank peerPcPrepared=$pcPrepared pendingRemoteIceQueued=$pendingIce socketState=$socketState peer=${state.callInfo.peerName} peerId=${state.callInfo.peerId} channelId=${state.callInfo.channelId} video=${state.callInfo.isVideo}"
        )

        cancelTimeout()
        callAudioManager?.stopTone()

        val callInfo = state.callInfo
        isLocalAudioEnabled = true
        isLocalVideoEnabled = false
        isSpeakerOn = callInfo.isVideo

        if (callAudioManager == null) {
            callAudioManager = CallAudioManager(appContext).also { it.startForIncomingRing() }
        }
        callAudioManager!!.advanceToEstablishedCallRouting(callInfo.isVideo)

        callState = CallState.Connecting(callInfo)
        startConnectingPhaseTimeout()
        notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, callState)

        try {
            CallForegroundService.startConnecting(appContext, callInfo.peerName)
        } catch (e: Exception) {
            Log.w(TAG, "startConnecting FGS failed", e)
            try {
                CallForegroundService.stop(appContext)
            } catch (_: Exception) {
            }
            try {
                CallNotificationManager(appContext).dismissIncomingNotification()
            } catch (_: Exception) {
            }
        }

        val pc = peerConnection
        if (pc != null) {
            Log.d(TAG, "acceptCall: branch=eager_pc path=requestAnswerWhenRemoteReady sdpLen=$sdpLen")
            pc.requestAnswerWhenRemoteReady { answer -> sendAnswer(callInfo, answer) }
            return
        }

        Log.d(TAG, "acceptCall: branch=lazy_pc path=handleRemoteOffer sdpLen=$sdpLen")
        webRtcInfra.prewarm()
        peerConnection = PeerConnectionWrapper(appContext, this, webRtcInfra)
        flushPendingIceCandidates()

        Log.d(TAG, "acceptCall: handling remote offer, sdp length=${state.offer.description.length}")
        peerConnection!!.handleRemoteOffer(state.offer) { answer -> sendAnswer(callInfo, answer) }
    }

    private fun sendAnswer(callInfo: CallInfo, answer: SessionDescription) {
        Log.d(TAG, "sendAnswer: callState=${callState::class.simpleName} answerSdpLen=${answer.description.length}")
        if (callState !is CallState.Connecting && callState !is CallState.Connected) {
            callState = CallState.Connecting(callInfo)
            startConnectingPhaseTimeout()
            notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, callState)
        }

        val answerJson = JSONObject().apply {
            put("sdp", answer.description)
            put("type", "answer")
        }.toString()
        val compressedPayload = SdpCompressor.compress(answerJson)
        Log.d(TAG, "[WEBRTC:ANSWER_LOCAL] peer=${callInfo.peerId} ch=${callInfo.channelId} ${sdpPreviewForLog(answer.description)}")
        Log.d(TAG, "sendAnswer: payload answerJson=${answerJson.length} compressed=${compressedPayload.length} peer=${callInfo.peerId}")

        appScope.launch(ioDispatcher) {
            val ok = sendWithRetry("sendAnswer") {
                socket.forwardWebrtcSignaling(
                    receiverId = callInfo.peerId,
                    dataType = WebrtcSignalingType.SDP_ANSWER,
                    jsonData = compressedPayload,
                    channelId = callInfo.channelId,
                    callerId = userController.userId
                )
            }
            if (ok) {
                Log.d(TAG, "[WEBRTC:ANSWER_SENT] peer=${callInfo.peerId} ch=${callInfo.channelId}")
                Log.d(TAG, "sendAnswer: answer sent to peer=${callInfo.peerId}")
            } else Log.e(TAG, "sendAnswer: failed after retries — peer will not connect")
        }
    }

    fun acceptCallFromFcm(offerJson: String) {
        try {
            Log.d(TAG, "acceptCallFromFcm: parsing FCM offer data")
            val parsed = parseSignalingData(offerJson)
            val callerName = parsed.optString("callerName", "Unknown")
            val callerAvatar = parsed.optString("callerAvatar", "")
            val callerIdStr = parsed.optString("callerId", "0")
            val channelIdStr = parsed.optString("channelId", "0")

            val sdpString = SdpCompressor.sdpPlainTextFromNegotiationJson(parsed)
            if (sdpString.isNullOrEmpty()) {
                Log.w(TAG, "acceptCallFromFcm: could not resolve SDP")
                endCall(CallEndReason.ERROR)
                return
            }

            val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
            val isVideo = resolveIsVideoFromOfferPayload(parsed, sdpString)

            val callInfo = CallInfo(
                peerId = callerIdStr.toLongOrNull() ?: 0L,
                peerName = callerName,
                peerAvatar = callerAvatar.ifEmpty { null },
                channelId = channelIdStr.toLongOrNull() ?: 0L,
                isVideo = isVideo,
                isInitiator = false
            )

            Log.d(TAG, "acceptCallFromFcm: caller=$callerName, sdpLen=${sdpString.length}")
            resetHandshakeDebugCounters()
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
        Log.d(TAG, "handleIncomingOfferFromFcm: received from caller=$callerId channel=$channelId offerJsonLen=${offerJson.length} currentState=${callState::class.simpleName} socketState=${socket.connectionState.value} appResumed=${MainActivity.isResumed}")
        if (callState !is CallState.Idle) {
            Log.d(TAG, "handleIncomingOfferFromFcm: not idle, skipping (state=${callState::class.simpleName})")
            return
        }

        try {
            val parsed = parseSignalingData(offerJson)
            val sdpString = SdpCompressor.sdpPlainTextFromNegotiationJson(parsed)
            if (sdpString.isNullOrEmpty()) {
                Log.w(TAG, "handleIncomingOfferFromFcm: no SDP found, parsed keys=${parsed.keys().asSequence().toList()}")
                return
            }

            val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
            val isVideo = resolveIsVideoFromOfferPayload(parsed, sdpString)
            val peerIdLong = callerId.toLongOrNull() ?: 0L
            val channelIdLong = channelId.toLongOrNull() ?: 0L

            if (shouldSuppressDuplicateRejectedOffer(peerIdLong, channelIdLong, offerJson)) {
                Log.d(TAG, "handleIncomingOfferFromFcm: suppressed duplicate of rejected offer")
                return
            }

            Log.d(TAG, "handleIncomingOfferFromFcm: parsed caller=$callerName, video=$isVideo, sdpLen=${sdpString.length}, hasV0=${sdpString.contains("v=0")}, hasMaudio=${sdpString.contains("m=audio")}, hasMvideo=${sdpString.contains("m=video")}")

            val callInfo = CallInfo(
                peerId = peerIdLong,
                peerName = callerName,
                peerAvatar = callerAvatar.ifEmpty { null },
                channelId = channelIdLong,
                isVideo = isVideo,
                isInitiator = false
            )

            resetHandshakeDebugCounters()
            callState = CallState.Incoming(callInfo, sdp)
            synchronized(pendingIceCandidates) { pendingIceCandidates.clear() }
            prepareIncomingPeerConnection(sdp)
            incomingPrepareHandler.post {
                if (callState !is CallState.Incoming) return@post
                if (callAudioManager != null) return@post
                callAudioManager = CallAudioManager(appContext).also {
                    it.startForIncomingRing()
                    it.playRingtone()
                }
            }

            startTimeout()
            notificationCenter.postNotificationOnMainThread(NotificationCenter.incomingCall, callInfo)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, callState)
        } catch (e: Exception) {
            Log.e(TAG, "handleIncomingOfferFromFcm: failed", e)
        }
    }

    private fun armSuppressDuplicateRejectedOffer(fingerprint: String?) {
        if (fingerprint.isNullOrEmpty()) return
        suppressDuplicateOfferFingerprint = fingerprint
        suppressDuplicateOfferUntilElapsed = SystemClock.elapsedRealtime() + 2500L
    }

    private fun sessionOfferFingerprint(peerId: Long, channelId: Long, sdp: SessionDescription): String? {
        if (peerId == 0L || channelId == 0L) return null
        return try {
            val canon = SdpCompressor.canonicalizeWebRtcSdp(sdp.description)
            "${peerId}_${channelId}_${canon.hashCode()}"
        } catch (_: Exception) {
            null
        }
    }

    private fun signalingOfferFingerprint(peerId: Long, channelId: Long, signalingJson: String): String? {
        if (peerId == 0L || channelId == 0L) return null
        return try {
            val parsed = parseSignalingData(signalingJson)
            val sdpString = SdpCompressor.sdpPlainTextFromNegotiationJson(parsed) ?: return null
            "${peerId}_${channelId}_${sdpString.hashCode()}"
        } catch (_: Exception) {
            null
        }
    }

    private fun shouldSuppressDuplicateRejectedOffer(
        peerId: Long,
        channelId: Long,
        signalingJson: String
    ): Boolean {
        val stored = suppressDuplicateOfferFingerprint ?: return false
        val now = SystemClock.elapsedRealtime()
        if (now >= suppressDuplicateOfferUntilElapsed) {
            suppressDuplicateOfferFingerprint = null
            return false
        }
        val incoming = signalingOfferFingerprint(peerId, channelId, signalingJson) ?: return false
        return incoming == stored
    }

    private fun prepareIncomingPeerConnection(sdp: SessionDescription) {
        val runPrepare = Runnable {
            if (peerConnection != null) {
                Log.d(TAG, "prepareIncomingPeerConnection: already prepared, skip")
                return@Runnable
            }
            try {
                webRtcInfra.factory
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
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runPrepare.run()
        } else {
            incomingPrepareHandler.post(runPrepare)
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
                offerJsonPayload,
                callInfo.isVideo
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
        rejectCallFromIncomingCallUi(null)
    }

    fun rejectCallFromIncomingCallUi(rawOfferEnvelope: String?) {
        cancelTimeout()
        when (val state = callState) {
            is CallState.Incoming -> {
                armSuppressDuplicateRejectedOffer(sessionOfferFingerprint(state.callInfo.peerId, state.callInfo.channelId, state.offer))
                sendSignaling(state.callInfo.peerId, state.callInfo.channelId, WebrtcSignalingType.SDP_QUIT, "")
                endCall(CallEndReason.LOCAL_REJECT)
            }
            is CallState.Connecting, is CallState.Connected -> hangup()
            else -> {
                val env = rawOfferEnvelope?.trim()?.takeIf { it.isNotEmpty() }
                    ?: appContext.getSharedPreferences("call_data", Context.MODE_PRIVATE)
                        .getString("incoming_call", null)?.trim().orEmpty()
                val ids = peerAndChannelFromIncomingOfferEnvelope(env)
                if (ids != null) {
                    armSuppressDuplicateRejectedOffer(signalingOfferFingerprint(ids.first, ids.second, env))
                    sendSignaling(ids.first, ids.second, WebrtcSignalingType.SDP_QUIT, "")
                }
                endCall(CallEndReason.LOCAL_REJECT)
            }
        }
    }

    private fun peerAndChannelFromIncomingOfferEnvelope(raw: String): Pair<Long, Long>? {
        if (raw.isEmpty()) return null
        return try {
            val top = JSONObject(raw)
            val callerId = top.optString("callerId", "0").toLongOrNull()
                ?: if (top.has("callerId") && !top.isNull("callerId")) top.getLong("callerId") else null
                ?: return null
            val channelId = top.optString("channelId", "0").toLongOrNull()
                ?: if (top.has("channelId") && !top.isNull("channelId")) top.getLong("channelId") else null
                ?: return null
            if (callerId == 0L || channelId == 0L) return null
            Pair(callerId, channelId)
        } catch (_: Exception) {
            null
        }
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

    fun dismissIncomingCall() {
        if (callState !is CallState.Incoming) return
        cancelTimeout()
        StartupCache.suppressHomeListApiForIncomingCallWake = false
        try { CallForegroundService.stop(appContext) } catch (_: Exception) {}
        try {
            val notifier = CallNotificationManager(appContext)
            notifier.dismissIncomingNotification()
        } catch (_: Exception) {}
        callAudioManager?.stopTone()
        peerConnection?.dispose()
        peerConnection = null
        callState = CallState.Idle
        notificationCenter.postNotificationOnMainThread(NotificationCenter.callEnded, CallEndReason.CANCELLED, null)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, CallState.Idle)
        appScope.launch {
            callAudioManager?.stop()
            callAudioManager = null
        }
    }

    fun dismissOutgoingCallSilently() {
        if (callState !is CallState.Outgoing) return
        Log.i(TAG, "dismissOutgoingCallSilently: call connected elsewhere")
        cancelTimeout()
        StartupCache.suppressHomeListApiForIncomingCallWake = false
        try { CallForegroundService.stop(appContext) } catch (_: Exception) {}
        try {
            val notifier = CallNotificationManager(appContext)
            notifier.dismissIncomingNotification()
            notifier.dismissOngoingNotification()
        } catch (_: Exception) {}
        callAudioManager?.stopTone()
        peerConnection?.dispose()
        peerConnection = null
        callState = CallState.Idle
        notificationCenter.postNotificationOnMainThread(NotificationCenter.callEnded, CallEndReason.CANCELLED, null)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, CallState.Idle)
        appScope.launch {
            callAudioManager?.stop()
            callAudioManager = null
        }
    }

    fun endCall(reason: CallEndReason) {
        cancelTimeout()
        cancelConnectingPhaseTimeout()
        cancelRemoteVideoRevealRefresh()

        val snapState = callState
        val wasConnected = snapState is CallState.Connected
        val durationMs = when (snapState) {
            is CallState.Connected -> SystemClock.elapsedRealtime() - snapState.connectedTime
            else -> 0L
        }
        val snapInfo = when (snapState) {
            is CallState.Outgoing -> snapState.callInfo
            is CallState.Incoming -> snapState.callInfo
            is CallState.Connecting -> snapState.callInfo
            is CallState.Connected -> snapState.callInfo
            is CallState.Idle -> null
        }
        if (snapState is CallState.Outgoing && snapInfo != null) {
            pushCancelCallToCallee(snapInfo)
        }
        val logMessageId = activeCallLogMessageId
        val logClan = activeCallClanId
        val logChType = activeCallChannelType
        val logPrivate = activeCallIsPrivate

        StartupCache.suppressHomeListApiForIncomingCallWake = false

        try {
            CallForegroundService.stop(appContext)
        } catch (_: Exception) {
        }
        try {
            val notifier = CallNotificationManager(appContext)
            notifier.dismissIncomingNotification()
            notifier.dismissOngoingNotification()
        } catch (_: Exception) {
        }

        callAudioManager?.stopTone()
        when (reason) {
            CallEndReason.BUSY -> callAudioManager?.playBusyTone()
            CallEndReason.LOCAL_HANGUP, CallEndReason.REMOTE_HANGUP -> callAudioManager?.playEndCallTone()
            else -> {}
        }

        val prevInfo = snapInfo

        peerConnection?.dispose()
        peerConnection = null
        remoteVideoTrack = null
        remoteAudioTrack = null

        callState = CallState.Idle
        isLocalAudioEnabled = true
        isLocalVideoEnabled = false
        isRemoteAudioEnabled = true
        isRemoteVideoEnabled = false
        remoteCameraEverSignaled = false
        isSpeakerOn = false
        localOffer = null
        synchronized(pendingIceCandidates) { pendingIceCandidates.clear() }
        resetHandshakeDebugCounters()

        activeCallLogMessageId = 0L

        if (prevInfo?.isInitiator == true && logMessageId != 0L) {
            callLogHelper.updateAfterCallEnd(
                socket = socket,
                scope = appScope,
                channelId = prevInfo.channelId,
                clanId = logClan,
                channelType = logChType,
                isChannelPrivate = logPrivate,
                messageId = logMessageId,
                reason = reason,
                wasConnected = wasConnected,
                durationMs = durationMs,
                isVideo = prevInfo.isVideo
            )
        }

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
        Log.d(TAG, "handleOffer: received from caller=$callerId channel=$channelId jsonLen=${jsonData.length} currentState=${callState::class.simpleName} socketState=${socket.connectionState.value}")
        if (callState is CallState.Connected) {
            handleRenegotiationOffer(callerId, channelId, jsonData)
            return
        }
        if (callState !is CallState.Idle) {
            val current = callState
            if (current is CallState.Incoming &&
                current.callInfo.peerId == callerId &&
                current.callInfo.channelId == channelId
            ) {
                Log.d(TAG, "handleOffer: duplicate offer from same caller (FCM+WS race), ignoring")
                return
            }
            Log.d(TAG, "handleOffer: busy, current state=${callState::class.simpleName}")
            sendSignaling(callerId, channelId, WebrtcSignalingType.SDP_JOINED_OTHER_CALL, "")
            return
        }
        if (shouldSuppressDuplicateRejectedOffer(callerId, channelId, jsonData)) {
            Log.d(TAG, "handleOffer: suppressed duplicate of rejected offer")
            return
        }

        try {
            val parsed = parseSignalingData(jsonData)
            val callerName = parsed.optString("callerName", "Unknown")
            val callerAvatar = parsed.optString("callerAvatar", "")

            val sdpString = SdpCompressor.sdpPlainTextFromNegotiationJson(parsed)
            if (sdpString.isNullOrEmpty()) {
                Log.w(TAG, "handleOffer: could not resolve SDP from jsonData (parsed keys=${parsed.keys().asSequence().toList()})")
                return
            }
            val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
            val isVideo = resolveIsVideoFromOfferPayload(parsed, sdpString)

            Log.d(TAG, "[WEBRTC:OFFER_REMOTE] caller=$callerId ch=$channelId name=$callerName video=$isVideo ${sdpPreviewForLog(sdpString)}")
            Log.d(TAG, "handleOffer: parsed caller=$callerName, video=$isVideo, sdpLen=${sdpString.length}, hasV0=${sdpString.contains("v=0")}, hasMaudio=${sdpString.contains("m=audio")}, hasMvideo=${sdpString.contains("m=video")}")

            val callInfo = CallInfo(
                peerId = callerId,
                peerName = callerName,
                peerAvatar = callerAvatar.ifEmpty { null },
                channelId = channelId,
                isVideo = isVideo,
                isInitiator = false
            )

            activeCallLogMessageId = 0L

            resetHandshakeDebugCounters()
            Log.d(TAG, "[WEBRTC:HANDSHAKE] offer via socket → Incoming + PC prepare")
            callState = CallState.Incoming(callInfo, sdp)
            synchronized(pendingIceCandidates) { pendingIceCandidates.clear() }
            prepareIncomingPeerConnection(sdp)
            callAudioManager = CallAudioManager(appContext).also {
                it.startForIncomingRing()
                it.playRingtone()
            }

            startTimeout()
            notificationCenter.postNotificationOnMainThread(NotificationCenter.incomingCall, callInfo)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, callState)
            try {
                appContext.getSharedPreferences("call_data", Context.MODE_PRIVATE).edit()
                    .putString("incoming_call", jsonData)
                    .commit()
            } catch (_: Exception) {}
            if (!MainActivity.isResumed) {
                StartupCache.suppressHomeListApiForIncomingCallWake = true
            }
            tryPresentIncomingCallBackgroundUi(callInfo, jsonData)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle offer", e)
        }
    }

    private fun handleAnswer(jsonData: String) {
        val state = callState
        Log.d(TAG, "handleAnswer: received jsonLen=${jsonData.length} currentState=${state::class.simpleName} socketState=${socket.connectionState.value}")
        if (state !is CallState.Outgoing && state !is CallState.Connected) {
            Log.w(TAG, "handleAnswer: unexpected state, current=${callState::class.simpleName}")
            return
        }

        if (state is CallState.Outgoing) {
            cancelTimeout()
            callAudioManager?.stopTone()
        }

        try {
            val parsed = parseSignalingData(jsonData)
            val sdpString = SdpCompressor.sdpPlainTextFromNegotiationJson(parsed)
            if (sdpString.isNullOrEmpty()) {
                Log.e(TAG, "handleAnswer: could not resolve SDP from parsed=${parsed.keys().asSequence().toList()}")
                endCall(CallEndReason.ERROR)
                return
            }
            val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpString)

            Log.d(TAG, "[WEBRTC:HANDSHAKE] caller will apply remote ANSWER (handleRemoteAnswer)")
            Log.d(TAG, "[WEBRTC:ANSWER_REMOTE] state=${state::class.simpleName} ${sdpPreviewForLog(sdpString)} pc=${peerConnection != null}")
            Log.d(TAG, "handleAnswer: applying remote answer sdpLen=${sdpString.length} pcExists=${peerConnection != null}")
            peerConnection?.handleRemoteAnswer(sdp)
            if (state is CallState.Outgoing) {
                callState = CallState.Connecting(state.callInfo)
                startConnectingPhaseTimeout()
                Log.d(TAG, "handleAnswer: transitioned to Connecting, awaiting ICE")
                notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, callState)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle answer", e)
            endCall(CallEndReason.ERROR)
        }
    }

    private fun handleIceCandidate(jsonData: String) {
        try {
            handshakeIceInWireSeq++
            val wireN = handshakeIceInWireSeq
            val json = JSONObject(jsonData)
            val candidate = IceCandidate(
                json.getString("sdpMid"),
                json.getInt("sdpMLineIndex"),
                json.getString("candidate")
            )
            val kind = iceCandidateKindForLog(candidate.sdp)
            val pc = peerConnection
            if (pc != null) {
                Log.d(TAG, "[WEBRTC:ICE_IN] #$wireN kind=$kind apply state=${callState::class.simpleName} ${iceCandidatePreviewForLog(candidate)}")
                pc.addRemoteIceCandidate(candidate)
            } else {
                synchronized(pendingIceCandidates) {
                    pendingIceCandidates.add(candidate)
                }
                Log.d(TAG, "[WEBRTC:ICE_IN] #$wireN kind=$kind queued depth=${pendingIceCandidates.size} state=${callState::class.simpleName} ${iceCandidatePreviewForLog(candidate)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ICE candidate", e)
        }
    }

    private fun flushPendingIceCandidates() {
        val pc = peerConnection ?: return
        synchronized(pendingIceCandidates) {
            if (pendingIceCandidates.isNotEmpty()) {
                val n = pendingIceCandidates.size
                Log.d(TAG, "[WEBRTC:HANDSHAKE] flush ${n} ICE from signaling (no PC yet) → PeerConnectionWrapper")
                var i = 0
                for (candidate in pendingIceCandidates) {
                    i++
                    Log.d(TAG, "[WEBRTC:ICE_IN] flush[$i/$n] kind=${iceCandidateKindForLog(candidate.sdp)} ${iceCandidatePreviewForLog(candidate)}")
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
                remoteCameraEverSignaled = true
                isRemoteVideoEnabled = json.getBoolean("cameraEnabled")
            }
            notificationCenter.postNotificationOnMainThread(NotificationCenter.callMediaChanged)
        } catch (_: Exception) {}
    }

    private fun handleClearCall() {
        if (callState is CallState.Incoming) {
            dismissIncomingCall()
        } else {
            endCall(CallEndReason.CLEAR_CALL)
        }
    }

    private fun handleSdpInit() {
        val state = callState
        if (state is CallState.Incoming) {
            Log.i(TAG, "handleSdpInit: caller connected elsewhere while Incoming → dismissIncomingCall")
            dismissIncomingCall()
            return
        }
        if (state is CallState.Connecting) {
            Log.d(TAG, "[WEBRTC:HANDSHAKE] SDP_INIT recv → Connected (peer signaled media ready)")
            val connectedTime = SystemClock.elapsedRealtime()
            callState = CallState.Connected(state.callInfo, connectedTime)
            cancelConnectingPhaseTimeout()
            callAudioManager?.stopTone()
            sendMediaStatus()
            scheduleRemoteVideoRevealRefreshIfNeeded()
            notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, callState)
            try {
                CallForegroundService.startConnected(appContext, state.callInfo.peerName, connectedTime)
            } catch (_: Exception) {
            }
        }
    }

    private fun cancelRemoteVideoRevealRefresh() {
        remoteVideoRevealRunnable?.let { incomingPrepareHandler.removeCallbacks(it) }
        remoteVideoRevealRunnable = null
    }

    private fun scheduleRemoteVideoRevealRefreshIfNeeded() {
        cancelRemoteVideoRevealRefresh()
        val state = callState as? CallState.Connected ?: return
        if (!state.callInfo.isVideo) return
        val elapsed = SystemClock.elapsedRealtime() - state.connectedTime
        val delayMs = (REMOTE_VIDEO_INITIAL_SUPPRESS_MS - elapsed).coerceAtLeast(0L)
        val r = Runnable {
            remoteVideoRevealRunnable = null
            val s = callState as? CallState.Connected ?: return@Runnable
            if (!s.callInfo.isVideo) return@Runnable
            notificationCenter.postNotificationOnMainThread(NotificationCenter.callMediaChanged)
        }
        remoteVideoRevealRunnable = r
        incomingPrepareHandler.postDelayed(r, delayMs)
    }

    override fun onLocalIceCandidate(candidate: IceCandidate) {
        val callInfo = currentCallInfo() ?: return
        val json = JSONObject().apply {
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        }.toString()
        handshakeIceOutSeq++
        val seq = handshakeIceOutSeq
        Log.d(
            TAG,
            "[WEBRTC:ICE_OUT] #$seq kind=${iceCandidateKindForLog(candidate.sdp)} socket=${socket.connectionState.value} state=${callState::class.simpleName} jsonLen=${json.length} ${iceCandidatePreviewForLog(candidate)}"
        )

        sendSignaling(callInfo.peerId, callInfo.channelId, WebrtcSignalingType.ICE_CANDIDATE, json)
    }

    override fun onIceConnected() {
        val state = callState
        Log.d(TAG, "[WEBRTC:HANDSHAKE] onIceConnected state=${state::class.simpleName}")
        if (state is CallState.Connecting || state is CallState.Outgoing) {
            val callInfo = currentCallInfo() ?: return
            val connectedTime = SystemClock.elapsedRealtime()
            callState = CallState.Connected(callInfo, connectedTime)
            cancelConnectingPhaseTimeout()
            cancelTimeout()
            callAudioManager?.stopTone()
            MezonCallConnection.activeConnection?.setCallActive()

            sendSignaling(callInfo.peerId, callInfo.channelId, WebrtcSignalingType.SDP_INIT, "")
            sendMediaStatus()
            scheduleRemoteVideoRevealRefreshIfNeeded()
            notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, callState)
            try {
                CallForegroundService.startConnected(appContext, callInfo.peerName, connectedTime)
            } catch (_: Exception) {
            }
        }
    }

    override fun onIceDisconnected() {
        Log.w(TAG, "[WEBRTC:HANDSHAKE] onIceDisconnected was=${callState::class.simpleName} socket=${socket.connectionState.value}")
        endCall(CallEndReason.ICE_FAILED)
    }

    override fun onIceFailed() {
        Log.e(TAG, "[WEBRTC:HANDSHAKE] onIceFailed was=${callState::class.simpleName} socket=${socket.connectionState.value}")
        endCall(CallEndReason.ICE_FAILED)
    }

    override fun onRemoteVideoTrack(track: VideoTrack) {
        remoteVideoTrack = track
        notificationCenter.postNotificationOnMainThread(NotificationCenter.callMediaChanged)
    }

    override fun onRemoteAudioTrack(track: AudioTrack) {
        remoteAudioTrack = track
    }

    override fun onInboundSignalingSetupFailed(error: String?) {
        Log.e(TAG, "onInboundSignalingSetupFailed: $error state=${callState::class.simpleName}")
        when (callState) {
            is CallState.Incoming -> {
                peerConnection?.dispose()
                peerConnection = null
                synchronized(pendingIceCandidates) { pendingIceCandidates.clear() }
            }
            is CallState.Connecting -> endCall(CallEndReason.ERROR)
            else -> Unit
        }
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

    private fun startConnectingPhaseTimeout() {
        connectingPhaseTimeoutJob?.cancel()
        connectingPhaseTimeoutJob = appScope.launch(Dispatchers.Main) {
            delay(CONNECTING_PHASE_TIMEOUT_MS)
            if (callState is CallState.Connecting) {
                Log.w(TAG, "Connecting phase exceeded ${CONNECTING_PHASE_TIMEOUT_MS}ms, ending call")
                endCall(CallEndReason.TIMEOUT)
            }
        }
    }

    private fun cancelConnectingPhaseTimeout() {
        connectingPhaseTimeoutJob?.cancel()
        connectingPhaseTimeoutJob = null
    }

    private fun pushCancelCallToCallee(callInfo: CallInfo) {
        val callerName = userController.displayName.ifEmpty { userController.username }
        val callerAvatar = userController.avatarUrl.orEmpty()
        val fingerprint = localOffer?.description?.hashCode()?.toString().orEmpty()
        val fcmPayload = JSONObject().apply {
            put("offer", "CANCEL_CALL")
            put("isVideo", callInfo.isVideo)
            put("callerName", callerName)
            put("callerAvatar", callerAvatar)
            put("callerId", userController.userIdStr)
            put("channelId", callInfo.channelId.toString())
            if (fingerprint.isNotEmpty()) put("offerFingerprint", fingerprint)
        }.toString()
        appScope.launch(ioDispatcher) {
            sendWithRetry("pushCancelCallToCallee") {
                socket.makeCallPush(
                    receiverId = callInfo.peerId,
                    jsonData = fcmPayload,
                    channelId = callInfo.channelId,
                    callerId = userController.userId
                )
            }
        }
    }

    private fun sendSignaling(peerId: Long, channelId: Long, dataType: Int, jsonData: String) {
        appScope.launch(ioDispatcher) {
            sendWithRetry("sendSignaling type=$dataType") {
                socket.forwardWebrtcSignaling(
                    receiverId = peerId,
                    dataType = dataType,
                    jsonData = jsonData,
                    channelId = channelId,
                    callerId = userController.userId
                )
            }
        }
    }

    private suspend fun sendWithRetry(
        label: String,
        maxAttempts: Int = 3,
        awaitTimeoutMs: Long = 10_000L,
        block: suspend () -> Unit
    ): Boolean {
        if (socket.connectionState.value != ConnectionState.CONNECTED) {
            Log.d(TAG, "$label: socket not connected, awaiting up to ${awaitTimeoutMs}ms")
            val connected = socket.awaitConnected(awaitTimeoutMs)
            if (!connected) {
                Log.e(TAG, "$label: socket connect timeout, giving up")
                return false
            }
        }
        repeat(maxAttempts) { attempt ->
            try {
                block()
                if (attempt > 0) Log.d(TAG, "$label: succeeded on attempt ${attempt + 1}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "$label attempt ${attempt + 1}/$maxAttempts failed: ${e.message}")
                if (attempt < maxAttempts - 1) {
                    val reconnected = socket.awaitConnected(awaitTimeoutMs)
                    if (!reconnected) {
                        Log.e(TAG, "$label: reconnect timeout between attempts, giving up")
                        return false
                    }
                } else {
                    Log.e(TAG, "$label: exhausted $maxAttempts attempts", e)
                }
            }
        }
        return false
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
            Log.d(TAG, "sendRenegotiationOffer: payload offerJson=${offerJson.length} compressed=${compressedPayload.length}")
            appScope.launch(ioDispatcher) {
                sendWithRetry("sendRenegotiationOffer") {
                    socket.forwardWebrtcSignaling(
                        receiverId = callInfo.peerId,
                        dataType = WebrtcSignalingType.SDP_OFFER,
                        jsonData = compressedPayload,
                        channelId = callInfo.channelId,
                        callerId = userController.userId
                    )
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
            val sdpString = SdpCompressor.sdpPlainTextFromNegotiationJson(parsed)
            if (sdpString.isNullOrEmpty()) {
                Log.w(TAG, "handleRenegotiationOffer: empty sdp")
                return
            }
            val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
            peerConnection?.handleRenegotiationRemoteOffer(sdp) { answer ->
                val answerJson = JSONObject().apply {
                    put("sdp", answer.description)
                    put("type", "answer")
                }.toString()
                val compressedPayload = SdpCompressor.compress(answerJson)
                Log.d(TAG, "handleRenegotiationOffer: answer payload answerJson=${answerJson.length} compressed=${compressedPayload.length}")
                appScope.launch(ioDispatcher) {
                    sendWithRetry("handleRenegotiationOffer:SDP_ANSWER") {
                        socket.forwardWebrtcSignaling(
                            receiverId = info.peerId,
                            dataType = WebrtcSignalingType.SDP_ANSWER,
                            jsonData = compressedPayload,
                            channelId = info.channelId,
                            callerId = userController.userId
                        )
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

    private fun resolveIsVideoFromOfferPayload(parsed: JSONObject, sdpString: String): Boolean =
        when {
            parsed.has("isVideoCall") -> parsed.getBoolean("isVideoCall")
            parsed.has("is_video_call") -> parsed.getBoolean("is_video_call")
            parsed.has("isVideo") -> parsed.getBoolean("isVideo")
            else -> sdpString.contains("m=video")
        }

    companion object {
        @Volatile
        var instance: CallController? = null
            private set
    }
}
