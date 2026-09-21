package com.mezon.mobile.home.stream

import android.content.Context
import android.os.Looper
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

private const val TAG = "StreamingWebRTC"
private const val MAX_RECONNECT_DELAY_MS = 15_000L
private const val ICE_DISCONNECTED_GRACE_MS = 3_000L
private const val OFFER_REISSUE_DEADLINE_MS = 8_000L

private data class SdpMediaSection(
    val kind: String,
    val mid: String,
    val direction: String?,
)

/**
 * SFU audience transport for streaming channels.
 *
 * The SFU sends the offer. Android answers the complete SDP layout while
 * keeping every video section inactive and receiving audio only. There is no
 * local media source, local sender or trickle ICE signaling in this path.
 */
@Singleton
class StreamingWebRtcSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webRtcInfra: WebRtcInfra,
    private val okHttpClient: OkHttpClient,
    @ApplicationScope private val appScope: CoroutineScope,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) {
    @Volatile var activeStreamChannelId: Long? = null
        private set
    @Volatile var isStreaming = false
        private set
    @Volatile var remoteAudioTrack: AudioTrack? = null
        private set

    @Volatile var onStreamingStateChanged: (() -> Unit)? = null

    private val audioRouting by lazy { StreamingAudioManager(context) }

    /* The app client may have a logging interceptor. Do not reuse it for a
       WebSocket whose URL and join message contain bearer tokens. */
    private val streamingHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder().apply {
            interceptors().clear()
            networkInterceptors().clear()
        }.build()
    }

    private var webSocket: WebSocket? = null
    private var peerConnection: PeerConnection? = null
    private var activeToken = ""
    private var tokenProvider: (suspend () -> String)? = null
    private var reconnectJob: Job? = null
    private var iceDisconnectedJob: Job? = null
    private var offerReissueJob: Job? = null
    private var reconnectAttempts = 0
    private var sessionGeneration = 0L
    private var transportGeneration = 0L
    private var negotiating = false
    private var pendingOffer: Pair<Long, String>? = null

    fun join(
        channelId: Long,
        token: String,
        tokenProvider: suspend () -> String,
    ) {
        runOnMain { joinOnMain(channelId, token, tokenProvider) }
    }

    fun disconnect() {
        runOnMain { disconnectOnMain() }
    }

    private fun joinOnMain(
        channelId: Long,
        token: String,
        provider: suspend () -> String,
    ) {
        checkOnMainThread()
        if (channelId == 0L || token.isBlank()) {
            log("join ignored: missing channel or token")
            return
        }
        if (activeStreamChannelId == channelId && (webSocket != null || peerConnection != null)) {
            log("join skipped: transport already exists channelId=$channelId")
            return
        }

        disconnectOnMain()
        activeStreamChannelId = channelId
        activeToken = token
        tokenProvider = provider
        reconnectAttempts = 0
        audioRouting.start()
        webRtcInfra.ensureFactoryReady()
        openTransportOnMain()
    }

    private fun disconnectOnMain() {
        checkOnMainThread()
        sessionGeneration++
        reconnectJob?.cancel()
        reconnectJob = null
        iceDisconnectedJob?.cancel()
        iceDisconnectedJob = null
        offerReissueJob?.cancel()
        offerReissueJob = null
        closeTransportOnMain()
        activeStreamChannelId = null
        activeToken = ""
        tokenProvider = null
        pendingOffer = null
        negotiating = false
        reconnectAttempts = 0
        remoteAudioTrack = null
        audioRouting.stop()
        setStreaming(false)
    }

    private fun openTransportOnMain() {
        checkOnMainThread()
        val channelId = activeStreamChannelId ?: return
        val token = activeToken
        if (token.isBlank()) {
            scheduleReconnectOnMain("missing token")
            return
        }
        val wsUrl = buildWebSocketUrl(token)
        if (wsUrl.isEmpty()) {
            log("join failed: invalid MEZON_SFU_WS_URL")
            scheduleReconnectOnMain("invalid websocket url")
            return
        }

        closeTransportOnMain()
        val sessionGen = sessionGeneration
        val transportGen = transportGeneration
        pendingOffer = null
        negotiating = false

        val pc = createPeerConnection(transportGen) ?: run {
            scheduleReconnectOnMain("peer connection creation failed")
            return
        }
        peerConnection = pc
        val request = Request.Builder().url(wsUrl).build()
        webSocket = streamingHttpClient.newWebSocket(
            request,
            StreamWebSocketListener(sessionGen, transportGen, channelId),
        )
    }

    private fun closeTransportOnMain() {
        checkOnMainThread()
        transportGeneration++
        offerReissueJob?.cancel()
        offerReissueJob = null
        val socket = webSocket
        webSocket = null
        runCatching { socket?.close(1000, "reconnect") }

        val pc = peerConnection
        peerConnection = null
        runCatching {
            pc?.close()
            pc?.dispose()
        }
        pendingOffer = null
        negotiating = false
        remoteAudioTrack = null
        setStreaming(false)
    }

    private fun createPeerConnection(transportGen: Long): PeerConnection? {
        /* SFU is ICE-lite. Keep this identical to the web audience path. */
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        return runCatching {
            webRtcInfra.factory.createPeerConnection(config, createPeerObserver(transportGen))
        }.onFailure { error ->
            log("peer connection creation failed: ${error.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun createPeerObserver(transportGen: Long) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            runOnMain {
                if (!isCurrentTransport(transportGen)) return@runOnMain
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        iceDisconnectedJob?.cancel()
                        iceDisconnectedJob = null
                        reconnectAttempts = 0
                        setStreaming(true)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        iceDisconnectedJob?.cancel()
                        val sessionGen = sessionGeneration
                        iceDisconnectedJob = appScope.launch(mainDispatcher) {
                            delay(ICE_DISCONNECTED_GRACE_MS)
                            if (sessionGen == sessionGeneration && isCurrentTransport(transportGen)) {
                                scheduleReconnectOnMain("ice disconnected")
                            }
                        }
                    }
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        scheduleReconnectOnMain("ice state $state")
                    }
                    else -> Unit
                }
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(candidate: org.webrtc.IceCandidate?) {
            /* Trickle ICE is not part of this SFU signaling contract. */
        }
        override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>?) {}

        override fun onAddStream(stream: MediaStream?) {
            stream ?: return
            runOnMain {
                if (!isCurrentTransport(transportGen)) return@runOnMain
                stream.audioTracks.forEach { handleRemoteTrack(it) }
                stream.videoTracks.forEach { ignoreUnexpectedVideoTrack(it) }
            }
        }

        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
        override fun onRenegotiationNeeded() {}

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track() ?: return
            runOnMain {
                if (!isCurrentTransport(transportGen)) return@runOnMain
                handleRemoteTrack(track)
            }
        }
    }

    private inner class StreamWebSocketListener(
        private val sessionGen: Long,
        private val transportGen: Long,
        private val channelId: Long,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            runOnMain {
                if (!isCurrent(sessionGen, transportGen, webSocket)) {
                    runCatching { webSocket.close(1000, "stale") }
                    return@runOnMain
                }
                send(
                    JSONObject()
                        .put("type", "join")
                        .put("room", channelId.toString())
                        .put("token", activeToken)
                        .put("role", "audience"),
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runOnMain {
                if (isCurrent(sessionGen, transportGen, webSocket)) {
                    handleIncomingMessage(text, sessionGen, transportGen)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            runOnMain {
                if (isCurrent(sessionGen, transportGen, webSocket)) {
                    log("SFU websocket failure: ${t.javaClass.simpleName}")
                    scheduleReconnectOnMain("websocket failure")
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            runOnMain {
                if (isCurrent(sessionGen, transportGen, webSocket)) {
                    log("SFU websocket closed code=$code")
                    scheduleReconnectOnMain("websocket closed")
                }
            }
        }
    }

    private fun handleIncomingMessage(text: String, sessionGen: Long, transportGen: Long) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: run {
            log("SFU message parse failed")
            return
        }
        when (json.optString("type")) {
            "ping" -> send(JSONObject().put("type", "pong"))
            "pong", "joined", "room_snapshot" -> Unit
            "offer" -> {
                val generation = json.optLong("offer_generation", -1L)
                val sdp = json.optString("sdp")
                if (generation < 0L || sdp.isBlank()) {
                    log("SFU offer missing generation or SDP")
                } else {
                    offerReissueJob?.cancel()
                    offerReissueJob = null
                    negotiate(generation, sdp, sessionGen, transportGen)
                }
            }
            "error" -> {
                val detail = json.optString("message")
                log("SFU signaling error detail=$detail")
                if (detail == "stale_offer_generation" || detail == "future_offer_generation") {
                    armOfferReissueDeadline(sessionGen, transportGen)
                } else {
                    scheduleReconnectOnMain("SFU signaling error")
                }
            }
        }
    }

    private fun armOfferReissueDeadline(sessionGen: Long, transportGen: Long) {
        if (offerReissueJob?.isActive == true) return
        offerReissueJob = appScope.launch(mainDispatcher) {
            delay(OFFER_REISSUE_DEADLINE_MS)
            offerReissueJob = null
            if (sessionGen == sessionGeneration && isCurrentTransport(transportGen)) {
                scheduleReconnectOnMain("SFU did not reissue offer")
            }
        }
    }

    private fun negotiate(
        firstGeneration: Long,
        firstSdp: String,
        sessionGen: Long,
        transportGen: Long,
    ) {
        if (!isCurrentTransport(transportGen) || sessionGen != sessionGeneration) return
        if (negotiating) {
            pendingOffer = firstGeneration to firstSdp
            return
        }
        negotiating = true
        appScope.launch(mainDispatcher) {
            var offer: Pair<Long, String>? = firstGeneration to firstSdp
            try {
                while (offer != null && isCurrentTransport(transportGen) && sessionGen == sessionGeneration) {
                    val (generation, sdp) = offer
                    negotiateOne(generation, sdp, sessionGen, transportGen)
                    offer = pendingOffer
                    pendingOffer = null
                    if (offer != null) delay(50)
                }
            } catch (e: Exception) {
                if (isCurrentTransport(transportGen) && sessionGen == sessionGeneration) {
                    log("SFU negotiation failed: ${e.message}")
                    scheduleReconnectOnMain("negotiation failed")
                }
            } finally {
                negotiating = false
            }
        }
    }

    private suspend fun negotiateOne(
        generation: Long,
        offerSdp: String,
        sessionGen: Long,
        transportGen: Long,
    ) {
        val pc = peerConnection ?: error("missing peer connection")
        if (!isCurrentTransport(transportGen) || sessionGen != sessionGeneration) return
        awaitSetRemoteDescription(pc, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
        if (!isCurrentTransport(transportGen) || sessionGen != sessionGeneration) return

        val transceivers = pc.transceivers
        val offeredSections = parseMediaSections(offerSdp)
        if (transceivers.size != offeredSections.size) {
            error("SFU offer/PeerConnection m-line count mismatch")
        }
        transceivers.forEachIndexed { index, transceiver ->
            val section = offeredSections[index]
            if (transceiver.mid != section.mid) {
                error("SFU offer/PeerConnection mid order mismatch")
            }
            transceiver.direction = if (section.kind == "video") {
                RtpTransceiver.RtpTransceiverDirection.INACTIVE
            } else {
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            }
        }

        val answer = awaitCreateAnswer(pc)
        validateFullSdpAnswer(offerSdp, answer.description)
        if (!isCurrentTransport(transportGen) || sessionGen != sessionGeneration) return
        awaitSetLocalDescription(pc, SessionDescription(SessionDescription.Type.ANSWER, answer.description))
        if (!isCurrentTransport(transportGen) || sessionGen != sessionGeneration) return
        send(
            JSONObject()
                .put("type", "answer")
                .put("sdp", answer.description)
                .put("offer_generation", generation),
        )
    }

    private fun handleRemoteTrack(track: MediaStreamTrack) {
        if (track is AudioTrack) {
            track.setEnabled(true)
            remoteAudioTrack = track
        } else {
            ignoreUnexpectedVideoTrack(track)
        }
    }

    private fun ignoreUnexpectedVideoTrack(track: MediaStreamTrack) {
        track.setEnabled(false)
    }

    private suspend fun awaitSetRemoteDescription(
        pc: PeerConnection,
        description: SessionDescription,
    ) = withContext(mainDispatcher) {
        suspendCancellableCoroutine<Unit> { continuation ->
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onCreateFailure(error: String?) {
                    if (continuation.isActive) continuation.resumeWithException(RuntimeException(error ?: "set remote failed"))
                }
                override fun onSetFailure(error: String?) {
                    if (continuation.isActive) continuation.resumeWithException(RuntimeException(error ?: "set remote failed"))
                }
            }, description)
        }
    }

    private suspend fun awaitCreateAnswer(pc: PeerConnection): SessionDescription =
        withContext(mainDispatcher) {
            suspendCancellableCoroutine { continuation ->
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription?) {
                        if (!continuation.isActive) return
                        if (description == null) {
                            continuation.resumeWithException(RuntimeException("create answer returned null"))
                        } else {
                            continuation.resume(description)
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String?) {
                        if (continuation.isActive) continuation.resumeWithException(RuntimeException(error ?: "create answer failed"))
                    }
                    override fun onSetFailure(error: String?) {}
                }, org.webrtc.MediaConstraints())
            }
        }

    private suspend fun awaitSetLocalDescription(
        pc: PeerConnection,
        description: SessionDescription,
    ) = withContext(mainDispatcher) {
        suspendCancellableCoroutine<Unit> { continuation ->
            pc.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onCreateFailure(error: String?) {
                    if (continuation.isActive) continuation.resumeWithException(RuntimeException(error ?: "set local failed"))
                }
                override fun onSetFailure(error: String?) {
                    if (continuation.isActive) continuation.resumeWithException(RuntimeException(error ?: "set local failed"))
                }
            }, description)
        }
    }

    private fun scheduleReconnectOnMain(reason: String) {
        checkOnMainThread()
        if (activeStreamChannelId == null || reconnectJob?.isActive == true) return
        setStreaming(false)
        closeTransportOnMain()
        val sessionGen = sessionGeneration
        val delayMs = (1_000L shl reconnectAttempts.coerceAtMost(4)).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        reconnectAttempts++
        log("scheduling SFU reconnect reason=$reason delayMs=$delayMs")
        reconnectJob = appScope.launch(mainDispatcher) {
            delay(delayMs)
            reconnectJob = null
            if (!isActive || sessionGen != sessionGeneration || activeStreamChannelId == null) return@launch
            val provider = tokenProvider ?: return@launch
            val refreshedToken = runCatching { provider() }.getOrNull()
            if (refreshedToken.isNullOrBlank()) {
                scheduleReconnectOnMain("token refresh failed")
                return@launch
            }
            if (sessionGen != sessionGeneration || activeStreamChannelId == null) return@launch
            activeToken = refreshedToken
            openTransportOnMain()
        }
    }

    private fun send(payload: JSONObject): Boolean = webSocket?.send(payload.toString()) == true

    private fun setStreaming(value: Boolean) {
        if (isStreaming == value) return
        isStreaming = value
        onStreamingStateChanged?.invoke()
    }

    private fun isCurrentTransport(transportGen: Long): Boolean =
        transportGen == transportGeneration && peerConnection != null && activeStreamChannelId != null

    private fun isCurrent(sessionGen: Long, transportGen: Long, socket: WebSocket): Boolean =
        sessionGen == sessionGeneration &&
            transportGen == transportGeneration &&
            webSocket === socket &&
            activeStreamChannelId != null

    private fun buildWebSocketUrl(token: String): String {
        var base = BuildConfig.MEZON_SFU_WS_URL.trim()
        if (base.isEmpty()) return ""
        if (base.startsWith("https://")) base = "wss://${base.removePrefix("https://")}"
        if (base.startsWith("http://")) base = "ws://${base.removePrefix("http://")}"
        val separator = when {
            base.endsWith("?") || base.endsWith("&") -> ""
            base.contains("?") -> "&"
            else -> "?"
        }
        return base + separator + "access_token=" + URLEncoder.encode(token, Charsets.UTF_8.name())
    }

    private fun parseMediaSections(sdp: String): List<SdpMediaSection> {
        val result = ArrayList<SdpMediaSection>()
        var kind: String? = null
        var mid: String? = null
        var direction: String? = null

        fun flush() {
            val currentKind = kind ?: return
            result.add(SdpMediaSection(currentKind, mid.orEmpty(), direction))
        }

        sdp.split("\r\n", "\n").forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("m=")) {
                flush()
                kind = line.removePrefix("m=").substringBefore(' ').trim()
                mid = null
                direction = null
            } else if (kind != null) {
                when {
                    line.startsWith("a=mid:") -> mid = line.removePrefix("a=mid:").trim()
                    line in setOf("a=sendrecv", "a=sendonly", "a=recvonly", "a=inactive") -> direction = line
                }
            }
        }
        flush()
        return result
    }

    private fun validateFullSdpAnswer(offerSdp: String, answerSdp: String) {
        val offer = parseMediaSections(offerSdp)
        val answer = parseMediaSections(answerSdp)
        if (offer.size != answer.size) error("SFU answer changed m-line count")
        offer.zip(answer).forEach { (offered, actual) ->
            if (offered.kind != actual.kind || offered.mid != actual.mid) {
                error("SFU answer changed m-line order or mid")
            }
            when (offered.kind) {
                "video" -> if (actual.direction != "a=inactive") error("SFU answer activated a video m-line")
                "audio" -> if (actual.direction !in setOf("a=recvonly", "a=inactive")) {
                    error("SFU answer activated an audio sender")
                }
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else appScope.launch(mainDispatcher) { block() }
    }

    private fun checkOnMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "StreamingWebRtcSession must run on the main thread" }
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
