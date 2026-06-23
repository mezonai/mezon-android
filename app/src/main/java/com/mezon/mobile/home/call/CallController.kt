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
private const val REMOTE_VIDEO_INITIAL_SUPPRESS_MS = 2000L

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

    @Volatile private var isInCall = false
    @Volatile private var inCallPeerId: Long = 0L
    @Volatile private var inCallChannelId: Long = 0L

    fun isCallSessionActive(): Boolean = isInCall || callState !is CallState.Idle

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
    private var localOffer: SessionDescription? = null
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    private val incomingPrepareHandler = Handler(Looper.getMainLooper())

    @Volatile private var suppressDuplicateOfferFingerprint: String? = null
    @Volatile private var suppressDuplicateOfferUntilElapsed: Long = 0L
    private var remoteVideoRevealRunnable: Runnable? = null
    @Volatile private var remoteCameraEverSignaled: Boolean = false
    @Volatile private var connectedCancelCallPushed = false

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

    fun startCall(
        peerId: Long,
        peerName: String,
        peerAvatar: String?,
        channelId: Long,
        clanId: Long,
        channelType: Int,
        isChannelPrivate: Boolean,
        isVideo: Boolean,
        peerUsername: String = ""
    ) {
        if (isCallSessionActive()) {
            Log.w(TAG, "Cannot start call: already in call state ${callState::class.simpleName}")
            return
        }

        activeCallClanId = clanId
        activeCallChannelType = channelType
        activeCallIsPrivate = isChannelPrivate
        activeCallLogMessageId = 0L
        connectedCancelCallPushed = false

        val callInfo = CallInfo(
            peerId = peerId,
            peerName = peerName,
            peerUsername = peerUsername,
            peerAvatar = peerAvatar,
            channelId = channelId,
            isVideo = isVideo,
            isInitiator = true
        )
        val outgoingStartedAt = SystemClock.elapsedRealtime()
        markInCall(callInfo)
        callState = CallState.Outgoing(callInfo, outgoingStartedAt)
        Log.d(TAG, "startCall: marked in-call peer=$peerId channel=$channelId")

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

                    val callerName = userController.displayName.ifEmpty { userController.username }
                    val callerAvatar = userController.avatarUrl
                    val offerJson = JSONObject().apply {
                        put("sdp", offer.description)
                        put("type", "offer")
                        put("isVideo", isVideo)
                        put("callerName", callerName)
                        put("callerAvatar", callerAvatar)
                        put("sentAt", System.currentTimeMillis().toString())
                    }.toString()
                    val compressedPayload = SdpCompressor.compress(offerJson)

                    val fcmPayload = JSONObject().apply {
                        put("offer", compressedPayload)
                        put("isVideo", isVideo)
                        put("callerName", callerName)
                        put("callerAvatar", callerAvatar)
                        put("callerId", userController.userIdStr)
                        put("channelId", channelId.toString())
                        put("sentAt", System.currentTimeMillis().toString())
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
        markInCall(callInfo)
        isLocalAudioEnabled = true
        isLocalVideoEnabled = false
        isSpeakerOn = callInfo.isVideo

        if (callAudioManager == null) {
            callAudioManager = CallAudioManager(appContext).also { it.startForIncomingRing() }
        }
        callAudioManager!!.advanceToEstablishedCallRouting(callInfo.isVideo)

        callState = CallState.Connecting(callInfo)
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
            Log.d(TAG, "acceptCall: peerConnection already prepared, answer when remote SDP applied")
            pc.requestAnswerWhenRemoteReady { answer -> sendAnswer(callInfo, answer) }
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
                peerUsername = parsed.optString("callerUsername", "").ifEmpty {
                    parsed.optString("username", "")
                },
                peerAvatar = callerAvatar.ifEmpty { null },
                channelId = channelIdStr.toLongOrNull() ?: 0L,
                isVideo = isVideo,
                isInitiator = false
            )

            Log.d(TAG, "acceptCallFromFcm: caller=$callerName, sdpLen=${sdpString.length}")
            markInCall(callInfo)
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
        val peerIdLong = callerId.toLongOrNull() ?: 0L
        val channelIdLong = channelId.toLongOrNull() ?: 0L
        if (isDuplicateIncomingOffer(peerIdLong, channelIdLong)) {
            Log.d(TAG, "handleIncomingOfferFromFcm: duplicate offer from same caller, ignoring")
            return
        }
        if (callState !is CallState.Idle && isOfferForCurrentCall(peerIdLong, channelIdLong)) {
            Log.d(TAG, "handleIncomingOfferFromFcm: offer belongs to current call, ignoring")
            return
        }
        if (shouldReplyBusyToIncomingOffer(peerIdLong, channelIdLong)) {
            Log.d(TAG, "handleIncomingOfferFromFcm: busy, state=${callState::class.simpleName}, inCallPeer=$inCallPeerId")
            sendBusyToCaller(peerIdLong, channelIdLong)
            return
        }

        try {
            val parsed = parseSignalingData(offerJson)
            val sdpString = SdpCompressor.sdpPlainTextFromNegotiationJson(parsed)
            if (sdpString.isNullOrEmpty()) {
                Log.w(TAG, "handleIncomingOfferFromFcm: no SDP found in offer")
                return
            }

            val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
            val isVideo = resolveIsVideoFromOfferPayload(parsed, sdpString)

            if (shouldSuppressDuplicateRejectedOffer(peerIdLong, channelIdLong, offerJson)) {
                Log.d(TAG, "handleIncomingOfferFromFcm: suppressed duplicate of rejected offer")
                return
            }

            Log.d(TAG, "handleIncomingOfferFromFcm: caller=$callerName, video=$isVideo, sdpLen=${sdpString.length}")

            val callInfo = CallInfo(
                peerId = peerIdLong,
                peerName = callerName,
                peerUsername = parsed.optString("callerUsername", "").ifEmpty {
                    parsed.optString("username", "")
                },
                peerAvatar = callerAvatar.ifEmpty { null },
                channelId = channelIdLong,
                isVideo = isVideo,
                isInitiator = false
            )

            markInCall(callInfo)
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
                if (callState is CallState.Incoming) {
                    endCall(CallEndReason.ERROR)
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runPrepare.run()
        } else {
            incomingPrepareHandler.post(runPrepare)
        }
    }

    private fun isAppInForegroundForIncomingCall(): Boolean =
        MainActivity.isResumed && !callManager.isDeviceLockedOrScreenOff()

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

    fun endCall(reason: CallEndReason) {
        cancelTimeout()
        cancelRemoteVideoRevealRefresh()

        val snapState = callState
        if (reason == CallEndReason.CANCELLED || reason == CallEndReason.CLEAR_CALL) {
            Log.d(TAG, "endCall: reason=$reason snapState=${snapState::class.simpleName}")
        }
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
        connectedCancelCallPushed = false

        peerConnection?.dispose()
        peerConnection = null
        remoteVideoTrack = null
        remoteAudioTrack = null

        callState = CallState.Idle
        clearInCallMarker()
        isLocalAudioEnabled = true
        isLocalVideoEnabled = false
        isRemoteAudioEnabled = true
        isRemoteVideoEnabled = false
        remoteCameraEverSignaled = false
        isSpeakerOn = false
        localOffer = null
        synchronized(pendingIceCandidates) { pendingIceCandidates.clear() }

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
        val currentUserId = userController.userId
        if (receiverId != 0L && currentUserId != 0L && receiverId != currentUserId) {
            Log.d(
                TAG,
                "handleSignaling: ignore type=$dataType caller=$callerId receiver=$receiverId channel=$channelId currentUser=$currentUserId"
            )
            return
        }
        Log.d(
            TAG,
            "handleSignaling: type=$dataType caller=$callerId receiver=$receiverId channel=$channelId state=${callState::class.simpleName}"
        )
        when (dataType) {
            WebrtcSignalingType.SDP_OFFER -> handleOffer(callerId, channelId, jsonData)
            WebrtcSignalingType.SDP_ANSWER -> handleAnswer(callerId, channelId, jsonData)
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
        val connectedState = callState as? CallState.Connected
        if (connectedState != null) {
            val currentCall = connectedState.callInfo
            if (currentCall.peerId == callerId && currentCall.channelId == channelId) {
                handleRenegotiationOffer(callerId, channelId, jsonData)
            } else {
                Log.d(TAG, "handleOffer: busy while connected, currentPeer=${currentCall.peerId}, incomingPeer=$callerId")
                sendSignaling(callerId, channelId, WebrtcSignalingType.SDP_JOINED_OTHER_CALL, "")
            }
            return
        }
        if (isDuplicateIncomingOffer(callerId, channelId)) {
            Log.d(TAG, "handleOffer: duplicate offer from same caller, ignoring")
            return
        }
        if (callState !is CallState.Idle && isOfferForCurrentCall(callerId, channelId)) {
            Log.d(TAG, "handleOffer: offer belongs to current call, ignoring")
            return
        }
        if (shouldReplyBusyToIncomingOffer(callerId, channelId)) {
            Log.d(TAG, "handleOffer: busy, current state=${callState::class.simpleName}, inCallPeer=$inCallPeerId")
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
                Log.w(TAG, "handleOffer: could not resolve SDP")
                return
            }
            val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
            val isVideo = resolveIsVideoFromOfferPayload(parsed, sdpString)

            Log.d(TAG, "handleOffer: caller=$callerName, video=$isVideo, sdpLen=${sdpString.length}")

            val callInfo = CallInfo(
                peerId = callerId,
                peerName = callerName,
                peerUsername = parsed.optString("callerUsername", "").ifEmpty {
                    parsed.optString("username", "")
                },
                peerAvatar = callerAvatar.ifEmpty { null },
                channelId = channelId,
                isVideo = isVideo,
                isInitiator = false
            )

            activeCallLogMessageId = 0L

            markInCall(callInfo)
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

    private fun handleAnswer(callerId: Long, channelId: Long, jsonData: String) {
        val state = callState
        if (state !is CallState.Outgoing && state !is CallState.Connected) {
            Log.w(TAG, "handleAnswer: unexpected state, current=${callState::class.simpleName}")
            return
        }
        val callInfo = when (state) {
            is CallState.Outgoing -> state.callInfo
            is CallState.Connected -> state.callInfo
            else -> null
        } ?: return
        if (callInfo.peerId != callerId || callInfo.channelId != channelId) {
            Log.d(
                TAG,
                "handleAnswer: ignore mismatched answer caller=$callerId channel=$channelId expectedPeer=${callInfo.peerId} expectedChannel=${callInfo.channelId}"
            )
            return
        }

        Log.d(TAG, "handleAnswer: received answer")
        if (state is CallState.Outgoing) {
            callAudioManager?.stopTone()
        }

        try {
            val parsed = parseSignalingData(jsonData)
            val sdpString = SdpCompressor.sdpPlainTextFromNegotiationJson(parsed)
            if (sdpString.isNullOrEmpty()) {
                Log.e(TAG, "handleAnswer: could not resolve SDP")
                endCall(CallEndReason.ERROR)
                return
            }
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

    private fun markInCall(callInfo: CallInfo) {
        isInCall = true
        inCallPeerId = callInfo.peerId
        inCallChannelId = callInfo.channelId
    }

    private fun clearInCallMarker() {
        isInCall = false
        inCallPeerId = 0L
        inCallChannelId = 0L
    }

    private fun shouldReplyBusyToIncomingOffer(callerId: Long, channelId: Long): Boolean {
        val state = callState
        if (state is CallState.Connected) {
            val info = state.callInfo
            if (info.peerId == callerId && info.channelId == channelId) return false
        }
        if (isInCall) {
            if (inCallPeerId != 0L && inCallPeerId == callerId) return false
            return true
        }
        return state !is CallState.Idle
    }

    private fun isOfferForCurrentCall(callerId: Long, channelId: Long): Boolean {
        val current = currentCallInfo()
        if (current != null && current.peerId == callerId) {
            return channelId == 0L || current.channelId == 0L || current.channelId == channelId
        }
        if (!isInCall || inCallPeerId == 0L || inCallPeerId != callerId) return false
        return channelId == 0L || inCallChannelId == 0L || inCallChannelId == channelId
    }

    private fun isDuplicateIncomingOffer(callerId: Long, channelId: Long): Boolean {
        val current = callState as? CallState.Incoming ?: return false
        return current.callInfo.peerId == callerId && current.callInfo.channelId == channelId
    }

    private fun sendBusyToCaller(callerId: Long, channelId: Long) {
        if (callerId == 0L || channelId == 0L) return
        sendSignaling(callerId, channelId, WebrtcSignalingType.SDP_JOINED_OTHER_CALL, "")
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
        endCall(CallEndReason.CLEAR_CALL)
    }

    private fun handleSdpInit() {
        val state = callState
        if (state is CallState.Connecting) {
            transitionToConnected(state.callInfo)
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

        sendSignaling(callInfo.peerId, callInfo.channelId, WebrtcSignalingType.ICE_CANDIDATE, json)
    }

    override fun onIceConnected() {
        val state = callState
        if (state is CallState.Connecting || state is CallState.Outgoing) {
            val callInfo = currentCallInfo() ?: return
            transitionToConnected(callInfo)
            sendSignaling(callInfo.peerId, callInfo.channelId, WebrtcSignalingType.SDP_INIT, "")
        }
    }

    private fun transitionToConnected(callInfo: CallInfo) {
        if (callState is CallState.Connected) return
        val connectedTime = SystemClock.elapsedRealtime()
        callState = CallState.Connected(callInfo, connectedTime)
        cancelTimeout()
        callAudioManager?.stopTone()
        sendMediaStatus()
        scheduleRemoteVideoRevealRefreshIfNeeded()
        pushCancelCallOnConnected(callInfo)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.callStateChanged, callState)
        try {
            CallForegroundService.startConnected(appContext, callInfo.peerName, connectedTime)
        } catch (_: Exception) {
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

    override fun onInboundSignalingSetupFailed(error: String?) {
        Log.e(TAG, "onInboundSignalingSetupFailed: $error state=${callState::class.simpleName}")
        when (callState) {
            is CallState.Incoming -> endCall(CallEndReason.ERROR)
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

    fun shouldIgnoreCancelCallFcmAnsweredElsewhere(): Boolean {
        return when (callState) {
            is CallState.Connecting, is CallState.Connected -> true
            else -> false
        }
    }

    fun clearIdleIncomingArtifactsAfterAnsweredElsewhere() {
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
        try {
            appContext.getSharedPreferences("call_data", android.content.Context.MODE_PRIVATE)
                .edit().remove("incoming_call").apply()
        } catch (_: Exception) {
        }
    }

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

    private fun originalCallerId(callInfo: CallInfo): Long =
        if (callInfo.isInitiator) userController.userId else callInfo.peerId

    private fun buildCancelCallPayload(callInfo: CallInfo, isConnected: Boolean): String {
        val callerId = originalCallerId(callInfo)
        val callerName: String
        val callerAvatar: String
        if (callInfo.isInitiator) {
            callerName = userController.displayName.ifEmpty { userController.username }
            callerAvatar = userController.avatarUrl.orEmpty()
        } else {
            callerName = callInfo.peerName
            callerAvatar = callInfo.peerAvatar.orEmpty()
        }
        return JSONObject().apply {
            put("offer", "CANCEL_CALL")
            put("isConnected", isConnected)
            put("isVideo", callInfo.isVideo)
            put("callerName", callerName)
            put("callerAvatar", callerAvatar)
            put("callerId", callerId.toString())
            put("channelId", callInfo.channelId.toString())
            put("sentAt", System.currentTimeMillis().toString())
        }.toString()
    }

    private fun pushCancelCallFcm(callInfo: CallInfo, isConnected: Boolean, receiverId: Long) {
        if (receiverId == 0L) return
        val fcmPayload = buildCancelCallPayload(callInfo, isConnected)
        val protoCallerId = originalCallerId(callInfo)
        appScope.launch(ioDispatcher) {
            try {
                if (socket.connectionState.value != ConnectionState.CONNECTED) {
                    val connected = socket.awaitConnected(15_000L)
                    if (!connected) {
                        Log.w(TAG, "pushCancelCallFcm: socket not connected")
                        return@launch
                    }
                }
                socket.makeCallPush(
                    receiverId = receiverId,
                    jsonData = fcmPayload,
                    channelId = callInfo.channelId,
                    callerId = protoCallerId
                )
            } catch (e: Exception) {
                Log.e(TAG, "pushCancelCallFcm failed", e)
            }
        }
    }

    private fun pushCancelCallToCallee(callInfo: CallInfo) {
        pushCancelCallFcm(callInfo, isConnected = false, receiverId = callInfo.peerId)
    }

    private fun pushCancelCallOnConnected(callInfo: CallInfo) {
        if (connectedCancelCallPushed) return
        connectedCancelCallPushed = true
        val selfId = userController.userId
        if (selfId != 0L) {
            pushCancelCallFcm(callInfo, isConnected = true, receiverId = selfId)
        }
        if (callInfo.peerId != 0L && callInfo.peerId != selfId) {
            pushCancelCallFcm(callInfo, isConnected = true, receiverId = callInfo.peerId)
        }
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
