package com.mezon.mobile.home.voice.sfu

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.media.projection.MediaProjection
import android.net.Network
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.MainDispatcher
import com.mezon.mobile.home.call.WebRtcInfra
import com.mezon.mobile.home.call.CallController
import com.mezon.mobile.home.voice.VideoTrackLastFrameStore
import com.mezon.mobile.network.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

private const val TAG = "MezonSfuSession"
private const val MID_AUDIO = "0"
private const val MID_CAMERA = "1"
private const val MID_SCREEN = "2"
private const val CAPTURE_WIDTH = 640
private const val CAPTURE_HEIGHT = 360
private const val CAPTURE_FPS = 24
private const val CAMERA_TIER_DOWNGRADE_MS = 1500L
private const val CAMERA_TIER_UPGRADE_MS = 6000L

private class CameraTier(
    val maxCameras: Int,
    val scaleDown: Double,
    val maxBitrateBps: Int,
    val maxFps: Int,
    val uncapped: Boolean = false
)

private val CAMERA_TIERS = listOf(
    CameraTier(2, 1.0, 1_000_000, 30, uncapped = true),
    CameraTier(4, 1.333, 500_000, 24),
    CameraTier(8, 2.0, 300_000, 20),
    CameraTier(Int.MAX_VALUE, 2.0, 200_000, 15)
)

private fun cameraTierIndexFor(cameras: Int, margin: Int = 0): Int {
    val index = CAMERA_TIERS.indexOfFirst { cameras <= it.maxCameras - margin }
    return if (index == -1) CAMERA_TIERS.lastIndex else index
}

private fun resolveCameraTier(cameras: Int, currentIndex: Int): Int {
    val target = cameraTierIndexFor(cameras)
    return if (target >= currentIndex) target else minOf(currentIndex, cameraTierIndexFor(cameras, 1))
}
private const val SCREEN_WIDTH = 1280
private const val SCREEN_HEIGHT = 720
private const val SCREEN_FPS = 15
private const val SPEAKING_POLL_MS = 300L
private const val SPEAKING_POLL_LARGE_ROOM_MS = 1_000L
private const val LARGE_ROOM_REMOTE_COUNT = 16
private const val RECONNECT_POLL_MS = 3000L
private const val MAX_RECONNECT_ATTEMPTS = 2
private const val HEALTHY_SESSION_MS = 30_000L
private const val MAX_TOKEN_REFRESHES = 3
internal const val TOKEN_EXPIRY_MARGIN_SECONDS = 60L
private const val MIN_SESSION_RESTART_SPACING_MS = 5_000L
private const val RETIRING_PEER_CONNECTION_GRACE_MS = 10_000L
private const val OFFER_REISSUE_DEADLINE_MS = 8_000L
private const val DTLS_CONNECT_DEADLINE_MS = 15_000L
private const val NATIVE_CLEANUP_TIMEOUT_MS = 5_000L
private const val SPEAKING_THRESHOLD = 0.02
private const val ICE_RECOVERY_GRACE_MS = 4000L
private const val MODERATOR_MUTE_WINDOW_MS = 300L
private const val HIDDEN_VIDEO_PAUSE_DELAY_MS = 5_000L
private const val KEYFRAME_MIN_INTERVAL_MS = 1_500L
private const val KEYFRAME_GLOBAL_SPACING_MS = 250L
private const val VISIBLE_VIDEO_GRACE_MS = 350L
private const val FOREGROUND_VIDEO_GRACE_MS = 1_500L
private const val KEYFRAME_ERROR_WINDOW_MS = 5_000L
private val SCREEN_KEYFRAME_RETRY_DELAYS_MS = longArrayOf(3_000, 6_000, 12_000, 24_000)
private val KEYFRAME_REQUEST_ERRORS = setOf("must_join_room_first", "session_not_found")
private val KEYFRAME_REPLY_ERRORS = setOf("publisher_not_found", "recovery_queue_full", "invalid_kind", "invalid_publisher_id")
private val SESSION_LOST_ERRORS = setOf(
    "session_not_found",
    "must_join_room_first",
    "routing_registration_failed",
    "invalid_answer_sdp",
    "invalid_answer_ufrag",
    "missing_offer_generation",
)
private val ROOM_UNAVAILABLE_ERRORS = setOf("room_capacity", "invalid_room", "room_creation_failed", "auth_not_configured")
private const val SFU_CLOSE_KICKED = 4006
private const val SFU_CLOSE_ALONE_TIMEOUT = 4011
private const val SFU_CLOSE_DUPLICATE_SESSION = 4012
private const val SFU_CLOSE_DTLS_FAILED = 4013
private const val WS_ABNORMAL_CLOSURE = 1006
private val PARTICIPANT_ACTION_ERRORS = setOf(
    "invalid_token",
    "token_room_mismatch",
    "target_not_found",
    "invalid_participant_action",
    "unsupported_participant_action",
    "auth_not_configured",
)

enum class SfuRemovalCause { KICKED, ALONE_TIMEOUT, DUPLICATE_SESSION, DISCONNECTED }

private fun removalCause(code: Int): SfuRemovalCause? = when (code) {
    1000 -> SfuRemovalCause.DISCONNECTED
    SFU_CLOSE_KICKED -> SfuRemovalCause.KICKED
    SFU_CLOSE_ALONE_TIMEOUT -> SfuRemovalCause.ALONE_TIMEOUT
    SFU_CLOSE_DUPLICATE_SESSION -> SfuRemovalCause.DUPLICATE_SESSION
    else -> null
}

// Equal jitter: 500–1000ms, 1–2s, 2–4s, 4–8s; capped at 8s.
internal fun reconnectDelayMs(attempt: Int, jitter: Double): Long {
    val ceiling = 1_000L shl attempt.coerceIn(0, 3)
    return (ceiling * (0.5 + 0.5 * jitter.coerceIn(0.0, 1.0))).toLong()
}

internal fun tokenSecondsLeft(token: String): Long? {
    val parts = token.split('.')
    if (parts.size != 3) return null
    return runCatching {
        val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        val exp = JSONObject(payload).optLong("exp", 0L)
        if (exp > 0L) exp - System.currentTimeMillis() / 1000L else null
    }.getOrNull()
}

private class MsidOwner(val mid: String, val userId: String, val peerId: String?)

private class SignalingMessage(val json: JSONObject, val offerMsidOwners: List<MsidOwner>)

private fun parseSignalingMessage(text: String): SignalingMessage? {
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
    val owners: List<MsidOwner> = if (json.optString("type") == "offer") {
        runCatching { parseMsidOwners(json.optString("sdp")) }.getOrDefault(emptyList())
    } else {
        emptyList()
    }
    return SignalingMessage(json, owners)
}

private fun parseMsidOwners(sdp: String): List<MsidOwner> {
    val owners = ArrayList<MsidOwner>()
    var currentMid: String? = null
    val userRegex = Regex("(?:^|-)u(\\d+)(?:-|$)")
    val peerRegex = Regex("(?:^|-)p(\\d+)(?:-|$)")
    for (raw in sdp.split("\r\n", "\n")) {
        val line = raw.trim()
        when {
            line.startsWith("m=") -> currentMid = null
            line.startsWith("a=mid:") -> currentMid = line.removePrefix("a=mid:").trim()
            currentMid != null && line.startsWith("a=msid:") -> {
                val mid = currentMid!!
                val parts = line.removePrefix("a=msid:").trim().split(Regex("\\s+"))
                val uid = parts.firstNotNullOfOrNull { userRegex.find(it)?.groupValues?.get(1) }
                val pid = parts.firstNotNullOfOrNull { peerRegex.find(it)?.groupValues?.get(1) }
                if (uid != null) owners.add(MsidOwner(mid, uid, pid))
            }
        }
    }
    return owners
}

private class SdpMidDirection(val mid: String, val direction: RtpTransceiver.RtpTransceiverDirection)

private fun isRemoteMid(mid: String): Boolean = mid != MID_AUDIO && mid != MID_CAMERA && mid != MID_SCREEN

private fun parseNegotiatedDirections(answerSdp: String): List<SdpMidDirection> {
    val result = ArrayList<SdpMidDirection>()
    var inMedia = false
    var mid: String? = null
    var sectionDirection = "sendrecv"
    var zeroPort = false
    var bundleOnly = false
    fun flush() {
        val sectionMid = mid ?: return
        val direction = when {
            zeroPort && !bundleOnly -> RtpTransceiver.RtpTransceiverDirection.STOPPED
            sectionDirection == "sendonly" -> RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            sectionDirection == "recvonly" -> RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            sectionDirection == "inactive" -> RtpTransceiver.RtpTransceiverDirection.INACTIVE
            else -> RtpTransceiver.RtpTransceiverDirection.SEND_RECV
        }
        result.add(SdpMidDirection(sectionMid, direction))
    }
    for (raw in answerSdp.split("\r\n", "\n")) {
        val line = raw.trim()
        when {
            line.startsWith("m=") -> {
                if (inMedia) flush()
                inMedia = true
                mid = null
                sectionDirection = "sendrecv"
                zeroPort = line.split(' ').getOrNull(1) == "0"
                bundleOnly = false
            }
            !inMedia -> {}
            line.startsWith("a=mid:") -> mid = line.removePrefix("a=mid:").trim()
            line == "a=bundle-only" -> bundleOnly = true
            line == "a=sendonly" || line == "a=recvonly" || line == "a=sendrecv" || line == "a=inactive" ->
                sectionDirection = line.removePrefix("a=")
        }
    }
    if (inMedia) flush()
    return result
}

@Singleton
class MezonSfuSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webRtcInfra: WebRtcInfra,
    private val okHttpClient: OkHttpClient,
    private val networkMonitor: NetworkMonitor,
    @ApplicationScope private val appScope: CoroutineScope,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) {
    var onConnectionState: ((SfuConnectionState) -> Unit)? = null
    var onNetworkWeak: ((Boolean) -> Unit)? = null
    var onParticipants: ((List<SfuParticipant>) -> Unit)? = null
    var onPeerJoined: ((String) -> Unit)? = null
    var onRoleChanged: ((SfuRole) -> Unit)? = null
    var onError: ((String, String?) -> Unit)? = null
    var onLocalVideoTrack: ((VideoTrack?) -> Unit)? = null
    var onLocalScreenTrack: ((VideoTrack?) -> Unit)? = null
    var onPushToTalkActive: ((Boolean) -> Unit)? = null
    var onSpeaking: ((Set<String>) -> Unit)? = null
    var tokenProvider: (suspend (Long, Long) -> String?)? = null
    var onAudioRecovery: (() -> Unit)? = null
    var onMutedByModerator: (() -> Unit)? = null
    var onRemoved: ((SfuRemovalCause, String) -> Unit)? = null
    var onNoiseStateChanged: ((NoiseSuppressionState) -> Unit)? = null
    var onAudioSendingChanged: ((Boolean) -> Unit)? = null
    var isAudioSending = false
        private set
    private var audioSendProbeJob: Job? = null
    private var audioSendProbeEpoch = 0
    private var audioSendProbeInFlight = false
    private var audioSendProbePackets: Long? = null
    private var audioSendProbeFrames: Long? = null
    var noiseState = NoiseSuppressionState.OFF
        private set
    var noiseCaptureConfirmed = false
        private set
    private var noiseProcessor: MezonNsCaptureProcessor? = null
    private var noiseChangeJob: Job? = null
    private var noiseChangeGeneration = 0
    private var noiseRequestedEnabled = false

    @Volatile var role: SfuRole = SfuRole.SPEAKER
        private set

    private var scope: CoroutineScope? = null
    private var webSocket: WebSocket? = null
    private var peerConnection: PeerConnection? = null
    private var retiringPeerConnection: PeerConnection? = null
    private var iceRecoveryJob: Job? = null
    private var transportWatchdogJob: Job? = null
    private var connectionDeadlineJob: Job? = null
    private var readiness = SfuConnectionReadiness()
    private var hasReachedConnected = false
    var connectionState = SfuConnectionState.DISCONNECTED
        private set

    private data class CallIdentity(val channelId: Long, val clanId: Long, val userId: String)
    private var callIdentity: CallIdentity? = null
    private var callFactory: PeerConnectionFactory? = null
    private var nativeStartupJob: Job? = null
    private val nativeCleanup by lazy {
        NativeCleanupBarrier(appScope, webRtcDispatcher) { error ->
            Log.w(TAG, "native cleanup failed; blocking replacement transport", error)
        }
    }
    private val factory: PeerConnectionFactory get() = checkNotNull(callFactory)
    private var lastOutboundAudioPackets: Long? = null
    private var lastCapturedAudioFrames: Long? = null
    private var captureStallTicks = 0
    private var audioRecoveryAttempts = 0
    private var token: String = ""

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var cameraSource: VideoSource? = null
    private var cameraTrack: VideoTrack? = null
    private var cameraHelper: SurfaceTextureHelper? = null
    private var cameraCapturing = false
    private var screenCaptureToken = 0
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
    private var pttRequested = false

    private var active = false
    @Volatile private var socketOpen = false
    private var connecting = false
    @Volatile private var connectionGen = 0
    private var stateRestored = false
    private var reconnectAttempts = 0
    private var transportRecoveryJob: Job? = null
    private var healthySessionResetJob: Job? = null
    private var mediaConnected = false
    private var tokenRefreshes = 0
    private var tokenRejected = false
    private var lastConnectionOpenedAtMs = 0L
    private var deferredRestartJob: Job? = null
    private var retiringCloseJob: Job? = null
    private var admitted = false
    private var selfPeerId: String? = null
    private var networkQuality = SfuNetworkQuality()
    private var networkWeak = false
    private var moderatorMuteJob: Job? = null
    private val participantActionCallbacks = ArrayDeque<(Boolean, String?) -> Unit>()

    private var negotiating = false
    private var pendingOffer: Pair<Long, String>? = null
    private var offerReissueJob: Job? = null

    private var transceiverCache: List<RtpTransceiver> = emptyList()
    private var transportStatus = TransportStatus()
    @Volatile private var localAudioSenderRef: LocalAudioSender? = null
    private var remoteSnapshot: List<RemoteTransceiverSnapshot> = emptyList()
    private var negotiatedDirections: List<SdpMidDirection> = emptyList()
    private var remoteTracks = RemoteTrackRegistry()
    private var snapshotTrackEvents = -1
    private val screenKeyframeRequests = LinkedHashMap<String, ScreenKeyframeRequest>()
    private val lastVideoKeyframeRequests = HashMap<String, Long>()
    private var screenKeyframeJob: Job? = null
    private var lastKeyframeRequestMs: Long? = null
    private val videoRecoveryChecks = LinkedHashMap<String, VideoRecoveryCheck>()
    private val videoExposures = LinkedHashMap<String, VideoExposure>()
    private val queuedVideoKeyframes = LinkedHashMap<String, QueuedKeyframe>()
    private var nextVideoKeyframeSendMs = 0L
    private val screenRecoveryStartedAt = HashMap<String, Long>()
    private var videoBackgroundedAtMs: Long? = null
    private var appVisible = true
    private var videoVisible = true
    private var hiddenVideoJob: Job? = null
    private val webRtcDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "sfu-webrtc") }.asCoroutineDispatcher()

    private val userIdByMid = HashMap<String, String>()
    private val peerIdByMid = HashMap<String, String>()
    private val roleByMid = HashMap<String, SfuRole>()
    private val memberByPeerId = HashMap<String, MemberState>()
    private val remote = LinkedHashMap<String, RemoteEntry>()
    private var lastParticipants: List<SfuParticipant> = emptyList()
    private var lastSpeakingIds: Set<String> = emptySet()
    private var cameraTierIndex = 0
    private var cameraTierJob: Job? = null

    private class RemoteEntry(val id: String) {
        var userId: String? = null
        var peerId: String? = null
        var role: SfuRole? = null
        var muted: Boolean = false
        var audio: AudioTrack? = null
        var video: VideoTrack? = null
        var screen: VideoTrack? = null
        var screenActive: Boolean = false
        var screenActiveSinceMs: Long = 0
        var cameraActive: Boolean = false
    }

    private class ScreenKeyframeRequest(
        val track: VideoTrack,
        val activeSinceMs: Long,
        val firstSeenMs: Long,
    ) : VideoSink {
        @Volatile var lastFrameMs: Long = 0
        var attempts = 0
        var lastSentMs = 0L
        var satisfied = false
        private var detached = true

        override fun onFrame(frame: VideoFrame) {
            lastFrameMs = SystemClock.elapsedRealtime()
        }

        fun attach(): Boolean {
            if (!detached) return true
            if (runCatching { track.addSink(this) }.isFailure) return false
            detached = false
            return true
        }

        fun detach() {
            if (detached) return
            detached = true
            runCatching { track.removeSink(this) }
        }
    }

    private class VideoRecoveryCheck(
        val track: VideoTrack,
        val kind: String,
        val publisherId: Long,
        val startedAtMs: Long,
        val checkAtMs: Long,
    ) : VideoSink {
        @Volatile var lastFrameMs = 0L
        override fun onFrame(frame: VideoFrame) { lastFrameMs = SystemClock.elapsedRealtime() }
        fun detach() { runCatching { track.removeSink(this) } }
    }

    private data class VideoExposure(val track: VideoTrack, val focused: Boolean)

    private class QueuedKeyframe(
        val track: VideoTrack, val kind: String, val publisherId: Long,
        var readyAtMs: Long, var frameSinceMs: Long,
    ) : VideoSink {
        @Volatile var lastFrameMs = 0L
        override fun onFrame(frame: VideoFrame) { lastFrameMs = SystemClock.elapsedRealtime() }
        fun detach() { runCatching { track.removeSink(this) } }
    }

    private class MemberState {
        var userId: String? = null
        var role: SfuRole? = null
        var muted: Boolean? = null
        var cameraActive: Boolean? = null
        var screenActive: Boolean? = null
    }

    private class RemoteTransceiverSnapshot(
        val mid: String?,
        val direction: RtpTransceiver.RtpTransceiverDirection?,
        val track: MediaStreamTrack?,
    )

    private class RemoteTrackRegistry {
        val byMid = ConcurrentHashMap<String, MediaStreamTrack>()
        val events = AtomicInteger()
    }

    private class TransportStatus {
        @Volatile var connection = PeerConnection.PeerConnectionState.NEW
        @Volatile var ice = PeerConnection.IceConnectionState.NEW
    }

    private class LocalAudioSender(val gen: Int, val sender: RtpSender)

    private enum class LocalAudioOutcome { APPLIED, TRACK_ENDED, FAILED }

    fun isInRoom(channelId: Long, clanId: Long): Boolean =
        active && callIdentity?.let { it.channelId == channelId && it.clanId == clanId } == true

    fun join(channelId: Long, clanId: Long, userId: String, token: String, role: SfuRole) {
        leave()
        callIdentity = CallIdentity(channelId, clanId, userId)
        audioRecoveryAttempts = 0
        resetAudioFlow()
        this.token = token
        this.role = role
        this.micEnabled = false
        this.cameraEnabled = false
        this.screenOn = false
        this.pttActive = false
        this.pttRequested = false
        this.joined = false
        hasReachedConnected = false
        this.localTracksAdded = false
        this.active = true
        this.reconnectAttempts = 0
        this.tokenRefreshes = 0
        this.tokenRejected = false
        val roomScope = CoroutineScope(SupervisorJob() + mainDispatcher)
        scope = roomScope
        connecting = true
        emitState(SfuConnectionState.CONNECTING)
        val gen = connectionGen
        nativeStartupJob = roomScope.launch(start = CoroutineStart.LAZY) {
            if (!awaitNativeCleanup(gen)) return@launch
            nativeStartupJob = null
            startInitialSession(roomScope)
        }
        nativeStartupJob?.start()
    }

    private fun startInitialSession(roomScope: CoroutineScope) {
        try {
            restoreCommunicationAudio()
            val gen = connectionGen
            val processor = MezonNsCaptureProcessor(context,
                onFirstProcessedFrame = {
                    appScope.launch(mainDispatcher) {
                        if (active && gen == connectionGen && noiseRequestedEnabled) {
                            noiseCaptureConfirmed = true
                            onNoiseStateChanged?.invoke(noiseState)
                        }
                    }
                },
                onFailure = {
                    appScope.launch(mainDispatcher) {
                        if (active && gen == connectionGen && noiseRequestedEnabled) {
                            noiseChangeGeneration++
                            noiseChangeJob?.cancel()
                            noiseChangeJob = null
                            noiseRequestedEnabled = false
                            noiseState = NoiseSuppressionState.ERROR
                            noiseProcessor?.cancelChange()
                            onNoiseStateChanged?.invoke(noiseState)
                        }
                    }
                })
            noiseProcessor = processor
            callFactory = webRtcInfra.createVoiceFactory(processor)
            createLocalAudioTrack()
        } catch (e: Exception) {
            leave()
            emitState(SfuConnectionState.FAILED)
            return
        }

        if (buildWsUrl(token).isEmpty()) {
            Log.e(TAG, "join failed: empty MEZON_SFU_WS_URL")
            leave()
            emitState(SfuConnectionState.FAILED)
            return
        }
        openConnection(initial = true)
        roomScope.launch {
            while (isActive) {
                delay(if (remote.size > LARGE_ROOM_REMOTE_COUNT) SPEAKING_POLL_LARGE_ROOM_MS else SPEAKING_POLL_MS)
                if (onSpeaking == null) continue
                peerConnection?.let { pollSpeaking(it) }
            }
        }
        roomScope.launch {
            while (isActive) {
                delay(RECONNECT_POLL_MS)
                if (active && !socketOpen && !connecting && transportRecoveryJob == null && networkMonitor.isOnline.value) {
                    recoverTransport(connectionGen)
                }
            }
        }
        roomScope.launch {
            while (isActive) {
                delay(5000L)
                peerConnection?.let { checkMediaStats(it) }
            }
        }
        roomScope.launch {
            var lastNetwork: Network? = networkMonitor.activeNetwork.value
            var wasOffline = lastNetwork == null
            networkMonitor.activeNetwork.drop(1).collect { network ->
                if (network == null) {
                    wasOffline = true
                } else {
                    val changed = wasOffline || network != lastNetwork
                    lastNetwork = network
                    wasOffline = false
                    if (changed) scheduleIceRecovery()
                }
            }
        }
    }

    private fun scheduleIceRecovery() {
        if (!active || !joined || iceRecoveryJob != null || transportRecoveryJob != null) return
        val gen = connectionGen
        iceRecoveryJob = scope?.launch {
            delay(ICE_RECOVERY_GRACE_MS)
            if (!isActive || gen != connectionGen) return@launch
            iceRecoveryJob = null
            if (!active || !networkMonitor.isOnline.value || connecting) return@launch
            val status = if (peerConnection != null) transportStatus else null
            val ice = status?.ice
            if (socketOpen && status?.connection == PeerConnection.PeerConnectionState.CONNECTED &&
                (ice == PeerConnection.IceConnectionState.CONNECTED || ice == PeerConnection.IceConnectionState.COMPLETED)
            ) return@launch
            recoverTransport(gen)
        }
    }

    private fun restoreCommunicationAudio() {
        if (!active || CallController.instance?.isCallSessionActive() == true) return
        onAudioRecovery?.invoke()
    }

    private fun resetAudioFlow() {
        lastOutboundAudioPackets = null
        lastCapturedAudioFrames = null
        captureStallTicks = 0
    }

    private fun checkMediaStats(pc: PeerConnection) {
        if (!active || transportStatus.connection != PeerConnection.PeerConnectionState.CONNECTED) return
        val gen = connectionGen
        appScope.launch(webRtcDispatcher) {
            if (gen != connectionGen) return@launch
            pc.getStats { report ->
                val packets = report.statsMap.values.filter {
                    it.type == "outbound-rtp" && (it.members["kind"] ?: it.members["mediaType"]) == "audio"
                }.sumOf { (it.members["packetsSent"] as? Number)?.toLong() ?: 0L }
                val lossSamples = SfuNetworkQuality.lossSamples(report)
                appScope.launch(mainDispatcher) statsResult@{
                    if (!active || !isCurrentConnection(pc, gen)) return@statsResult
                    if (localTracksAdded) evaluateAudioFlow(packets)
                    updateNetworkQuality(lossSamples)
                }
            }
        }
    }

    private fun updateNetworkQuality(samples: List<SfuNetworkQuality.LossSample>) {
        if (connectionState != SfuConnectionState.CONNECTED) return
        setNetworkWeak(networkQuality.update(samples))
    }

    private fun setNetworkWeak(weak: Boolean) {
        if (networkWeak == weak) return
        networkWeak = weak
        onNetworkWeak?.invoke(weak)
    }

    private fun evaluateAudioFlow(packets: Long) {
        val previous = lastOutboundAudioPackets
        val captured = noiseProcessor?.capturedFrameCount ?: 0L
        val previousCaptured = lastCapturedAudioFrames
        lastOutboundAudioPackets = packets
        lastCapturedAudioFrames = captured
        val captureProgress = previousCaptured == null || captured > previousCaptured
        if (!shouldSendAudio || previous == null || (packets > previous && captureProgress) ||
            CallController.instance?.isCallSessionActive() == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            captureStallTicks = 0
            return
        }
        setAudioSending(false)
        updateAudioSendProbe()
        captureStallTicks++
        if (captureStallTicks < 2 || audioRecoveryAttempts >= 3) return
        captureStallTicks = 0
        audioRecoveryAttempts++
        restoreCommunicationAudio()
        if (!synchronizeLocalAudioTrack() || audioRecoveryAttempts > 1) recoverTransport(connectionGen)
    }

    private fun openConnection(initial: Boolean) {
        if (!active || transportRecoveryJob != null || nativeStartupJob != null) return
        val call = callIdentity ?: return
        resetAudioFlow()
        if (!initial) {
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                leave()
                emitState(SfuConnectionState.FAILED)
                return
            }
            reconnectAttempts++
        }
        healthySessionResetJob?.cancel()
        healthySessionResetJob = null
        mediaConnected = false
        connecting = true
        connectionGen++
        val gen = connectionGen
        stateRestored = false
        admitted = false
        selfPeerId = null
        readiness = SfuConnectionReadiness(requiresVoiceJoined = !hasReachedConnected)
        participantActionCallbacks.clear()
        clearOfferReissueDeadline()
        clearTransportWatchdog()
        clearConnectionDeadline()
        clearModeratorMuteCheck()
        clearDeferredRestart()
        resetVideoKeyframeRequests()
        lastConnectionOpenedAtMs = SystemClock.elapsedRealtime()
        if (!initial) {
            if (pttActive) {
                pttActive = false
                localAudioTrack?.setEnabled(false)
                onPushToTalkActive?.invoke(false)
            }
            socketOpen = false
            runCatching { webSocket?.close(1000, null) }
            disposePeerConnection(peerConnection)
            disposePeerConnection(retiringPeerConnection)
            retiringPeerConnection = null
            retiringCloseJob?.cancel()
            retiringCloseJob = null
            peerConnection = null
            userIdByMid.clear()
            peerIdByMid.clear()
            roleByMid.clear()
            memberByPeerId.clear()
            remote.clear()
            emitParticipants()
        }
        disposePeerConnection(peerConnection)
        peerConnection = null
        negotiating = false
        pendingOffer = null
        localTracksAdded = false
        transceiverCache = emptyList()
        localAudioSenderRef = null
        transportStatus = TransportStatus()
        remoteSnapshot = emptyList()
        negotiatedDirections = emptyList()
        emitState(if (initial) SfuConnectionState.CONNECTING else SfuConnectionState.DISCONNECTED)
        nativeStartupJob = scope?.launch(start = CoroutineStart.LAZY) {
            if (!awaitNativeCleanup(gen)) return@launch
            nativeStartupJob = null
            finishOpenConnection(gen, call, initial)
        }
        nativeStartupJob?.start()
    }

    private fun finishOpenConnection(gen: Int, call: CallIdentity, initial: Boolean) {
        if (!active || gen != connectionGen || callIdentity != call) return
        val tracks = RemoteTrackRegistry()
        remoteTracks = tracks
        snapshotTrackEvents = -1
        val status = TransportStatus()
        transportStatus = status
        val pc = createPeerConnection(gen, tracks, status)
        if (pc == null) {
            leave()
            emitState(SfuConnectionState.FAILED)
            return
        }
        peerConnection = pc
        emitState(if (initial) SfuConnectionState.CONNECTING else SfuConnectionState.DISCONNECTED)
        val request = Request.Builder().url(buildWsUrl(token)).build()
        webSocket = okHttpClient.newWebSocket(request, SfuSocketListener(gen, call))
        armConnectionDeadline(gen, 30_000L)
    }

    private fun pollSpeaking(pc: PeerConnection) {
        val localId = callIdentity?.userId ?: return
        val localAudible = micEnabled || pttActive
        val midOwners = HashMap(userIdByMid)
        val gen = connectionGen
        appScope.launch(webRtcDispatcher) {
            if (gen != connectionGen) return@launch
            pc.getStats { report ->
                val speaking = HashSet<String>()
                for (stats in report.statsMap.values) {
                    if (stats.members["kind"] != "audio") continue
                    val level = (stats.members["audioLevel"] as? Number)?.toDouble() ?: continue
                    if (level <= SPEAKING_THRESHOLD) continue
                    when (stats.type) {
                        "media-source" -> if (localAudible) speaking.add(localId)
                        "inbound-rtp" -> {
                            val mid = stats.members["mid"] as? String
                            val uid = mid?.let { midOwners[it] }
                            if (uid != null) speaking.add(uid)
                        }
                    }
                }
                appScope.launch(mainDispatcher) {
                    if (active && gen == connectionGen && peerConnection === pc && speaking != lastSpeakingIds) {
                        lastSpeakingIds = speaking
                        onSpeaking?.invoke(speaking)
                    }
                }
            }
        }
    }

    fun leave() {
        stopAudioSendProbe()
        noiseChangeGeneration++
        noiseChangeJob?.cancel()
        noiseChangeJob = null
        noiseState = NoiseSuppressionState.OFF
        noiseCaptureConfirmed = false
        noiseRequestedEnabled = false
        val retiredNoiseProcessor = noiseProcessor
        noiseProcessor = null
        nativeStartupJob?.cancel()
        nativeStartupJob = null
        transportRecoveryJob?.cancel()
        transportRecoveryJob = null
        active = false
        connectionState = SfuConnectionState.DISCONNECTED
        networkQuality = SfuNetworkQuality()
        setNetworkWeak(false)
        callIdentity = null
        resetAudioFlow()
        videoExposures.clear()
        hiddenVideoJob?.cancel()
        hiddenVideoJob = null
        resetVideoKeyframeRequests()
        healthySessionResetJob?.cancel()
        healthySessionResetJob = null
        mediaConnected = false
        connectionGen++
        socketOpen = false
        connecting = false
        stateRestored = false
        admitted = false
        selfPeerId = null
        readiness = SfuConnectionReadiness(requiresVoiceJoined = !hasReachedConnected)
        participantActionCallbacks.clear()
        pttRequested = false
        clearOfferReissueDeadline()
        clearTransportWatchdog()
        clearConnectionDeadline()
        clearModeratorMuteCheck()
        clearDeferredRestart()
        retiringCloseJob?.cancel()
        retiringCloseJob = null
        scope?.cancel()
        scope = null
        webSocket?.close(1000, "leave")
        webSocket = null
        val oldCameraCapturer = cameraCapturer
        val wasCameraCapturing = cameraCapturing
        val oldCameraTrack = cameraTrack
        val oldCameraSource = cameraSource
        val oldCameraHelper = cameraHelper
        val oldScreenCapturer = screenCapturer
        val oldScreenTrack = screenTrack
        val oldScreenSource = screenSource
        val oldScreenHelper = screenHelper
        val oldAudioTrack = localAudioTrack
        val oldAudioSource = audioSource
        cameraCapturing = false
        screenCaptureToken++
        cameraTrack = null; cameraSource = null; cameraHelper = null; cameraCapturer = null
        screenTrack = null; screenSource = null; screenHelper = null; screenCapturer = null
        localAudioTrack = null; audioSource = null
        if (oldScreenTrack != null) onLocalScreenTrack?.invoke(null)
        transceiverCache = emptyList()
        localAudioSenderRef = null
        remoteSnapshot = emptyList()
        negotiatedDirections = emptyList()
        remoteTracks = RemoteTrackRegistry()
        snapshotTrackEvents = -1
        iceRecoveryJob?.cancel()
        iceRecoveryJob = null
        disposePeerConnection(peerConnection)
        disposePeerConnection(retiringPeerConnection)
        val retiredFactory = callFactory
        callFactory = null
        if (retiredFactory != null || retiredNoiseProcessor != null || oldCameraCapturer != null || oldScreenCapturer != null ||
            oldAudioTrack != null || oldAudioSource != null || oldCameraTrack != null || oldScreenTrack != null ||
            oldCameraSource != null || oldCameraHelper != null || oldScreenSource != null || oldScreenHelper != null) {
            nativeCleanup.enqueue {
                var failure: Exception? = null
                fun release(action: () -> Unit) {
                    try { action() } catch (error: Exception) { failure = error }
                }
                if (wasCameraCapturing) release { oldCameraCapturer?.stopCapture() }
                release { oldCameraCapturer?.dispose() }
                release { oldScreenCapturer?.stopCapture() }
                release { oldScreenCapturer?.dispose() }
                release { oldCameraTrack?.dispose() }
                release { oldCameraSource?.dispose() }
                release { oldCameraHelper?.dispose() }
                release { oldScreenTrack?.dispose() }
                release { oldScreenSource?.dispose() }
                release { oldScreenHelper?.dispose() }
                release { oldAudioTrack?.dispose() }
                release { oldAudioSource?.dispose() }
                release { retiredFactory?.dispose() }
                release { retiredNoiseProcessor?.close() }
                failure?.let { throw it }
            }
        }
        peerConnection = null
        retiringPeerConnection = null
        VideoTrackLastFrameStore.clear()
        joined = false
        localTracksAdded = false
        negotiating = false
        pendingOffer = null
        userIdByMid.clear(); peerIdByMid.clear(); roleByMid.clear()
        memberByPeerId.clear()
        remote.clear()
        lastParticipants = emptyList()
        lastSpeakingIds = emptySet()
    }

    fun setMicEnabled(on: Boolean) {
        if (!active || role != SfuRole.SPEAKER || (on && connectionState != SfuConnectionState.CONNECTED)) return
        applyMicEnabled(on)
    }

    private fun applyMicEnabled(on: Boolean) {
        micEnabled = on
        applyLocalAudio()
        if (on) restoreCommunicationAudio()
        send(JSONObject().put("type", "mute").put("is_mute", !on))
    }

    fun setNoiseSuppressionEnabled(enabled: Boolean) {
        if (!active || noiseState == NoiseSuppressionState.APPLYING) return
        val processor = noiseProcessor ?: return
        noiseRequestedEnabled = enabled
        noiseCaptureConfirmed = false
        noiseState = NoiseSuppressionState.APPLYING
        onNoiseStateChanged?.invoke(noiseState)
        val change = ++noiseChangeGeneration
        noiseChangeJob?.cancel()
        noiseChangeJob = scope?.launch {
            val success = withContext(Dispatchers.IO) {
                if (enabled) processor.enable() else runCatching { processor.disable() }.isSuccess
            }
            if (!active || change != noiseChangeGeneration || processor !== noiseProcessor) return@launch
            if (!success) processor.cancelChange()
            if (!success) noiseRequestedEnabled = false
            noiseState = when {
                !success -> NoiseSuppressionState.ERROR
                enabled -> NoiseSuppressionState.ON
                else -> NoiseSuppressionState.OFF
            }
            onNoiseStateChanged?.invoke(noiseState)
        }
    }

    fun setCameraEnabled(on: Boolean) {
        if (!active) return
        cameraEnabled = on
        scheduleCameraTier()
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
        cameraCapturer?.switchCamera(null)
    }

    fun setScreenShare(on: Boolean, permissionData: Intent?) {
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
        if (!active || connectionState != SfuConnectionState.CONNECTED || role != SfuRole.AUDIENCE) return
        if (pttRequested) return
        pttRequested = true
        restoreCommunicationAudio()
        applyLocalAudio()
        send(JSONObject().put("type", "mute").put("is_mute", false))
        send(JSONObject().put("type", "push_to_talk").put("active", true))
    }

    fun pttRelease() {
        if (role != SfuRole.AUDIENCE) return
        val wasActive = pttActive
        pttRequested = false
        pttActive = false
        applyLocalAudio()
        send(JSONObject().put("type", "push_to_talk").put("active", false))
        send(JSONObject().put("type", "mute").put("is_mute", true))
        if (wasActive) onPushToTalkActive?.invoke(false)
    }

    fun sendParticipantAction(token: String, onResult: (Boolean, String?) -> Unit): Boolean {
        val ws = webSocket ?: return false
        if (!active || !socketOpen || !admitted) return false
        if (!ws.send(JSONObject().put("type", "participant_action").put("token", token).toString())) return false
        participantActionCallbacks.addLast(onResult)
        return true
    }

    fun setAppVisible(visible: Boolean) {
        if (appVisible == visible) return
        appVisible = visible
        hiddenVideoJob?.cancel()
        hiddenVideoJob = null
        if (visible) {
            if (active) checkVideoAfterForeground()
            applyVideoVisible(true)
            requestMissingScreenKeyframes()
            return
        }
        videoBackgroundedAtMs = SystemClock.elapsedRealtime()
        suspendVideoKeyframeQueue()
        hiddenVideoJob = appScope.launch(mainDispatcher) {
            delay(HIDDEN_VIDEO_PAUSE_DELAY_MS)
            hiddenVideoJob = null
            if (!appVisible) applyVideoVisible(false)
        }
    }

    private fun applyVideoVisible(visible: Boolean) {
        if (videoVisible == visible) return
        videoVisible = visible
        if (active && socketOpen && admitted) sendVisibility()
        if (!visible) {
            screenKeyframeJob?.cancel()
            screenKeyframeJob = null
        }
    }

    private fun sendVisibility() {
        send(JSONObject().put("type", "visibility").put("visible", videoVisible))
    }

    private fun createPeerConnection(gen: Int, tracks: RemoteTrackRegistry, status: TransportStatus): PeerConnection? {
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
        return factory.createPeerConnection(config, makePeerObserver(gen, tracks, status))
    }

    private fun createLocalAudioTrack() {
        if (localAudioTrack?.let { !it.isDisposed && it.state() == MediaStreamTrack.State.LIVE } == true) return
        stopAudioSendProbe()
        val oldTrack = localAudioTrack
        val oldSource = audioSource
        if (oldTrack?.isDisposed == false) oldTrack.setEnabled(false)
        localAudioTrack = null
        audioSource = null
        runCatching { oldTrack?.dispose() }
        runCatching { oldSource?.dispose() }
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val source = factory.createAudioSource(constraints)
        audioSource = source
        val track = factory.createAudioTrack("sfu_audio", source)
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

    private inner class SfuSocketListener(private val gen: Int, private val call: CallIdentity) : WebSocketListener() {
        @Volatile private var receivedCloseCode: Int? = null
        @Volatile private var receivedCloseReason: String = ""

        override fun onOpen(webSocket: WebSocket, response: Response) {
            appScope.launch(mainDispatcher) {
                if (gen != connectionGen) {
                    runCatching { webSocket.close(1000, null) }
                    return@launch
                }
                socketOpen = true
                connecting = false
                emitState(SfuConnectionState.JOINING)
                send(
                    JSONObject()
                        .put("type", "join")
                        .put("room", call.channelId.toString())
                        .put("token", token)
                        .put("role", role.wire)
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = parseSignalingMessage(text) ?: return
            appScope.launch(mainDispatcher) {
                if (gen != connectionGen) return@launch
                handleMessage(message)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            receivedCloseReason = reason
            receivedCloseCode = code
            runCatching { webSocket.close(code, null) }
            appScope.launch(mainDispatcher) { handleSocketClosed(gen, code, reason) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            appScope.launch(mainDispatcher) {
                if (gen != connectionGen || !active) return@launch
                if (receivedCloseCode == null && response?.code in setOf(401, 403)) tokenRejected = true
                handleSocketClosed(gen, receivedCloseCode ?: WS_ABNORMAL_CLOSURE, receivedCloseReason)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            appScope.launch(mainDispatcher) { handleSocketClosed(gen, code, reason) }
        }
    }

    private fun handleSocketClosed(gen: Int, code: Int, reason: String) {
        if (gen != connectionGen || !active) return
        val cause = removalCause(code)
        if (cause != null) {
            handleRemoved(gen, cause, reason)
            return
        }
        when (code) {
            4003, 4004, 4005 -> tokenRejected = true
            SFU_CLOSE_DTLS_FAILED -> Unit
        }
        recoverTransport(gen)
    }

    private fun handleRemoved(gen: Int, cause: SfuRemovalCause, reason: String) {
        if (gen != connectionGen || !active) return
        Log.w(TAG, "sfu removed this peer from the room cause=$cause reason='$reason'")
        leave()
        onRemoved?.invoke(cause, reason)
    }

    private fun handleMessage(message: SignalingMessage) {
        val msg = message.json
        when (msg.optString("type")) {
            "ping" -> send(JSONObject().put("type", "pong"))
            "pong" -> {}
            "joined" -> {
                if (connectionState == SfuConnectionState.CONNECTING || connectionState == SfuConnectionState.JOINING) {
                    emitState(SfuConnectionState.AWAITING_OFFER)
                }
            }
            "room_snapshot" -> {
                if (applyPeers(msg.optJSONArray("members")) && peerConnection != null) syncRemoteMedia()
                joined = true
                admitted = true
                selfPeerId = msg.opt("self_peer_id")?.toString()?.takeIf { it != "null" && it != "0" && it.isNotBlank() }
                tokenRefreshes = 0
                tokenRejected = false
                if (!stateRestored) {
                    stateRestored = true
                    applyLocalAudio()
                    val resumePushToTalk = role == SfuRole.AUDIENCE && pttRequested
                    val unmuted = if (role == SfuRole.SPEAKER) micEnabled else resumePushToTalk
                    if (unmuted) send(JSONObject().put("type", "mute").put("is_mute", false))
                    if (resumePushToTalk) {
                        send(JSONObject().put("type", "push_to_talk").put("active", true))
                    }
                    if (role == SfuRole.SPEAKER) {
                        send(JSONObject().put("type", "camera").put("active", cameraEnabled))
                        if (screenOn) send(JSONObject().put("type", "share_screen").put("active", true))
                    }
                    sendVisibility()
                }
                updateConnectionReadiness()
                emitParticipants()
                requestMissingScreenKeyframes()
            }
            "peer_joined", "peer_updated" -> {
                val peer = msg.optJSONObject("peer")
                val newcomer = if (msg.optString("type") == "peer_joined") peer?.let { newcomerUserId(it) } else null
                if (peer != null && applyPeers(org.json.JSONArray().put(peer)) && peerConnection != null) syncRemoteMedia()
                emitParticipants()
                newcomer?.let { onPeerJoined?.invoke(it) }
                val selfPeer = selfPeerId
                if (msg.optString("type") == "peer_updated" && peer != null && selfPeer != null &&
                    peer.opt("peer_id")?.toString() == selfPeer && peer.optBoolean("is_mute") && micEnabled
                ) {
                    armModeratorMuteCheck()
                }
            }
            "peer_left" -> {
                handlePeerLeft(msg)
                emitParticipants()
            }
            "push_to_talk_changed" -> {
                if (role != SfuRole.AUDIENCE) return
                val granted = msg.optBoolean("active") && pttRequested
                pttActive = granted
                applyLocalAudio()
                if (granted) restoreCommunicationAudio()
                onPushToTalkActive?.invoke(granted)
            }
            "role_changed" -> {
                val newRole = SfuRole.fromWire(msg.optString("role"))
                scope?.launch { handleRoleChanged(newRole) }
            }
            "offer" -> {
                clearOfferReissueDeadline()
                val sdp = msg.optString("sdp")
                if (sdp.isNotEmpty()) onOffer(msg.optLong("offer_generation"), sdp, message.offerMsidOwners)
            }
            "mute_changed" -> clearModeratorMuteCheck()
            "participant_action_completed" -> {
                participantActionCallbacks.removeFirstOrNull()?.invoke(true, null)
            }
            "keyframe_requested" -> Unit
            "error" -> {
                val detail = msg.optString("message")
                if (!admitted && (detail == "invalid_token" || detail == "missing_token")) {
                    tokenRejected = true
                    recoverTransport(connectionGen)
                    return
                }
                when {
                    detail == "invalid_push_to_talk" || detail == "push_to_talk_rejected" -> {
                        pttActive = false
                        pttRequested = false
                        applyLocalAudio()
                        onPushToTalkActive?.invoke(false)
                    }
                    detail == "stale_offer_generation" || detail == "future_offer_generation" -> {
                        if (!negotiating && pendingOffer == null) {
                            Log.w(TAG, "sfu rejected the answer generation ($detail); waiting for the reissued offer")
                            armOfferReissueDeadline()
                        }
                    }
                    admitted && detail in PARTICIPANT_ACTION_ERRORS -> {
                        Log.w(TAG, "sfu rejected the participant action ($detail)")
                        participantActionCallbacks.removeFirstOrNull()?.invoke(false, detail)
                    }
                    detail in KEYFRAME_REPLY_ERRORS -> {
                        Log.w(TAG, "sfu rejected the keyframe request ($detail)")
                    }
                    detail in KEYFRAME_REQUEST_ERRORS && lastKeyframeRequestMs?.let {
                        SystemClock.elapsedRealtime() - it < KEYFRAME_ERROR_WINDOW_MS
                    } == true -> {
                        Log.w(TAG, "sfu keyframe request deferred: $detail")
                    }
                    detail in SESSION_LOST_ERRORS -> {
                        Log.w(TAG, "sfu lost this session ($detail); reconnecting")
                        recoverTransport(connectionGen)
                    }
                    detail in ROOM_UNAVAILABLE_ERRORS -> {
                        Log.e(TAG, "sfu error: $detail")
                        onError?.invoke(detail, msg.optString("message"))
                        handleRemoved(connectionGen, SfuRemovalCause.DISCONNECTED, detail)
                    }
                    else -> {
                        Log.w(TAG, "sfu error ignored: $detail")
                    }
                }
            }
        }
    }

    private fun armOfferReissueDeadline() {
        if (offerReissueJob?.isActive == true) return
        val gen = connectionGen
        offerReissueJob = scope?.launch {
            delay(OFFER_REISSUE_DEADLINE_MS)
            offerReissueJob = null
            if (gen != connectionGen || !active || !joined) return@launch
            Log.w(TAG, "sfu never reissued the rejected offer; reconnecting")
            recoverTransport(gen)
        }
    }

    private fun clearOfferReissueDeadline() {
        offerReissueJob?.cancel()
        offerReissueJob = null
    }

    private fun armModeratorMuteCheck() {
        if (moderatorMuteJob?.isActive == true) return
        val gen = connectionGen
        moderatorMuteJob = scope?.launch {
            delay(MODERATOR_MUTE_WINDOW_MS)
            moderatorMuteJob = null
            if (gen != connectionGen || !active || !micEnabled) return@launch
            Log.w(TAG, "a channel moderator muted this peer; turning the local mic off")
            onMutedByModerator?.invoke()
        }
    }

    private fun clearModeratorMuteCheck() {
        moderatorMuteJob?.cancel()
        moderatorMuteJob = null
    }

    private fun armTransportWatchdog(gen: Int) {
        if (transportWatchdogJob?.isActive == true) return
        if (peerConnection == null) return
        if (transportStatus.connection == PeerConnection.PeerConnectionState.CONNECTED) return
        transportWatchdogJob = scope?.launch {
            delay(DTLS_CONNECT_DEADLINE_MS)
            transportWatchdogJob = null
            if (gen != connectionGen || !active) return@launch
            if (peerConnection == null) return@launch
            if (transportStatus.connection == PeerConnection.PeerConnectionState.CONNECTED) return@launch
            Log.w(TAG, "dtls never completed after ice connected; restarting the sfu session")
            recoverTransport(gen)
        }
    }

    private fun clearTransportWatchdog() {
        transportWatchdogJob?.cancel()
        transportWatchdogJob = null
    }

    private fun onOffer(generation: Long, sdp: String, msidOwners: List<MsidOwner>) {
        applyMsidOwners(msidOwners)
        val gen = connectionGen
        scope?.launch { negotiate(generation, sdp, gen) }
    }

    private suspend fun negotiate(firstGeneration: Long, firstSdp: String, gen: Int) {
        if (gen != connectionGen) return
        if (negotiating) {
            pendingOffer = firstGeneration to firstSdp
            return
        }
        negotiating = true
        var offer: Pair<Long, String>? = firstGeneration to firstSdp
        while (offer != null) {
            if (gen != connectionGen) return
            val (generation, sdp) = offer
            val pc = peerConnection ?: break
            val tracks = remoteTracks
            try {
                val stableSdp = withContext(webRtcDispatcher) {
                    stabilizeInactiveVideoSections(sdp, pc.remoteDescription?.description)
                }
                if (!isCurrentConnection(pc, gen)) return
                awaitSetRemote(pc, SessionDescription(SessionDescription.Type.OFFER, stableSdp))
                if (!isCurrentConnection(pc, gen)) return
                if (transceiverCache.isEmpty()) {
                    val offeredTransceivers = withContext(webRtcDispatcher) { readTransceivers(pc, gen) }
                    if (!isCurrentConnection(pc, gen)) return
                    transceiverCache = offeredTransceivers
                }
                attachLocalTracks(pc)
                val answer = awaitCreateAnswer(pc)
                if (!isCurrentConnection(pc, gen)) return
                val audience = role == SfuRole.AUDIENCE
                val (answerMessage, directions) = withContext(webRtcDispatcher) {
                    val message = JSONObject()
                        .put("type", "answer")
                        .put("offer_generation", generation)
                        .put("sdp", patchAnswerForSfu(answer.description, audience))
                        .toString()
                    message to parseNegotiatedDirections(answer.description)
                }
                if (!isCurrentConnection(pc, gen)) return
                sendText(answerMessage)
                awaitSetLocal(pc, SessionDescription(SessionDescription.Type.ANSWER, answer.description))
                if (!isCurrentConnection(pc, gen)) return
                val untracked = untrackedReceiverMids(directions, tracks)
                if (transceiverCache.isEmpty() || untracked.isNotEmpty()) {
                    val (transceivers, recovered) = withContext(webRtcDispatcher) {
                        val read = readTransceivers(pc, gen)
                        read to recoverUntrackedReceivers(read, untracked, tracks)
                    }
                    if (!isCurrentConnection(pc, gen)) return
                    transceiverCache = transceivers
                    if (recovered > 0) Log.w(TAG, "onTrack did not report $recovered remote tracks; recovered them from transceivers")
                }
                negotiatedDirections = directions
                refreshRemoteSnapshot()
                syncRemoteMedia()
            } catch (e: Exception) {
                if (!isCurrentConnection(pc, gen)) return
                recoverTransport(gen)
                return
            }
            offer = pendingOffer
            pendingOffer = null
            if (offer != null) delay(50)
        }
        negotiating = false
        syncPendingRemoteTracks()
        requestMissingScreenKeyframes()
    }

    private fun isCurrentConnection(pc: PeerConnection, gen: Int): Boolean =
        gen == connectionGen && peerConnection === pc

    private val shouldSendAudio: Boolean
        get() = if (role == SfuRole.AUDIENCE) pttActive else micEnabled

    private fun synchronizeLocalAudioTrack(): Boolean {
        if (!active) return false
        createLocalAudioTrack()
        val audio = localAudioTrack ?: return false
        if (audio.state() != MediaStreamTrack.State.LIVE) return false
        val enabled = shouldSendAudio
        audio.setEnabled(enabled)
        noiseProcessor?.setCaptureActive(enabled)
        if (audio.enabled() != enabled) return false
        val tc = findTransceiver(MID_AUDIO, "audio") ?: return false
        if (tc.sender.track() !== audio && !tc.sender.setTrack(audio, false)) return false
        // A muted track sends silence; keeping its encoding active avoids ADM cold starts.
        val warm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val applied = setAudioEncodingActive(tc.sender, enabled || warm)
        updateAudioSendProbe()
        return applied || !enabled
    }

    private fun setAudioEncodingActive(sender: RtpSender, sending: Boolean): Boolean {
        val parameters = sender.parameters
        if (parameters.encodings.all { it.active == sending }) return true
        parameters.encodings.forEach { it.active = sending }
        return sender.setParameters(parameters)
    }

    private fun readTransceivers(pc: PeerConnection, gen: Int): List<RtpTransceiver> {
        val read = pc.transceivers
        localAudioSenderRef = read.firstOrNull { it.mid == MID_AUDIO }?.let { LocalAudioSender(gen, it.sender) }
        return read
    }

    private fun applyLocalAudio() {
        if (!active) return
        val audio = localAudioTrack
        if (audio == null) {
            repairLocalAudio()
            return
        }
        val gen = connectionGen
        val enabled = shouldSendAudio
        if (!enabled) stopAudioSendProbe()
        val attach = localTracksAdded
        appScope.launch(webRtcDispatcher) {
            if (!active || gen != connectionGen || audio !== localAudioTrack || enabled != shouldSendAudio) return@launch
            val outcome = runCatching {
                if (audio.state() != MediaStreamTrack.State.LIVE) {
                    LocalAudioOutcome.TRACK_ENDED
                } else {
                    audio.setEnabled(enabled)
                    noiseProcessor?.setCaptureActive(enabled)
                    val sender = localAudioSenderRef?.takeIf { it.gen == gen }?.sender
                    when {
                        audio.enabled() != enabled -> LocalAudioOutcome.FAILED
                        !attach || gen != connectionGen -> LocalAudioOutcome.APPLIED
                        sender == null -> LocalAudioOutcome.FAILED
                        sender.track() !== audio && !sender.setTrack(audio, false) -> LocalAudioOutcome.FAILED
                        setAudioEncodingActive(sender, enabled ||
                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) || !enabled -> LocalAudioOutcome.APPLIED
                        else -> LocalAudioOutcome.FAILED
                    }
                }
            }.getOrDefault(LocalAudioOutcome.FAILED)
            if (outcome == LocalAudioOutcome.APPLIED) {
                appScope.launch(mainDispatcher) {
                    if (active && gen == connectionGen && audio === localAudioTrack) updateAudioSendProbe()
                }
                return@launch
            }
            appScope.launch(mainDispatcher) {
                if (!active || gen != connectionGen || audio !== localAudioTrack) return@launch
                repairLocalAudio()
            }
        }
    }

    private fun repairLocalAudio() {
        val attached = synchronizeLocalAudioTrack()
        if (localTracksAdded && !attached) recoverTransport(connectionGen)
    }

    private fun setAudioSending(sending: Boolean) {
        if (isAudioSending == sending) return
        isAudioSending = sending
        onAudioSendingChanged?.invoke(sending)
    }

    private fun stopAudioSendProbe() {
        audioSendProbeEpoch++
        audioSendProbeJob?.cancel()
        audioSendProbeJob = null
        audioSendProbeInFlight = false
        audioSendProbePackets = null
        audioSendProbeFrames = null
        setAudioSending(false)
    }

    private fun updateAudioSendProbe() {
        val pc = peerConnection
        if (!active || connectionState != SfuConnectionState.CONNECTED || !shouldSendAudio || pc == null) {
            stopAudioSendProbe()
            return
        }
        if (isAudioSending || audioSendProbeJob != null) return
        val epoch = ++audioSendProbeEpoch
        val gen = connectionGen
        audioSendProbeInFlight = false
        audioSendProbePackets = null
        audioSendProbeFrames = null
        audioSendProbeJob = scope?.launch {
            var attempt = 0
            while (isActive) {
                if (!isActive || !active || !shouldSendAudio || !isCurrentConnection(pc, gen) ||
                    audioSendProbeEpoch != epoch || isAudioSending) break
                if (!audioSendProbeInFlight) {
                    audioSendProbeInFlight = true
                    appScope.launch(webRtcDispatcher) statsRequest@{
                        if (gen != connectionGen) return@statsRequest
                        pc.getStats { report ->
                            val packets = report.statsMap.values.filter {
                                it.type == "outbound-rtp" && (it.members["kind"] ?: it.members["mediaType"]) == "audio" &&
                                    (it.members["mid"] == null || it.members["mid"] == MID_AUDIO)
                            }.sumOf { (it.members["packetsSent"] as? Number)?.toLong() ?: 0L }
                            appScope.launch(mainDispatcher) statsResult@{
                                if (!active || !shouldSendAudio || !isCurrentConnection(pc, gen) || audioSendProbeEpoch != epoch) return@statsResult
                                audioSendProbeInFlight = false
                                val before = audioSendProbePackets
                                val captured = noiseProcessor?.capturedFrameCount ?: 0L
                                val previousFrames = audioSendProbeFrames
                                if (before != null && previousFrames != null && packets > before && captured > previousFrames) setAudioSending(true)
                                audioSendProbePackets = packets
                                audioSendProbeFrames = captured
                            }
                        }
                    }
                }
                // Cold capture/permission/negotiation can exceed three seconds.
                // Keep checking while the mic is requested; never infer readiness from a timer.
                delay(if (attempt++ < 30) 100L else 250L)
            }
            if (audioSendProbeEpoch == epoch) audioSendProbeJob = null
        }
    }

    private fun attachLocalTracks(pc: PeerConnection) {
        check(synchronizeLocalAudioTrack()) { "SFU audio sender unavailable" }
        val tc = checkNotNull(findTransceiver(MID_AUDIO, "audio"))
        check(tc.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
        if (role == SfuRole.SPEAKER) {
            if (!localTracksAdded) prepareVideoSender(pc)
            if (screenOn) reattachScreen(pc)
        }
        localTracksAdded = true
    }

    private suspend fun handleRoleChanged(newRole: SfuRole) {
        if (role == newRole) return
        role = newRole
        createLocalAudioTrack()
        val pc = peerConnection
        val audio = localAudioTrack
        val tc = if (pc != null) findTransceiver(MID_AUDIO, "audio") else null
        if (newRole == SfuRole.SPEAKER) {
            micEnabled = true
            audio?.setEnabled(true)
            if (tc != null && audio != null) {
                tc.sender.setTrack(audio, false)
                setAudioEncodingActive(tc.sender, true)
                tc.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            }
            pttActive = true
            onPushToTalkActive?.invoke(true)
        } else {
            micEnabled = false
            pttRequested = false
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

    private fun prepareVideoSender(addingTo: PeerConnection? = null) {
        if (cameraTrack == null) {
            val source = factory.createVideoSource(false)
            val track = factory.createVideoTrack("sfu_camera", source)
            track.setEnabled(cameraEnabled)
            cameraSource = source
            cameraTrack = track
        }
        val track = cameraTrack ?: return
        val tc = findTransceiver(MID_CAMERA, "video")
        if (tc != null) {
            if (tc.sender.track() !== track) {
                tc.sender.setTrack(track, false)
                applyCameraTier(tc.sender, cameraTierIndex)
                tc.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            }
            return
        }
        val pc = addingTo ?: return
        val sender = runCatching { pc.addTrack(track, listOf("sfu")) }
            .onFailure { Log.w(TAG, "camera addTrack failed: ${it.message}") }
            .getOrNull() ?: return
        applyCameraTier(sender, cameraTierIndex)
    }

    private fun applyCameraTier(sender: RtpSender, tierIndex: Int) {
        try {
            val tier = CAMERA_TIERS.getOrElse(tierIndex) { CAMERA_TIERS[0] }
            val params = sender.parameters
            params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            for (encoding in params.encodings) {
                encoding.maxBitrateBps = if (tier.uncapped) null else tier.maxBitrateBps
                encoding.maxFramerate = if (tier.uncapped) null else tier.maxFps
                encoding.scaleResolutionDownBy = if (tier.uncapped) null else tier.scaleDown
            }
            sender.parameters = params
        } catch (e: Exception) {
            Log.w(TAG, "camera tier not applied", e)
        }
    }

    private fun activeCameraCount(): Int =
        remote.values.count { it.cameraActive } + if (cameraEnabled) 1 else 0

    private fun scheduleCameraTier() {
        val next = resolveCameraTier(activeCameraCount(), cameraTierIndex)
        if (next == cameraTierIndex) return
        val delayMs = if (next > cameraTierIndex) CAMERA_TIER_DOWNGRADE_MS else CAMERA_TIER_UPGRADE_MS
        cameraTierJob?.cancel()
        cameraTierJob = scope?.launch {
            delay(delayMs)
            cameraTierIndex = next
            findTransceiver(MID_CAMERA, "video")?.sender?.let { applyCameraTier(it, next) }
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
        if (!active || screenTrack != null) return
        val captureToken = ++screenCaptureToken
        val capturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
            override fun onStop() {
                appScope.launch(mainDispatcher) {
                    if (active && captureToken == screenCaptureToken) setScreenShare(false, null)
                }
            }
        })
        val source = factory.createVideoSource(true)
        val helper = SurfaceTextureHelper.create("SfuScreenThread", webRtcInfra.eglContext) ?: return
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_FPS)
        val track = factory.createVideoTrack("sfu_screen", source)
        track.setEnabled(true)
        screenCapturer = capturer
        screenSource = source
        screenHelper = helper
        screenTrack = track
        onLocalScreenTrack?.invoke(track)
        if (peerConnection != null) reattachScreen()
    }

    private fun reattachScreen(addingTo: PeerConnection? = null) {
        val track = screenTrack ?: return
        val tc = findTransceiver(MID_SCREEN, "video")
        if (tc != null) {
            if (tc.sender.track() !== track) {
                tc.sender.setTrack(track, false)
                tc.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            }
            return
        }
        val pc = addingTo ?: return
        runCatching { pc.addTrack(track, listOf("sfu")) }
            .onFailure { Log.w(TAG, "screen addTrack failed: ${it.message}") }
    }

    private fun stopScreenShare() {
        screenCaptureToken++
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

    private fun applyPeers(members: org.json.JSONArray?): Boolean {
        members ?: return false
        var ownershipChanged = false
        val participantIdByPeerId = HashMap<String, String>(remote.size)
        for ((id, entry) in remote) {
            entry.peerId?.let { participantIdByPeerId.putIfAbsent(it, id) }
        }
        for (i in 0 until members.length()) {
            val peer = members.optJSONObject(i) ?: continue
            val peerId = peer.opt("peer_id")?.toString() ?: continue
            val state = memberByPeerId.getOrPut(peerId) { MemberState() }
            peer.optString("user_id").takeIf { it.isNotEmpty() }?.let { state.userId = it }
            if (peer.has("role")) state.role = SfuRole.fromWire(peer.optString("role"))
            if (peer.has("is_mute")) state.muted = peer.optBoolean("is_mute")
            if (peer.has("camera_active")) state.cameraActive = peer.optBoolean("camera_active")
            if (peer.has("screen_active")) {
                state.screenActive = peer.optBoolean("screen_active") && peer.optBoolean("screen_requested", true)
            }
            val mids = listOf(
                peer.opt("mid_audio"), peer.opt("mid_video"), peer.opt("mid_screen")
            ).mapNotNull { it?.toString() }.filter { it.isNotEmpty() && it != "0" }
            for (mid in mids) {
                if (claimMid(mid, peerId)) ownershipChanged = true
            }
            val existing = participantIdByPeerId[peerId]
            val participantId = existing ?: mids.firstOrNull()?.let { remoteParticipantId(it) } ?: continue
            val entry = remote.getOrPut(participantId) { RemoteEntry(participantId) }
            entry.peerId?.takeIf { it != peerId && participantIdByPeerId[it] == participantId }
                ?.let { participantIdByPeerId.remove(it) }
            applyMemberState(entry, peerId)
            participantIdByPeerId[peerId] = participantId
        }
        scheduleCameraTier()
        return ownershipChanged
    }

    private fun claimMid(mid: String, peerId: String): Boolean {
        val changed = peerIdByMid.put(mid, peerId) != peerId
        val state = memberByPeerId[peerId]
        state?.userId?.let { userIdByMid[mid] = it }
        state?.role?.let { roleByMid[mid] = it }
        return changed
    }

    private fun applyMemberState(entry: RemoteEntry, peerId: String) {
        entry.peerId = peerId
        val state = memberByPeerId[peerId] ?: return
        state.userId?.let { entry.userId = it }
        state.role?.let { entry.role = it }
        state.muted?.let { entry.muted = it }
        state.cameraActive?.let { entry.cameraActive = it }
        state.screenActive?.let {
            val started = it && !entry.screenActive
            if (started) entry.screenActiveSinceMs = SystemClock.elapsedRealtime()
            entry.screenActive = it
            if (started) entry.screen?.let { track -> VideoTrackLastFrameStore.observe(track) }
        }
    }

    private fun newcomerUserId(peer: JSONObject): String? {
        if (!admitted) return null
        val peerId = peer.opt("peer_id")?.toString() ?: return null
        if (peerId == selfPeerId || peerId in memberByPeerId) return null
        return peer.optString("user_id").takeIf { it.isNotEmpty() }
    }

    private fun handlePeerLeft(msg: JSONObject) {
        val peerId = msg.opt("peer_id")?.toString()
        val mids = listOf(
            msg.opt("mid_audio"), msg.opt("mid_video"), msg.opt("mid_screen")
        ).mapNotNull { it?.toString() }.filter { it.isNotEmpty() && it != "0" }
        if (peerId != null) memberByPeerId.remove(peerId)
        for (mid in mids) {
            val owner = peerIdByMid[mid]
            if (owner != null && peerId != null && owner != peerId) continue
            peerIdByMid.remove(mid)
            userIdByMid.remove(mid)
            roleByMid.remove(mid)
            remote.remove(remoteParticipantId(mid))
        }
    }

    private fun syncPendingRemoteTracks() {
        if (negotiating || !active || peerConnection == null) return
        if (remoteTracks.events.get() == snapshotTrackEvents) return
        refreshRemoteSnapshot()
        syncRemoteMedia()
    }

    private fun refreshRemoteSnapshot() {
        val tracks = remoteTracks
        val trackEvents = tracks.events.get()
        remoteSnapshot = negotiatedDirections.map { entry ->
            val receiving = entry.direction != RtpTransceiver.RtpTransceiverDirection.INACTIVE &&
                entry.direction != RtpTransceiver.RtpTransceiverDirection.STOPPED
            RemoteTransceiverSnapshot(entry.mid, entry.direction, if (receiving) tracks.byMid[entry.mid] else null)
        }
        snapshotTrackEvents = trackEvents
    }

    private fun untrackedReceiverMids(directions: List<SdpMidDirection>, tracks: RemoteTrackRegistry): Set<String> {
        val untracked = HashSet<String>()
        for (entry in directions) {
            if (!isRemoteMid(entry.mid)) continue
            val receivesMedia = entry.direction == RtpTransceiver.RtpTransceiverDirection.RECV_ONLY ||
                entry.direction == RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            if (receivesMedia && tracks.byMid[entry.mid] == null) untracked.add(entry.mid)
        }
        return untracked
    }

    private fun recoverUntrackedReceivers(
        transceivers: List<RtpTransceiver>,
        untracked: Set<String>,
        tracks: RemoteTrackRegistry,
    ): Int {
        if (untracked.isEmpty()) return 0
        var recovered = 0
        for (tc in transceivers) {
            val mid = tc.mid ?: continue
            if (mid !in untracked) continue
            val track = tc.receiver?.track() ?: continue
            if (tracks.byMid.putIfAbsent(mid, track) == null) recovered++
        }
        return recovered
    }

    private fun disposePeerConnection(pc: PeerConnection?): Job? {
        if (pc == null) return null
        return nativeCleanup.enqueue {
            try { pc.close() } finally { pc.dispose() }
        }
    }

    private suspend fun awaitNativeCleanup(gen: Int): Boolean {
        val started = SystemClock.elapsedRealtime()
        val completed = nativeCleanup.awaitCompletion(NATIVE_CLEANUP_TIMEOUT_MS)
        if (!active || gen != connectionGen) return false
        val elapsed = SystemClock.elapsedRealtime() - started
        if (!completed) {
            Log.w(TAG, "native cleanup did not finish safely; cleanupMs=$elapsed")
            leave()
            emitState(SfuConnectionState.FAILED)
        }
        return completed
    }

    private fun syncRemoteMedia() {
        for (item in remoteSnapshot) {
            val mid = item.mid ?: continue
            if (mid == MID_AUDIO || mid == MID_CAMERA || mid == MID_SCREEN) continue
            val direction = item.direction
            val id = remoteParticipantId(mid)
            val kind = remoteKind(mid)
            if (direction == RtpTransceiver.RtpTransceiverDirection.INACTIVE ||
                direction == RtpTransceiver.RtpTransceiverDirection.STOPPED
            ) {
                clearRemoteKind(id, kind)
                continue
            }
            val track = item.track ?: continue
            val ownerUserId = userIdByMid[mid]
            val ownerPeerId = peerIdByMid[mid]
            if (ownerUserId == null && ownerPeerId == null) {
                clearRemoteKind(id, kind)
                continue
            }
            val entry = remote.getOrPut(id) { RemoteEntry(id) }
            ownerUserId?.let { entry.userId = it }
            ownerPeerId?.let { applyMemberState(entry, it) }
            roleByMid[mid]?.let { entry.role = it }
            when {
                track is AudioTrack -> entry.audio = track
                kind == "camera" && track is VideoTrack -> entry.video = track
                kind == "screen" && track is VideoTrack -> {
                    entry.screen = track
                    if (entry.screenActive) VideoTrackLastFrameStore.observe(track)
                }
            }
            if (entry.audio == null && entry.video == null && entry.screen == null) {
                remote.remove(id)
            }
        }
        releaseRetiringPeerConnection()
        emitParticipants()
        requestMissingScreenKeyframes()
    }

    private fun clearRemoteKind(id: String, kind: String?) {
        val entry = remote[id] ?: return
        when (kind) {
            "audio" -> entry.audio = null
            "camera" -> entry.video = null
            "screen" -> entry.screen = null
        }
        if (entry.audio == null && entry.video == null && entry.screen == null) {
            remote.remove(id)
        }
    }

    private fun releaseRetiringPeerConnection() {
        if (remote.isEmpty()) return
        val previous = retiringPeerConnection ?: return
        retiringPeerConnection = null
        retiringCloseJob?.cancel()
        retiringCloseJob = null
        disposePeerConnection(previous)
    }

    private fun scheduleRetiringPeerConnectionClose() {
        retiringCloseJob?.cancel()
        val retiring = retiringPeerConnection
        if (retiring == null) {
            retiringCloseJob = null
            return
        }
        retiringCloseJob = scope?.launch {
            delay(RETIRING_PEER_CONNECTION_GRACE_MS)
            retiringCloseJob = null
            if (retiringPeerConnection !== retiring) return@launch
            retiringPeerConnection = null
            disposePeerConnection(retiring)
        }
    }

    private fun recoverTransport(gen: Int) {
        if (!active || gen != connectionGen || transportRecoveryJob != null || nativeStartupJob != null) return
        val roomScope = scope ?: return
        val call = callIdentity ?: return
        discardFailedTransport()
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            leave()
            emitState(SfuConnectionState.FAILED)
            return
        }
        val retryGen = connectionGen
        val waitMs = reconnectDelayMs(reconnectAttempts, Random.nextDouble())
        transportRecoveryJob = roomScope.launch {
            delay(waitMs)
            if (!isActive || !active || retryGen != connectionGen) return@launch
            if (!networkMonitor.isOnline.value) {
                transportRecoveryJob = null
                return@launch
            }
            if (tokenNeedsRefresh() && tokenRefreshes < MAX_TOKEN_REFRESHES) {
                val fresh = runCatching { tokenProvider?.invoke(call.channelId, call.clanId) }.getOrNull()
                if (!isActive || !active || retryGen != connectionGen) return@launch
                tokenRefreshes++
                if (!fresh.isNullOrEmpty()) {
                    token = fresh
                    tokenRejected = false
                }
            }
            if (tokenRejected) {
                leave()
                emitState(SfuConnectionState.FAILED)
                return@launch
            }
            transportRecoveryJob = null
            if (networkMonitor.isOnline.value) openConnection(initial = false)
        }
        emitState(SfuConnectionState.DISCONNECTED)
        if (!active || retryGen != connectionGen) return
        emitParticipants()
        lastSpeakingIds = emptySet()
        onSpeaking?.invoke(emptySet())
        onPushToTalkActive?.invoke(false)
    }

    private fun discardFailedTransport() {
        connectionGen++
        socketOpen = false
        connecting = false
        mediaConnected = false
        resetAudioFlow()
        stateRestored = false
        admitted = false
        selfPeerId = null
        readiness = SfuConnectionReadiness(requiresVoiceJoined = !hasReachedConnected)
        negotiating = false
        pendingOffer = null
        localTracksAdded = false
        webSocket?.cancel()
        webSocket = null
        clearOfferReissueDeadline()
        clearTransportWatchdog()
        clearConnectionDeadline()
        clearModeratorMuteCheck()
        clearDeferredRestart()
        iceRecoveryJob?.cancel()
        iceRecoveryJob = null
        healthySessionResetJob?.cancel()
        healthySessionResetJob = null
        cameraTierJob?.cancel()
        cameraTierJob = null
        retiringCloseJob?.cancel()
        retiringCloseJob = null
        resetVideoKeyframeRequests()
        videoExposures.clear()
        val failed = peerConnection
        val retiring = retiringPeerConnection
        peerConnection = null
        retiringPeerConnection = null
        transceiverCache = emptyList()
        localAudioSenderRef = null
        remoteSnapshot = emptyList()
        negotiatedDirections = emptyList()
        remoteTracks = RemoteTrackRegistry()
        snapshotTrackEvents = -1
        userIdByMid.clear()
        peerIdByMid.clear()
        roleByMid.clear()
        memberByPeerId.clear()
        remote.clear()
        participantActionCallbacks.clear()
        localAudioTrack?.setEnabled(false)
        pttActive = false
        disposePeerConnection(failed)
        disposePeerConnection(retiring)
    }

    private fun scheduleHealthyConnectionReset(gen: Int) {
        if (healthySessionResetJob != null) return
        healthySessionResetJob = scope?.launch {
            delay(HEALTHY_SESSION_MS)
            if (!isActive || gen != connectionGen) return@launch
            healthySessionResetJob = null
            if (active && mediaConnected && socketOpen && peerConnection != null &&
                transportStatus.connection == PeerConnection.PeerConnectionState.CONNECTED
            ) {
                reconnectAttempts = 0
            }
        }
    }

    private fun restartSession(reason: String) {
        if (!active || !joined || connecting || transportRecoveryJob != null) return
        val sinceLastOpenMs = SystemClock.elapsedRealtime() - lastConnectionOpenedAtMs
        if (lastConnectionOpenedAtMs > 0L && sinceLastOpenMs < MIN_SESSION_RESTART_SPACING_MS) {
            deferRestart(reason, MIN_SESSION_RESTART_SPACING_MS - sinceLastOpenMs)
            return
        }
        iceRecoveryJob?.cancel()
        iceRecoveryJob = null
        if (tokenNeedsRefresh()) {
            recoverTransport(connectionGen)
            return
        }
        openConnection(initial = false)
    }

    private fun deferRestart(reason: String, delayMs: Long) {
        if (deferredRestartJob?.isActive == true) return
        val gen = connectionGen
        deferredRestartJob = scope?.launch {
            delay(delayMs)
            deferredRestartJob = null
            if (gen != connectionGen) return@launch
            restartSession(reason)
        }
    }

    private fun clearDeferredRestart() {
        deferredRestartJob?.cancel()
        deferredRestartJob = null
    }

    private fun tokenNeedsRefresh(): Boolean {
        if (tokenRejected) return true
        val secondsLeft = tokenSecondsLeft(token) ?: return true
        return secondsLeft <= TOKEN_EXPIRY_MARGIN_SECONDS
    }

    private fun emitParticipants() {
        val list = remote.values.map {
            SfuParticipant(
                id = it.id,
                userId = it.userId,
                peerId = it.peerId,
                role = it.role,
                muted = it.muted,
                audio = it.audio,
                video = it.video,
                screen = it.screen,
                screenActive = it.screenActive,
                cameraActive = it.cameraActive,
            )
        }
        if (list == lastParticipants) return
        lastParticipants = list
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

    private fun applyMsidOwners(owners: List<MsidOwner>) {
        for (owner in owners) {
            userIdByMid[owner.mid] = owner.userId
            val pid = owner.peerId
            if (pid != null && pid != "0") claimMid(owner.mid, pid)
        }
    }

    private fun patchAnswerForSfu(sdp: String, audience: Boolean): String {
        if (!audience) return sdp
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

    /** Source tokens keep tile, focused view and overlay visibility independent. */
    fun setVideoTrackVisible(track: VideoTrack, visible: Boolean, source: String, focused: Boolean = false) {
        if (visible) {
            val previous = videoExposures.put(source, VideoExposure(track, focused))
            if (previous?.track !== track || previous?.focused != focused) requestVideoKeyframeIfNeeded(track)
        } else if (videoExposures[source]?.track === track) {
            videoExposures.remove(source)
            if (videoPriority(track) == null) {
                queuedVideoKeyframes.filterValues { it.track === track }.keys.toList().forEach {
                    queuedVideoKeyframes.remove(it)?.detach()
                }
                videoRecoveryChecks.filterValues { it.track === track }.keys.toList().forEach {
                    videoRecoveryChecks.remove(it)?.detach()
                }
                screenKeyframeRequests.values.filter { it.track === track }.forEach { it.detach() }
            }
        }
        requestMissingScreenKeyframes()
    }

    private fun videoPriority(track: VideoTrack): Int? {
        val exposures = videoExposures.values.filter { it.track === track }
        if (exposures.isEmpty()) return null
        return if (exposures.any { it.focused }) 0 else 1
    }

    fun requestVideoKeyframe(track: VideoTrack) {
        if (!active || videoPriority(track) == null) return
        val (kind, publisherId) = videoOwner(track) ?: return
        val since = videoFrameRequiredSince(kind, publisherId)
        if ((VideoTrackLastFrameStore.lastFrameReceivedAt(track) ?: -1) >= since) return
        enqueueVideoKeyframe(track, kind, publisherId, SystemClock.elapsedRealtime(), since)
        requestMissingScreenKeyframes()
    }

    fun requestVideoKeyframeIfNeeded(track: VideoTrack) {
        if (!active) return
        val priority = videoPriority(track) ?: return
        val (kind, publisherId) = videoOwner(track) ?: return
        val since = videoFrameRequiredSince(kind, publisherId)
        if ((VideoTrackLastFrameStore.observe(track)?.lastFrameReceivedMs ?: -1) >= since) return
        val key = "$kind|$publisherId"
        if (videoRecoveryChecks[key]?.track?.let { it !== track } == true) videoRecoveryChecks.remove(key)?.detach()
        val now = SystemClock.elapsedRealtime()
        if (priority == 0 && (videoRecoveryChecks[key]?.startedAtMs ?: since) <= since) {
            videoRecoveryChecks.remove(key)?.detach()
            enqueueVideoKeyframe(track, kind, publisherId, now, since)
        } else if (key !in videoRecoveryChecks) {
            queueVideoRecoveryCheck(track, kind, publisherId, since,
                now + VISIBLE_VIDEO_GRACE_MS + kotlin.random.Random.nextLong(151))
        }
        requestMissingScreenKeyframes()
    }

    private fun videoFrameRequiredSince(kind: String, publisherId: Long): Long {
        val entry = remote.values.firstOrNull { it.peerId == publisherId.toString() }
        return maxOf(lastConnectionOpenedAtMs, if (kind == "screen") entry?.screenActiveSinceMs ?: 0 else 0)
    }

    private fun enqueueVideoKeyframe(track: VideoTrack, kind: String, publisherId: Long, readyAt: Long, frameSince: Long) {
        val key = "$kind|$publisherId"
        val queued = queuedVideoKeyframes[key]
        if (queued != null && queued.track === track) {
            queued.readyAtMs = minOf(queued.readyAtMs, readyAt)
            queued.frameSinceMs = maxOf(queued.frameSinceMs, frameSince)
        } else {
            queuedVideoKeyframes.remove(key)?.detach()
            val created = QueuedKeyframe(track, kind, publisherId, readyAt, frameSince)
            if (runCatching { track.addSink(created) }.isFailure) return
            created.lastFrameMs = maxOf(created.lastFrameMs, VideoTrackLastFrameStore.lastFrameReceivedAt(track) ?: 0,
                videoRecoveryChecks[key]?.takeIf { it.track === track }?.lastFrameMs ?: 0)
            queuedVideoKeyframes[key] = created
        }
    }

    private fun videoOwner(track: VideoTrack): Pair<String, Long>? {
        val entry = remote.values.firstOrNull { it.screen === track || it.video === track } ?: return null
        if (if (entry.screen === track) !entry.screenActive else !entry.cameraActive) return null
        val id = entry.peerId?.toLongOrNull()?.takeIf { it in 1..0xFFFF_FFFFL } ?: return null
        return (if (entry.screen === track) "screen" else "camera") to id
    }

    private fun queueVideoRecoveryCheck(track: VideoTrack, kind: String, publisherId: Long,
        startedAt: Long = SystemClock.elapsedRealtime(), checkAt: Long = startedAt + FOREGROUND_VIDEO_GRACE_MS) {
        val key = "$kind|$publisherId"
        val check = VideoRecoveryCheck(track, kind, publisherId, startedAt, checkAt)
        if (runCatching { track.addSink(check) }.isFailure) return
        videoRecoveryChecks.remove(key)?.detach()
        videoRecoveryChecks[key] = check
    }

    private fun checkVideoAfterForeground() {
        val backgroundedAt = videoBackgroundedAtMs ?: return
        videoBackgroundedAtMs = null
        suspendVideoKeyframeQueue()
        val now = SystemClock.elapsedRealtime()
        val briefBackground = videoVisible && now - backgroundedAt < HIDDEN_VIDEO_PAUSE_DELAY_MS
        // Install probes BEFORE visibility=true, so BE's cached replay counts as recovery.
        for (entry in remote.values) {
            val id = entry.peerId?.toLongOrNull()?.takeIf { it in 1..0xFFFF_FFFFL } ?: continue
            if (entry.screenActive) entry.screen?.takeIf { videoPriority(it) != null }?.let { track ->
                val frameAt = VideoTrackLastFrameStore.lastFrameReceivedAt(track)
                val satisfied = screenKeyframeRequests[entry.peerId]?.let { it.track === track && it.satisfied } == true
                val fresh = frameAt != null && frameAt >= backgroundedAt && now - frameAt < FOREGROUND_VIDEO_GRACE_MS
                if (!(briefBackground && satisfied) && !fresh) queueVideoRecoveryCheck(track, "screen", id)
            }
            if (entry.cameraActive) entry.video?.takeIf { videoPriority(it) != null }?.let { track ->
                val frameAt = VideoTrackLastFrameStore.lastFrameReceivedAt(track)
                if (frameAt == null || frameAt < backgroundedAt || now - frameAt >= FOREGROUND_VIDEO_GRACE_MS) {
                    queueVideoRecoveryCheck(track, "camera", id)
                }
            }
        }
    }

    private fun canRequestVideoKeyframes(): Boolean =
        active && joined && socketOpen && webSocket != null && mediaConnected && !negotiating && videoVisible && appVisible

    private fun sendVideoKeyframeRequest(kind: String, publisherId: Long): Boolean {
        if (!canRequestVideoKeyframes()) return false
        val socket = webSocket ?: return false
        val now = SystemClock.elapsedRealtime()
        val key = "$kind|$publisherId"
        val last = lastVideoKeyframeRequests[key] ?: -KEYFRAME_MIN_INTERVAL_MS
        if (now - last < KEYFRAME_MIN_INTERVAL_MS) return false
        val payload = JSONObject().put("type", "request_keyframe")
            .put("kind", kind).put("publisher_id", publisherId)
        lastVideoKeyframeRequests[key] = now
        if (!socket.send(payload.toString())) return false
        lastKeyframeRequestMs = now
        return true
    }

    private fun recoverVideoIfNeeded(): Long? {
        val now = SystemClock.elapsedRealtime()
        var nextCheckMs: Long? = null
        for ((key, check) in videoRecoveryChecks.toMap()) {
            val owner = videoOwner(check.track)
            if (videoPriority(check.track) == null || owner != (check.kind to check.publisherId) || check.lastFrameMs >= check.startedAtMs) {
                videoRecoveryChecks.remove(key)?.detach()
                continue
            }
            if (now >= check.checkAtMs) {
                enqueueVideoKeyframe(check.track, check.kind, check.publisherId, check.checkAtMs, check.startedAtMs)
                videoRecoveryChecks.remove(key)?.detach()
                if (check.kind == "screen") screenRecoveryStartedAt[check.publisherId.toString()] = check.startedAtMs
            } else {
                nextCheckMs = minOf(nextCheckMs ?: check.checkAtMs, check.checkAtMs)
            }
        }
        return nextCheckMs
    }

    private fun drainVideoKeyframes(): Long? {
        val now = SystemClock.elapsedRealtime()
        for ((key, queued) in queuedVideoKeyframes.toMap()) {
            val request = screenKeyframeRequests[queued.publisherId.toString()]
            if (videoPriority(queued.track) == null || videoOwner(queued.track) != (queued.kind to queued.publisherId) ||
                maxOf(queued.lastFrameMs, VideoTrackLastFrameStore.lastFrameReceivedAt(queued.track) ?: 0) >= queued.frameSinceMs ||
                (queued.kind == "screen" && request?.track === queued.track && !request.satisfied && request.attempts > SCREEN_KEYFRAME_RETRY_DELAYS_MS.size)) {
                queuedVideoKeyframes.remove(key)?.detach()
            }
        }
        val ordered = queuedVideoKeyframes.entries.sortedWith(
            compareBy<Map.Entry<String, QueuedKeyframe>> { videoPriority(it.value.track) ?: 2 }
                .thenBy { it.value.readyAtMs }.thenBy { it.key })
        var nextAt: Long? = null
        for ((key, queued) in ordered) {
            val due = maxOf(queued.readyAtMs, nextVideoKeyframeSendMs,
                (lastVideoKeyframeRequests[key] ?: -KEYFRAME_MIN_INTERVAL_MS) + KEYFRAME_MIN_INTERVAL_MS)
            if (now >= due && sendVideoKeyframeRequest(queued.kind, queued.publisherId)) {
                queuedVideoKeyframes.remove(key)?.detach()
                nextVideoKeyframeSendMs = now + KEYFRAME_GLOBAL_SPACING_MS + kotlin.random.Random.nextLong(76)
                screenKeyframeRequests[queued.publisherId.toString()]?.let { request ->
                    if (queued.kind == "screen" && request.track === queued.track && !request.satisfied) {
                        request.attempts++
                        request.lastSentMs = now
                    }
                }
            } else {
                val next = maxOf(due, (lastVideoKeyframeRequests[key] ?: -KEYFRAME_MIN_INTERVAL_MS) + KEYFRAME_MIN_INTERVAL_MS)
                nextAt = minOf(nextAt ?: next, next)
            }
        }
        return nextAt
    }

    private fun requestMissingScreenKeyframes() {
        if (!canRequestVideoKeyframes()) return
        val recoveryCheckMs = recoverVideoIfNeeded()
        val now = SystemClock.elapsedRealtime()
        val sharingPeers = HashSet<String>()
        var nextCheckMs = recoveryCheckMs
        for (entry in remote.values) {
            if (!entry.screenActive) continue
            val track = entry.screen ?: continue
            val peerId = entry.peerId ?: continue
            val publisherId = peerId.toLongOrNull()?.takeIf { it in 1..0xFFFF_FFFFL } ?: continue
            sharingPeers.add(peerId)
            val activeSince = maxOf(entry.screenActiveSinceMs, lastConnectionOpenedAtMs, screenRecoveryStartedAt[peerId] ?: 0)
            var request = screenKeyframeRequests[peerId]
            if (request == null || request.track !== track || request.activeSinceMs != activeSince) {
                request?.detach()
                val created = ScreenKeyframeRequest(track, activeSince, now)
                if (videoPriority(track) != null && !created.attach()) continue
                created.lastFrameMs = maxOf(created.lastFrameMs, VideoTrackLastFrameStore.lastFrameReceivedAt(track) ?: 0)
                screenKeyframeRequests[peerId] = created
                request = created
            }
            if (videoPriority(track) == null) request.detach()
            else if (!request.satisfied) request.attach()
            if (request.lastFrameMs >= request.activeSinceMs) request.satisfied = true
            if (videoPriority(track) != null && !request.satisfied && request.attempts <= SCREEN_KEYFRAME_RETRY_DELAYS_MS.size) {
                val lastRequest = lastVideoKeyframeRequests["screen|$publisherId"] ?: -KEYFRAME_MIN_INTERVAL_MS
                val recoveryGrace = videoRecoveryChecks["screen|$publisherId"]?.checkAtMs ?: 0
                val dueMs = maxOf(if (request.attempts == 0) {
                    request.firstSeenMs + VISIBLE_VIDEO_GRACE_MS
                } else request.lastSentMs + SCREEN_KEYFRAME_RETRY_DELAYS_MS[request.attempts - 1],
                    maxOf(lastRequest + KEYFRAME_MIN_INTERVAL_MS, recoveryGrace))
                if (dueMs <= now) enqueueVideoKeyframe(track, "screen", publisherId, dueMs, request.activeSinceMs)
                if (request.attempts <= SCREEN_KEYFRAME_RETRY_DELAYS_MS.size) {
                    val next = maxOf(if (request.attempts == 0) request.firstSeenMs + VISIBLE_VIDEO_GRACE_MS
                        else request.lastSentMs + SCREEN_KEYFRAME_RETRY_DELAYS_MS[request.attempts - 1],
                        maxOf((lastVideoKeyframeRequests["screen|$publisherId"] ?: lastRequest) + KEYFRAME_MIN_INTERVAL_MS, recoveryGrace))
                    if (next > now) nextCheckMs = minOf(nextCheckMs ?: next, next)
                }
            }
        }
        val stale = screenKeyframeRequests.keys.filter { it !in sharingPeers }
        for (peer in stale) screenKeyframeRequests.remove(peer)?.detach()
        VideoTrackLastFrameStore.retainTracks(remote.values.flatMap { entry ->
            listOfNotNull(entry.video?.takeIf { entry.cameraActive }, entry.screen?.takeIf { entry.screenActive })
        })
        for (request in screenKeyframeRequests.values) if (request.satisfied) request.detach()
        lastVideoKeyframeRequests.keys.removeAll { key ->
            key.substringAfter('|') !in memberByPeerId
        }
        screenRecoveryStartedAt.keys.retainAll(sharingPeers)
        drainVideoKeyframes()?.let { nextCheckMs = minOf(nextCheckMs ?: it, it) }
        for (request in screenKeyframeRequests.values) {
            if (videoPriority(request.track) != null && !request.satisfied && request.attempts in 1..SCREEN_KEYFRAME_RETRY_DELAYS_MS.size) {
                val due = request.lastSentMs + SCREEN_KEYFRAME_RETRY_DELAYS_MS[request.attempts - 1]
                if (due > now) nextCheckMs = minOf(nextCheckMs ?: due, due)
            }
        }
        if (nextCheckMs != null) scheduleScreenKeyframeCheck(nextCheckMs!! - now)
        else {
            screenKeyframeJob?.cancel()
            screenKeyframeJob = null
        }
    }

    private fun scheduleScreenKeyframeCheck(delayMs: Long) {
        screenKeyframeJob?.cancel()
        val gen = connectionGen
        screenKeyframeJob = scope?.launch {
            delay(maxOf(delayMs, 200L))
            screenKeyframeJob = null
            if (gen == connectionGen) requestMissingScreenKeyframes()
        }
    }

    private fun clearScreenKeyframeRequests() {
        screenKeyframeJob?.cancel()
        screenKeyframeJob = null
        screenKeyframeRequests.values.forEach { it.detach() }
        screenKeyframeRequests.clear()
    }

    private fun suspendVideoKeyframeQueue() {
        screenKeyframeJob?.cancel()
        screenKeyframeJob = null
        videoRecoveryChecks.values.forEach { it.detach() }
        videoRecoveryChecks.clear()
        queuedVideoKeyframes.values.forEach { it.detach() }
        queuedVideoKeyframes.clear()
    }

    private fun resetVideoKeyframeRequests() {
        clearScreenKeyframeRequests()
        lastVideoKeyframeRequests.clear()
        lastKeyframeRequestMs = null
        videoRecoveryChecks.values.forEach { it.detach() }
        videoRecoveryChecks.clear()
        queuedVideoKeyframes.values.forEach { it.detach() }
        queuedVideoKeyframes.clear()
        nextVideoKeyframeSendMs = 0
        screenRecoveryStartedAt.clear()
        videoBackgroundedAtMs = null
    }

    private fun send(json: JSONObject) {
        sendText(json.toString())
    }

    private fun sendText(text: String) {
        val ws = webSocket ?: return
        ws.send(text)
    }

    private fun emitState(state: SfuConnectionState) {
        if (connectionState == state) return
        connectionState = state
        if (state != SfuConnectionState.CONNECTED) {
            stopAudioSendProbe()
            networkQuality = SfuNetworkQuality()
            setNetworkWeak(false)
        }
        onConnectionState?.invoke(state)
    }

    fun handleVoiceJoined(clanId: Long, channelId: Long, userId: String, peerId: String?) {
        val gen = connectionGen
        appScope.launch(mainDispatcher) {
            val call = callIdentity
            if (call == null || !active || gen != connectionGen || !socketOpen ||
                call.clanId != clanId || call.channelId != channelId || call.userId != userId
            ) {
                return@launch
            }
            readiness.confirmVoiceJoined(peerId)
            updateConnectionReadiness()
        }
    }

    private fun updateConnectionReadiness() {
        if (!active || transportRecoveryJob != null) return
        if (peerConnection == null) return
        val status = transportStatus
        val ice = status.ice
        readiness.iceConnected = ice == PeerConnection.IceConnectionState.CONNECTED ||
            ice == PeerConnection.IceConnectionState.COMPLETED
        readiness.transportConnected = status.connection == PeerConnection.PeerConnectionState.CONNECTED
        readiness.roomConfirmed = admitted && stateRestored
        readiness.peerId = selfPeerId
        if (readiness.isReady) {
            val wasConnected = mediaConnected
            mediaConnected = true
            hasReachedConnected = true
            clearTransportWatchdog()
            clearConnectionDeadline()
            if (!wasConnected) {
                restoreCommunicationAudio()
                scheduleHealthyConnectionReset(connectionGen)
                requestMissingScreenKeyframes()
            }
            emitState(SfuConnectionState.CONNECTED)
        } else if (readiness.transportConnected && readiness.iceConnected) {
            mediaConnected = false
            clearTransportWatchdog()
            if (connectionState != SfuConnectionState.AWAITING_CONFIRMATION) {
                clearConnectionDeadline()
                armConnectionDeadline(connectionGen, 20_000L)
            }
            emitState(SfuConnectionState.AWAITING_CONFIRMATION)
        } else if (readiness.iceConnected) {
            mediaConnected = false
            clearConnectionDeadline()
            if (connectionState != SfuConnectionState.DTLS_HANDSHAKE) emitState(SfuConnectionState.ICE_CONNECTED)
            emitState(SfuConnectionState.DTLS_HANDSHAKE)
            armTransportWatchdog(connectionGen)
        }
    }

    private fun armConnectionDeadline(gen: Int, timeoutMs: Long) {
        if (connectionDeadlineJob != null) return
        connectionDeadlineJob = scope?.launch {
            delay(timeoutMs)
            connectionDeadlineJob = null
            if (active && gen == connectionGen && connectionState != SfuConnectionState.CONNECTED) {
                recoverTransport(gen)
            }
        }
    }

    private fun clearConnectionDeadline() {
        connectionDeadlineJob?.cancel()
        connectionDeadlineJob = null
    }

    private suspend fun awaitSetRemote(pc: PeerConnection, desc: SessionDescription) =
        withContext(webRtcDispatcher) {
            suspendCancellableCoroutine<Unit> { cont ->
                pc.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() { cont.resume(Unit) }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) { cont.resumeWithException(RuntimeException("setRemote: $p0")) }
                }, desc)
            }
        }

    private suspend fun awaitSetLocal(pc: PeerConnection, desc: SessionDescription) =
        withContext(webRtcDispatcher) {
            suspendCancellableCoroutine<Unit> { cont ->
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() { cont.resume(Unit) }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) { cont.resumeWithException(RuntimeException("setLocal: $p0")) }
                }, desc)
            }
        }

    private suspend fun awaitCreateAnswer(pc: PeerConnection): SessionDescription =
        withContext(webRtcDispatcher) {
            suspendCancellableCoroutine<SessionDescription> { cont ->
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {
                        if (p0 != null) cont.resume(p0) else cont.resumeWithException(RuntimeException("createAnswer null"))
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(p0: String?) { cont.resumeWithException(RuntimeException("createAnswer: $p0")) }
                    override fun onSetFailure(p0: String?) {}
                }, MediaConstraints())
            }
        }

    private fun makePeerObserver(gen: Int, tracks: RemoteTrackRegistry, status: TransportStatus) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            if (newState != null) status.connection = newState
            appScope.launch(mainDispatcher) {
                if (gen != connectionGen) return@launch
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        updateConnectionReadiness()
                    }
                    PeerConnection.PeerConnectionState.FAILED -> {
                        val ice = status.ice
                        if (ice == PeerConnection.IceConnectionState.CONNECTED || ice == PeerConnection.IceConnectionState.COMPLETED) {
                            recoverTransport(gen)
                            return@launch
                        }
                        mediaConnected = false
                        healthySessionResetJob?.cancel()
                        healthySessionResetJob = null
                        clearTransportWatchdog()
                        if (active) {
                            emitState(SfuConnectionState.DISCONNECTED)
                            recoverTransport(gen)
                        }
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.CLOSED -> {
                        mediaConnected = false
                        emitState(SfuConnectionState.DISCONNECTED)
                        scheduleIceRecovery()
                        healthySessionResetJob?.cancel()
                        healthySessionResetJob = null
                    }
                    else -> {}
                }
            }
        }
        override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            if (state != null) status.ice = state
            appScope.launch(mainDispatcher) {
                if (gen != connectionGen) return@launch
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        iceRecoveryJob?.cancel()
                        iceRecoveryJob = null
                        updateConnectionReadiness()
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        iceRecoveryJob?.cancel()
                        iceRecoveryJob = null
                        mediaConnected = false
                        healthySessionResetJob?.cancel()
                        healthySessionResetJob = null
                        if (active) {
                            emitState(SfuConnectionState.DISCONNECTED)
                            recoverTransport(gen)
                        }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        mediaConnected = false
                        healthySessionResetJob?.cancel()
                        healthySessionResetJob = null
                        emitState(SfuConnectionState.DISCONNECTED)
                        scheduleIceRecovery()
                    }
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
            tracks.events.incrementAndGet()
            appScope.launch(mainDispatcher) {
                if (gen != connectionGen) return@launch
                syncPendingRemoteTracks()
            }
        }
        override fun onTrack(transceiver: RtpTransceiver?) {
            val mid = runCatching { transceiver?.mid }.getOrNull()
            val track = runCatching { transceiver?.receiver?.track() }.getOrNull()
            if (mid != null && track != null) tracks.byMid.putIfAbsent(mid, track)
            tracks.events.incrementAndGet()
            appScope.launch(mainDispatcher) {
                if (gen != connectionGen) return@launch
                if (track is VideoTrack && mid != null && isRemoteMid(mid) && remoteKind(mid) == "screen" &&
                    remote[remoteParticipantId(mid)]?.screenActive == true
                ) {
                    VideoTrackLastFrameStore.observe(track)
                }
                syncPendingRemoteTracks()
            }
        }
    }
}
