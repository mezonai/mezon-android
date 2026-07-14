package com.mezon.mobile.home.stream

import android.content.Context
import android.util.Log
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.MainDispatcher
import com.mezon.mobile.home.call.WebRtcInfra
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

private const val TAG = "StreamingWebRTC"
private const val AVAILABILITY_POLL_INTERVAL_MS = 3_000L

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
    @Volatile var remoteVideoTrack: VideoTrack? = null
        private set
    @Volatile var remoteAudioTrack: AudioTrack? = null
        private set

    val isRemoteVideoStream: Boolean get() = remoteVideoTrack != null

    @Volatile var onStreamingStateChanged: (() -> Unit)? = null
    @Volatile var onRemoteVideoTrackChanged: ((VideoTrack?) -> Unit)? = null

    private val audioRouting by lazy { StreamingAudioManager(context) }

    private var webSocket: WebSocket? = null
    private var peerConnection: PeerConnection? = null
    private var pendingClanId = 0L
    private var pendingChannelId = 0L
    private var pendingStreamId = 0L
    private var pendingUserId = ""
    @Volatile private var availabilityPollJob: Job? = null
    @Volatile private var hasSubscribedToStream = false

    fun join(
        clanId: Long,
        channelId: Long,
        streamId: Long,
        userId: String,
        username: String,
        token: String,
    ) {
        if (activeStreamChannelId == streamId && isStreaming && peerConnection != null) {
            log("join skipped: already connected streamId=$streamId")
            return
        }
        log("join start clanId=$clanId channelId=$channelId streamId=$streamId userId=$userId username=$username")
        disconnect()
        pendingClanId = clanId
        pendingChannelId = channelId
        pendingStreamId = streamId
        pendingUserId = userId
        activeStreamChannelId = streamId
        audioRouting.start()

        webRtcInfra.ensureFactoryReady()
        val pc = createPeerConnection() ?: run {
            log("join failed: peerConnection")
            return
        }
        peerConnection = pc

        pc.addTransceiver(
            org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )

        val wsUrl = buildWebSocketUrl(username, token)
        if (wsUrl.isEmpty()) {
            log("join failed: invalid websocket url base=${BuildConfig.MEZON_STREAM_WS_URL}")
            disconnect()
            return
        }
        log("websocket connect ${redactWsUrl(wsUrl)}")
        val request = Request.Builder().url(wsUrl).build()
        webSocket = okHttpClient.newWebSocket(request, StreamWebSocketListener())

        createOfferAndSubscribe(pc)
    }

    fun disconnect() {
        log("disconnect streamId=${activeStreamChannelId ?: "nil"} isStreaming=$isStreaming")
        availabilityPollJob?.cancel()
        availabilityPollJob = null
        hasSubscribedToStream = false
        webSocket?.close(1000, "disconnect")
        webSocket = null
        peerConnection?.close()
        peerConnection = null
        activeStreamChannelId = null
        isStreaming = false
        remoteAudioTrack = null
        audioRouting.stop()
        setRemoteVideoTrack(null)
    }

    private fun createPeerConnection(): PeerConnection? {
        val servers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        return webRtcInfra.factory.createPeerConnection(config, peerObserver)
    }

    private fun createOfferAndSubscribe(pc: PeerConnection) {
        val constraints = MediaConstraints()
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) {
                val sdp = description ?: return
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        sendJson(
                            JSONObject().apply {
                                put("Key", "session_subscriber")
                                put("ClanId", pendingClanId.toString())
                                put("ChannelId", pendingChannelId.toString())
                                put("UserId", pendingUserId)
                                put("Value", JSONObject().apply {
                                    put("type", "offer")
                                    put("sdp", sdp.description)
                                })
                            },
                            "session_subscriber"
                        )
                        sendJson(JSONObject().put("Key", "get_channels"), "get_channels")
                        startAvailabilityPolling()
                    }
                    override fun onCreateFailure(p0: String?) {
                        log("setLocalDescription failed: $p0")
                        appScope.launch(mainDispatcher) { disconnect() }
                    }
                    override fun onSetFailure(p0: String?) {
                        log("setLocalDescription failed: $p0")
                        appScope.launch(mainDispatcher) { disconnect() }
                    }
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                log("createOffer failed: $error")
                appScope.launch(mainDispatcher) { disconnect() }
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private fun startAvailabilityPolling() {
        availabilityPollJob?.cancel()
        availabilityPollJob = appScope.launch(mainDispatcher) {
            while (isActive) {
                delay(AVAILABILITY_POLL_INTERVAL_MS)
                if (isStreaming || webSocket == null) return@launch
                sendJson(JSONObject().put("Key", "get_channels"), "get_channels")
            }
        }
    }

    private val peerObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            log("signalingState=$state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            log("iceConnectionState=$state")
            if (state == PeerConnection.IceConnectionState.CONNECTED ||
                state == PeerConnection.IceConnectionState.COMPLETED
            ) {
                appScope.launch(mainDispatcher) { scanRemoteTracks("ice connected") }
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            log("iceGatheringState=$state")
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            if (candidate.sdp.isNullOrEmpty()) return
            sendJson(
                JSONObject().apply {
                    put("Key", "ice_candidate")
                    put("Value", JSONObject().apply {
                        put("candidate", candidate.sdp)
                        put("sdpMid", candidate.sdpMid ?: "")
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                    })
                },
                "ice_candidate"
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {
            stream ?: return
            appScope.launch(mainDispatcher) {
                stream.audioTracks.firstOrNull()?.let { handleRemoteTrack(it, "didAddStream.audio") }
                stream.videoTracks.firstOrNull()?.let { handleRemoteTrack(it, "didAddStream.video") }
            }
        }

        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(p0: org.webrtc.DataChannel?) {}
        override fun onRenegotiationNeeded() {
            log("negotiation needed")
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track() ?: return
            appScope.launch(mainDispatcher) { handleRemoteTrack(track, "didAddTrack") }
        }
    }

    private inner class StreamWebSocketListener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            appScope.launch(mainDispatcher) { handleIncomingMessage(text) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log("ws failure: ${t.message}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            log("ws closed code=$code reason=$reason")
        }
    }

    private fun handleIncomingMessage(text: String) {
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            log("ws message parse failed: ${text.take(200)}")
            return
        }
        when (json.optString("Key")) {
            "channels" -> handleChannelsMessage(json)
            "sd_answer" -> handleAnswerMessage(json)
            "ice_candidate" -> handleRemoteIceCandidate(json)
            "session_received" -> log("ws message key=session_received")
            "error" -> {
                log("ws message key=error value=${json.opt("Value")}")
                setStreaming(false)
            }
            else -> log("ws message key=${json.optString("Key")} value=${json.opt("Value")}")
        }
    }

    private fun handleChannelsMessage(json: JSONObject) {
        val values = json.optJSONArray("Value")
        if (values == null) {
            log("channels missing Value array")
            setStreaming(false)
            return
        }
        val streamIdString = pendingStreamId.toString()
        var containsStream = false
        for (i in 0 until values.length()) {
            val value = values.opt(i)
            if (value?.toString() == streamIdString) {
                containsStream = true
                break
            }
        }
        log("channels contains streamId=$streamIdString: $containsStream")
        if (!containsStream) {
            setStreaming(false)
            return
        }
        if (!hasSubscribedToStream) {
            hasSubscribedToStream = true
            sendJson(
                JSONObject().apply {
                    put("Key", "connect_subscriber")
                    put("ClanId", pendingClanId.toString())
                    put("ChannelId", pendingChannelId.toString())
                    put("UserId", pendingUserId)
                    put("Value", JSONObject().put("ChannelId", streamIdString))
                },
                "connect_subscriber"
            )
        }
        setStreaming(true)
    }

    private fun handleAnswerMessage(json: JSONObject) {
        val pc = peerConnection ?: run {
            log("sd_answer ignored: no peerConnection")
            return
        }
        val sdp = when (val value = json.opt("Value")) {
            is String -> value
            is JSONObject -> value.optString("sdp")
            else -> null
        }
        if (sdp.isNullOrEmpty()) {
            log("sd_answer missing sdp payload")
            return
        }
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                log("setRemoteDescription ok sdpLen=${sdp.length}")
                scanRemoteTracks("after sd_answer")
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(error: String?) {
                log("setRemoteDescription failed: $error")
            }
        }, answer)
    }

    private fun handleRemoteIceCandidate(json: JSONObject) {
        val pc = peerConnection ?: return
        val value = json.opt("Value") ?: return
        val candidateJson = when (value) {
            is JSONObject -> value
            is String -> try { JSONObject(value) } catch (_: Exception) { null }
            else -> null
        } ?: run {
            log("addIceCandidate parse failed")
            return
        }
        val sdp = candidateJson.optString("candidate")
        if (sdp.isEmpty()) return
        val candidate = IceCandidate(
            candidateJson.optString("sdpMid"),
            candidateJson.optInt("sdpMLineIndex", 0),
            sdp
        )
        pc.addIceCandidate(candidate)
        log("addIceCandidate ok mid=${candidate.sdpMid}")
    }

    private fun handleRemoteTrack(track: org.webrtc.MediaStreamTrack, source: String) {
        when (track) {
            is VideoTrack -> {
                log("$source: video track id=${track.id()}")
                track.setEnabled(true)
                setRemoteVideoTrack(track)
            }
            is AudioTrack -> {
                log("$source: audio track id=${track.id()}")
                track.setEnabled(true)
                remoteAudioTrack = track
            }
        }
    }

    private fun scanRemoteTracks(source: String) {
        val pc = peerConnection ?: return
        val transceivers = pc.transceivers
        log("$source: transceivers=${transceivers.size}")
        for ((index, transceiver) in transceivers.withIndex()) {
            val track = transceiver.receiver.track() ?: continue
            handleRemoteTrack(track, "$source.scan[$index]")
        }
    }

    private fun setRemoteVideoTrack(track: VideoTrack?) {
        if (remoteVideoTrack === track) return
        remoteVideoTrack = track
        onRemoteVideoTrackChanged?.invoke(track)
    }

    private fun setStreaming(value: Boolean) {
        if (isStreaming == value) return
        isStreaming = value
        if (value) {
            availabilityPollJob?.cancel()
            availabilityPollJob = null
        }
        log("isStreaming=$value streamId=$pendingStreamId")
        onStreamingStateChanged?.invoke()
    }

    private fun sendJson(payload: JSONObject, label: String) {
        val text = payload.toString()
        webSocket?.send(text)?.let { ok ->
            if (ok) log("ws send ok key=$label") else log("ws send failed key=$label")
        } ?: log("ws send skipped key=$label")
    }

    private fun buildWebSocketUrl(username: String, token: String): String {
        var base = BuildConfig.MEZON_STREAM_WS_URL.trim()
        if (base.isEmpty()) return ""
        if (base.startsWith("https://")) base = "wss://${base.removePrefix("https://")}"
        if (base.startsWith("http://")) base = "ws://${base.removePrefix("http://")}"
        if (!base.endsWith("/ws")) base = "$base/ws"
        val encodedUser = URLEncoder.encode(username, Charsets.UTF_8.name())
        val encodedToken = URLEncoder.encode(token, Charsets.UTF_8.name())
        return "$base?username=$encodedUser&token=$encodedToken"
    }

    private fun redactWsUrl(url: String): String {
        val tokenIdx = url.indexOf("token=")
        if (tokenIdx < 0) return url
        val raw = url.substring(tokenIdx + 6)
        val suffix = if (raw.length > 6) raw.takeLast(6) else raw
        return url.substring(0, tokenIdx + 6) + "<redacted:$suffix>"
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
