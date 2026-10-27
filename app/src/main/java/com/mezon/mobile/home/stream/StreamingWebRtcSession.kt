package com.mezon.mobile.home.stream

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.mezon.mezon.api.GetStreamPlaybackResponse
import com.mezon.mobile.R
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.MainDispatcher
import com.mezon.mobile.home.call.WebRtcInfra
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.MezonSocket
import com.mezon.mobile.session.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

enum class StreamingQuality(val layer: String, val titleRes: Int, val detailRes: Int, val maxHeight: Int) {
    AUTO("auto", R.string.stream_quality_auto, R.string.stream_quality_auto_detail, Int.MAX_VALUE),
    HIGH("high", R.string.stream_quality_high, R.string.stream_quality_high_detail, Int.MAX_VALUE),
    MEDIUM("mid", R.string.stream_quality_medium, R.string.stream_quality_medium_detail, 720),
    LOW("low", R.string.stream_quality_low, R.string.stream_quality_low_detail, 360),
}

/** HLS-first viewer transport, with WHEP when HLS is unavailable. */
@Singleton
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StreamingWebRtcSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webRtcInfra: WebRtcInfra,
    okHttpClient: OkHttpClient,
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val socket: MezonSocket,
    private val presence: StreamingController,
    @ApplicationScope private val appScope: CoroutineScope,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) {
    var activeStreamChannelId: Long? = null; private set
    var isStreaming = false; private set
    var isBuffering = false; private set
    var hasVideoFrames = false; private set
    var remoteAudioTrack: AudioTrack? = null; private set
    var remoteVideoTrack: VideoTrack? = null; private set
    var player: ExoPlayer? = null; private set
    var quality = StreamingQuality.AUTO; private set
    val defaultQuality: StreamingQuality get() = StreamingQuality.entries.firstOrNull {
        it.layer == preferences.getString("default-quality", "auto")
    } ?: StreamingQuality.AUTO
    val audioOutputIcon: com.mezon.mobile.ui.cells.MezonIcon get() = audioRouting.currentOutputIcon()
    val isSpeakerOutput: Boolean get() = audioRouting.isSpeaker
    val audioOutputName: String get() = audioRouting.deviceName
    val eglContext get() = webRtcInfra.eglContext
    var onStreamingStateChanged: (() -> Unit)? = null

    private val preferences = context.getSharedPreferences("stream-playback", Context.MODE_PRIVATE)
    private val audioRouting = StreamingAudioManager(context).apply { onChanged = { notifyState() } }
    // Signed URLs and SDP must not reach the application's logging interceptors.
    private val http = okHttpClient.newBuilder().apply {
        interceptors().clear(); networkInterceptors().clear()
        followRedirects(false); followSslRedirects(false)
        connectTimeout(5, TimeUnit.SECONDS); callTimeout(15, TimeUnit.SECONDS)
    }.build()
    private var clanId = 0L
    private var peer: PeerConnection? = null
    private var firstFrameSink: VideoSink? = null
    private var frameKeeper: com.mezon.mobile.home.voice.VideoTrackFrameKeeper? = null
    private var whepResource: HttpUrl? = null
    private var fallbackUrl: String? = null
    private var transportGeneration = 0L
    private var sessionGeneration = 0L
    private var joinGeneration = 0L
    private var pendingJoinChannelId: Long? = null
    private var transportJob: Job? = null
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var watchdogDeadline: Long? = null
    private var watchJob: Job? = null
    private var renewWatchJob: Job? = null
    private var disconnectedJob: Job? = null
    private var reconnectAttempts = 0
    private var mediaProgressJob: Job? = null
    private var lastMediaProgressAt = 0L
    private var lastMediaPosition: Long? = null
    private var lastInboundBytes = 0L

    init {
        appScope.launch(mainDispatcher) {
            socket.reconnected.collect {
                if (activeStreamChannelId != null) {
                    renewWatch()
                    presence.fetchStreamChannelMembers(clanId, noCache = true)
                }
            }
        }
        appScope.launch(mainDispatcher) {
            presence.changes.collect { changedClans ->
                val channel = activeStreamChannelId ?: return@collect
                if (changedClans.containsKey(clanId) && presence.getStreamMembersForChannel(channel, clanId).isEmpty()) {
                    stopPlayback(sendUnwatch = false)
                }
            }
        }
    }

    suspend fun join(channelId: Long, clanId: Long, canJoin: () -> Boolean = { true }): Boolean = withContext(mainDispatcher) {
        if (channelId == 0L) return@withContext false
        if (activeStreamChannelId == channelId) {
            joinGeneration++; pendingJoinChannelId = null
            return@withContext canJoin()
        }
        if (pendingJoinChannelId == channelId) return@withContext false
        val request = ++joinGeneration
        pendingJoinChannelId = channelId
        try {
            val playback = fetchPlayback(channelId)
            if (request != joinGeneration || !isActive || !canJoin()) return@withContext false
            // Fetch before disconnecting: failed/stale joins keep the current stream playing.
            stopPlayback(sendUnwatch = true, notify = false)
            activeStreamChannelId = channelId
            this@StreamingWebRtcSession.clanId = clanId
            quality = defaultQuality
            reconnectAttempts = 0
            audioRouting.start() // Defaults to speaker for every new room.
            renewWatch()
            watchJob = appScope.launch(mainDispatcher) {
                while (isActive) { delay(30_000); renewWatch() }
            }
            startPlayback(playback)
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (request == joinGeneration && canJoin()) showError()
            false
        } finally { if (request == joinGeneration) pendingJoinChannelId = null }
    }

    fun disconnect() = runOnMain {
        joinGeneration++; pendingJoinChannelId = null
        stopPlayback(sendUnwatch = true)
    }

    fun toggleSpeakerOutput() = runOnMain { audioRouting.toggleSpeaker(); notifyState() }

    fun setDefaultQuality(value: StreamingQuality) { preferences.edit().putString("default-quality", value.layer).apply() }

    fun setQuality(value: StreamingQuality) = runOnMain {
        if (quality == value) return@runOnMain
        quality = value
        applyHlsQuality()
        whepResource?.let { resource ->
            val url = resource.newBuilder().addPathSegment("layer").setQueryParameter("layer", value.layer).build()
            fireAndForget(Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build())
        }
        notifyState()
    }

    fun replayVideoFrame(sink: VideoSink) { frameKeeper?.replay(sink) }

    fun resumePresence() = runOnMain {
        if (activeStreamChannelId != null) {
            renewWatch()
            presence.fetchStreamChannelMembers(clanId, noCache = true)
        }
    }

    private suspend fun fetchPlayback(channelId: Long): GetStreamPlaybackResponse {
        val response = sessionManager.withAutoRefresh { session -> api.getStreamPlayback(session.apiUrl, session.token, channelId) }
        val playback = resolvedPlayback(response, channelId)
        require(playbackUrl(playback.hlsUrl) != null ||
            (playbackUrl(playback.whepUrl) != null && playback.token.isNotEmpty()))
        return playback
    }

    private fun playbackUrl(raw: String): HttpUrl? = raw.toHttpUrlOrNull()

    private fun resolvedPlayback(playback: GetStreamPlaybackResponse, channelId: Long): GetStreamPlaybackResponse {
        // An omitted WHEP URL can mean no edge has capacity: honor an API-provided HLS URL.
        if (channelId == 0L || playback.token.isEmpty() ||
            playbackUrl(playback.whepUrl) != null || playbackUrl(playback.hlsUrl) != null) return playback
        val baseUrl = "https://streaming.mezon.vn".toHttpUrlOrNull()!!
        val whep = baseUrl.newBuilder().addPathSegment("whep").addPathSegment(channelId.toString()).build()
        // The streaming server uses the channel's 16-digit hexadecimal ID for HLS.
        val channelPath = java.lang.Long.toHexString(channelId).padStart(16, '0')
        val hls = baseUrl.newBuilder().addPathSegment(channelPath).addPathSegment("live")
            .addPathSegment("index.m3u8").addQueryParameter("token", playback.token).build()
        return playback.toBuilder().setWhepUrl(whep.toString()).setHlsUrl(hls.toString()).build()
    }

    private fun renewWatch() {
        val channel = activeStreamChannelId ?: return
        if (renewWatchJob?.isActive == true) return
        renewWatchJob = appScope.launch(mainDispatcher) {
            try { socket.writeStreamingWatch(channel, true) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { logFailure("watch", e) }
        }
    }

    private fun startPlayback(playback: GetStreamPlaybackResponse) {
        closeTransport()
        fallbackUrl = playbackUrl(playback.hlsUrl)?.toString()
        isBuffering = true
        notifyState()
        val generation = transportGeneration
        if (fallbackUrl != null) { startHls(); return }
        val url = playbackUrl(playback.whepUrl)
        if (url == null || playback.token.isBlank()) { startHls(); return }
        armWatchdog(generation, 15_000)
        transportJob = appScope.launch(mainDispatcher) {
            try {
                webRtcInfra.ensureFactoryReady()
                val connection = webRtcInfra.factory.createPeerConnection(
                    PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN },
                    observer(generation),
                ) ?: throw IOException("Peer unavailable")
                if (!isCurrent(generation)) { connection.dispose(); return@launch }
                peer = connection
                startMediaProgressMonitoring(generation)
                connection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
                connection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
                val offer = createOffer(connection)
                if (!isCurrent(generation)) return@launch
                setDescription(connection, offer, local = true)
                withTimeout(4_000) {
                    while (isCurrent(generation) && connection.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) delay(50)
                }
                if (!isCurrent(generation)) return@launch
                val target = url.newBuilder().setQueryParameter("layer", quality.layer).build()
                val answer = postOffer(Request.Builder().url(target)
                    .header("Authorization", "Bearer ${playback.token}")
                    .post(connection.localDescription.description.toRequestBody("application/sdp".toMediaType())).build())
                if (!isCurrent(generation)) { deleteResource(answer.resource); return@launch }
                whepResource = answer.resource
                if (Regex("(?m)^m=video 0 ").containsMatchIn(answer.sdp)) throw IOException("Video rejected")
                setDescription(connection, SessionDescription(SessionDescription.Type.ANSWER, answer.sdp), local = false)
                if (isCurrent(generation)) {
                    if (connection.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED ||
                        connection.iceConnectionState() == PeerConnection.IceConnectionState.COMPLETED) connected()
                    else if (!isStreaming) armWatchdog(generation, 5_000)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (isCurrent(generation)) {
                    logFailure("whep", e)
                    if (e is WhepFailure && e.status == 404) failPlayback() else startHls()
                }
            }
        }
    }

    private fun startHls() {
        val url = fallbackUrl
        closeTransport()
        if (url == null) { scheduleReconnect(); return }
        val generation = transportGeneration
        isBuffering = true
        val next = ExoPlayer.Builder(context).build()
        player = next
        next.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_VOICE_COMMUNICATION).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
        applyHlsQuality()
        next.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { if (isCurrent(generation) && playing) connected() }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { if (isCurrent(generation)) notifyState() }
            override fun onRenderedFirstFrame() { if (isCurrent(generation)) { hasVideoFrames = true; notifyState() } }
            override fun onPlaybackStateChanged(state: Int) {
                if (!isCurrent(generation)) return
                when (state) {
                    Player.STATE_BUFFERING -> { isBuffering = true; armWatchdog(generation, 15_000); notifyState() }
                    Player.STATE_ENDED -> scheduleReconnect()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (isCurrent(generation)) { logFailure("hls", error); scheduleReconnect() }
            }
        })
        val media = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        next.setMediaSource(HlsMediaSource.Factory(DefaultHttpDataSource.Factory()).createMediaSource(media))
        armWatchdog(generation, 15_000)
        startMediaProgressMonitoring(generation)
        next.prepare()
        next.playWhenReady = true
        notifyState()
    }

    private fun applyHlsQuality() {
        player?.let { current ->
            current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(if (quality.maxHeight == Int.MAX_VALUE) Int.MAX_VALUE else quality.maxHeight * 16 / 9, quality.maxHeight)
                .setForceHighestSupportedBitrate(quality == StreamingQuality.HIGH).build()
        }
    }

    private fun observer(generation: Long) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = runOnMain {
            if (!isCurrent(generation)) return@runOnMain
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> connected()
                PeerConnection.IceConnectionState.FAILED -> startHls()
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    if (disconnectedJob?.isActive != true) disconnectedJob = appScope.launch(mainDispatcher) {
                        delay(5_000)
                        if (isCurrent(generation)) startHls()
                    }
                }
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(candidate: IceCandidate?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) { stream?.audioTracks?.forEach { remoteTrack(it, generation) }; stream?.videoTracks?.forEach { remoteTrack(it, generation) } }
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) { receiver?.track()?.let { remoteTrack(it, generation) } }
        override fun onTrack(transceiver: RtpTransceiver?) { transceiver?.receiver?.track()?.let { remoteTrack(it, generation) } }
    }

    private fun remoteTrack(track: MediaStreamTrack, generation: Long) = runOnMain {
        if (!isCurrent(generation)) return@runOnMain
        when (track) {
            is AudioTrack -> { remoteAudioTrack = track; track.setEnabled(true); track.setVolume(1.0) }
            is VideoTrack -> if (remoteVideoTrack?.id() != track.id()) {
                firstFrameSink?.let { sink -> remoteVideoTrack?.removeSink(sink) }
                frameKeeper?.let { keeper -> remoteVideoTrack?.removeSink(keeper); keeper.clear() }
                remoteVideoTrack = track
                frameKeeper = com.mezon.mobile.home.voice.VideoTrackFrameKeeper().also { track.addSink(it) }
                track.setEnabled(true)
                hasVideoFrames = false
                val first = AtomicBoolean(false)
                val sink = VideoSink { frame ->
                    if (frame.buffer.width > 0 && frame.buffer.height > 0 && first.compareAndSet(false, true)) runOnMain {
                        if (isCurrent(generation) && remoteVideoTrack === track) { hasVideoFrames = true; notifyState() }
                    }
                }
                firstFrameSink = sink
                track.addSink(sink)
            }
        }
        notifyState()
    }

    private fun noteMediaProgress() {
        lastMediaProgressAt = SystemClock.elapsedRealtime()
        reconnectAttempts = 0
        watchdogJob?.cancel(); watchdogJob = null; watchdogDeadline = null
        if (!isStreaming || isBuffering) connected()
    }

    private fun startMediaProgressMonitoring(generation: Long) {
        mediaProgressJob?.cancel()
        lastMediaProgressAt = SystemClock.elapsedRealtime()
        lastMediaPosition = null
        lastInboundBytes = 0L
        mediaProgressJob = appScope.launch(mainDispatcher) {
            while (isActive && isCurrent(generation)) {
                delay(1_000)
                if (!isCurrent(generation)) return@launch
                player?.let { current ->
                    val position = current.currentPosition
                    if (current.isPlaying && lastMediaPosition?.let { position > it } == true) noteMediaProgress()
                    lastMediaPosition = position
                } ?: peer?.let { connection ->
                    connection.getStats { report ->
                        val bytes = report.statsMap.values.filter { it.type == "inbound-rtp" }
                            .sumOf { (it.members["bytesReceived"] as? Number)?.toLong() ?: 0L }
                        runOnMain {
                            if (isCurrent(generation) && peer === connection) {
                                if (bytes > lastInboundBytes) {
                                    lastInboundBytes = bytes
                                    noteMediaProgress()
                                }
                            }
                        }
                    }
                }
                val stalledFor = SystemClock.elapsedRealtime() - lastMediaProgressAt
                if (stalledFor >= 15_000) {
                    if (player == null) startHls() else scheduleReconnect()
                    return@launch
                }
                if (stalledFor >= 3_000 && !isBuffering) { isBuffering = true; notifyState() }
            }
        }
    }

    private fun connected() {
        watchdogJob?.cancel(); watchdogJob = null; watchdogDeadline = null
        disconnectedJob?.cancel(); disconnectedJob = null
        isStreaming = true; isBuffering = false
        notifyState()
    }

    private fun armWatchdog(generation: Long, delayMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + delayMs
        if ((watchdogDeadline ?: Long.MAX_VALUE) <= deadline) return
        watchdogJob?.cancel()
        watchdogDeadline = deadline
        watchdogJob = appScope.launch(mainDispatcher) {
            delay(delayMs)
            if (!isCurrent(generation)) return@launch
            watchdogJob = null; watchdogDeadline = null
            if (player == null) startHls() else scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (activeStreamChannelId == null || reconnectJob?.isActive == true) return
        closeTransport()
        if (reconnectAttempts >= 3) { failPlayback(); return }
        val wait = ((1L shl reconnectAttempts++) * Random.nextLong(500, 1001))
        val generation = sessionGeneration
        val channel = activeStreamChannelId ?: return
        isBuffering = true; notifyState()
        reconnectJob = appScope.launch(mainDispatcher) {
            delay(wait)
            try {
                val playback = fetchPlayback(channel)
                if (generation == sessionGeneration && activeStreamChannelId == channel) {
                    reconnectJob = null
                    renewWatch()
                    startPlayback(playback)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == sessionGeneration) { reconnectJob = null; scheduleReconnect() }
            }
        }
    }

    private fun closeTransport() {
        transportGeneration++
        transportJob?.cancel(); transportJob = null
        watchdogJob?.cancel(); watchdogJob = null; watchdogDeadline = null
        disconnectedJob?.cancel(); disconnectedJob = null
        mediaProgressJob?.cancel(); mediaProgressJob = null
        firstFrameSink?.let { sink -> remoteVideoTrack?.removeSink(sink) }
        firstFrameSink = null
        frameKeeper?.let { keeper -> remoteVideoTrack?.removeSink(keeper); keeper.clear() }
        frameKeeper = null
        remoteAudioTrack = null; remoteVideoTrack = null
        peer?.let { connection -> connection.close(); connection.dispose() }; peer = null
        whepResource?.let { deleteResource(it) }; whepResource = null
        player?.release(); player = null
        isStreaming = false; hasVideoFrames = false
    }

    private fun stopPlayback(sendUnwatch: Boolean, notify: Boolean = true) {
        val previous = activeStreamChannelId
        sessionGeneration++
        reconnectJob?.cancel(); reconnectJob = null
        watchJob?.cancel(); watchJob = null
        renewWatchJob?.cancel(); renewWatchJob = null
        closeTransport()
        activeStreamChannelId = null; clanId = 0
        fallbackUrl = null; isBuffering = false
        audioRouting.stop()
        if (sendUnwatch && previous != null) socket.sendStreamingUnwatch(previous)
        if (notify) notifyState()
    }

    private fun failPlayback() { stopPlayback(sendUnwatch = true); showError() }
    private fun showError() { Toast.makeText(context, R.string.stream_unavailable, Toast.LENGTH_SHORT).show() }
    private fun isCurrent(generation: Long) = generation == transportGeneration && activeStreamChannelId != null
    private fun notifyState() { onStreamingStateChanged?.invoke() }
    private fun logFailure(stage: String, error: Exception) { Log.w("MezonStream", "$stage failed (${error.javaClass.simpleName})") }
    private fun runOnMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else appScope.launch(mainDispatcher) { block() } }

    private data class WhepAnswer(val resource: HttpUrl, val sdp: String)
    private class WhepFailure(val status: Int) : IOException("WHEP HTTP $status")

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun postOffer(request: Request): WhepAnswer = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                var resource: HttpUrl? = null
                try {
                    val answer = response.use {
                        if (it.code != 201) throw WhepFailure(it.code)
                        resource = it.header("Location")?.let { location -> request.url.resolve(location) }
                            ?.let { resolved -> playbackUrl(resolved.toString()) }
                        WhepAnswer(resource ?: throw IOException("Missing WHEP resource"), it.body?.string() ?: throw IOException("Missing SDP"))
                    }
                    continuation.resume(answer) { deleteResource(answer.resource) }
                } catch (e: Exception) {
                    resource?.let { deleteResource(it) }
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    private fun deleteResource(resource: HttpUrl) { fireAndForget(Request.Builder().url(resource).delete().build()) }
    private fun fireAndForget(request: Request) {
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    private suspend fun createOffer(connection: PeerConnection): SessionDescription = suspendCancellableCoroutine { continuation ->
        connection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { if (continuation.isActive) continuation.resume(sdp) }
            override fun onCreateFailure(error: String) { if (continuation.isActive) continuation.resumeWithException(IOException("Offer failed")) }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, MediaConstraints())
    }

    private suspend fun setDescription(connection: PeerConnection, description: SessionDescription, local: Boolean): Unit = suspendCancellableCoroutine { continuation ->
        val observer = object : SdpObserver {
            override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
            override fun onSetFailure(error: String) { if (continuation.isActive) continuation.resumeWithException(IOException("Description failed")) }
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }
        if (local) connection.setLocalDescription(observer, description) else connection.setRemoteDescription(observer, description)
    }
}
