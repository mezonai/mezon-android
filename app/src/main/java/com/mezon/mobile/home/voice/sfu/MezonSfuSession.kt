package com.mezon.mobile.home.voice.sfu

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.net.Network
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.MainDispatcher
import com.mezon.mobile.home.call.WebRtcInfra
import com.mezon.mobile.home.voice.VideoTrackLastFrameStore
import com.mezon.mobile.network.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLEncoder
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
private const val MAX_RECONNECT_ATTEMPTS = 40
private const val MAX_TOKEN_REFRESHES = 3
private const val TOKEN_EXPIRY_MARGIN_SECONDS = 60L
private const val MIN_SESSION_RESTART_SPACING_MS = 5_000L
private const val RETIRING_PEER_CONNECTION_GRACE_MS = 10_000L
private const val OFFER_REISSUE_DEADLINE_MS = 8_000L
private const val DTLS_CONNECT_DEADLINE_MS = 15_000L
private const val SPEAKING_THRESHOLD = 0.02
private const val ICE_RECOVERY_GRACE_MS = 4000L
private const val MODERATOR_MUTE_WINDOW_MS = 300L
private const val SFU_CLOSE_KICKED = 4006
private const val SFU_CLOSE_ALONE_TIMEOUT = 4011
private val PARTICIPANT_ACTION_ERRORS = setOf(
    "invalid_token",
    "token_room_mismatch",
    "target_not_found",
    "invalid_participant_action",
    "unsupported_participant_action",
    "auth_not_configured",
)

enum class SfuRemovalCause { KICKED, ALONE_TIMEOUT }

private fun removalCause(code: Int): SfuRemovalCause? = when (code) {
    SFU_CLOSE_KICKED -> SfuRemovalCause.KICKED
    SFU_CLOSE_ALONE_TIMEOUT -> SfuRemovalCause.ALONE_TIMEOUT
    else -> null
}

private fun tokenSecondsLeft(token: String): Long? {
    val parts = token.split('.')
    if (parts.size != 3) return null
    return runCatching {
        val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        val exp = JSONObject(payload).optLong("exp", 0L)
        if (exp > 0L) exp - System.currentTimeMillis() / 1000L else null
    }.getOrNull()
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
    var onParticipants: ((List<SfuParticipant>) -> Unit)? = null
    var onRoleChanged: ((SfuRole) -> Unit)? = null
    var onError: ((String, String?) -> Unit)? = null
    var onLocalVideoTrack: ((VideoTrack?) -> Unit)? = null
    var onLocalScreenTrack: ((VideoTrack?) -> Unit)? = null
    var onPushToTalkActive: ((Boolean) -> Unit)? = null
    var onSpeaking: ((Set<String>) -> Unit)? = null
    var tokenProvider: (suspend () -> String?)? = null
    var onMutedByModerator: (() -> Unit)? = null
    var onRemoved: ((SfuRemovalCause, String) -> Unit)? = null

    @Volatile var role: SfuRole = SfuRole.SPEAKER
        private set

    private var scope: CoroutineScope? = null
    private var webSocket: WebSocket? = null
    private var peerConnection: PeerConnection? = null
    private var retiringPeerConnection: PeerConnection? = null
    private var iceRecoveryJob: Job? = null
    private var transportWatchdogJob: Job? = null

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
    private var pttRequested = false

    private var active = false
    @Volatile private var socketOpen = false
    private var connecting = false
    private var connectionGen = 0
    private var stateRestored = false
    private var reconnectAttempts = 0
    private var tokenRefreshes = 0
    private var tokenRejected = false
    private var lastConnectionOpenedAtMs = 0L
    private var deferredRestartJob: Job? = null
    private var retiringCloseJob: Job? = null
    private var admitted = false
    private var selfPeerId: String? = null
    private var moderatorMuteJob: Job? = null
    private val participantActionCallbacks = ArrayDeque<(Boolean, String?) -> Unit>()

    private var negotiating = false
    private var pendingOffer: Pair<Long, String>? = null
    private var offerReissueJob: Job? = null

    private var transceiverCache: List<RtpTransceiver> = emptyList()
    private var remoteSnapshot: List<RemoteTransceiverSnapshot> = emptyList()
    private var remoteMediaSyncScheduled = false
    private var remoteMediaSyncRunning = false
    private var remoteMediaRevision = 0
    private val webRtcDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "sfu-webrtc") }.asCoroutineDispatcher()

    private val userIdByMid = HashMap<String, String>()
    private val peerIdByMid = HashMap<String, String>()
    private val roleByMid = HashMap<String, SfuRole>()
    private val memberByPeerId = HashMap<String, MemberState>()
    private val remote = LinkedHashMap<String, RemoteEntry>()
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
        var cameraActive: Boolean = false
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
        this.pttRequested = false
        this.joined = false
        this.localTracksAdded = false
        this.active = true
        this.reconnectAttempts = 0
        this.tokenRefreshes = 0
        this.tokenRejected = false
        val roomScope = CoroutineScope(SupervisorJob() + mainDispatcher)
        scope = roomScope

        webRtcInfra.ensureFactoryReady()
        createLocalAudioTrack()

        if (buildWsUrl(token).isEmpty()) {
            Log.e(TAG, "join failed: empty MEZON_SFU_WS_URL")
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
                if (active && joined && !socketOpen && !connecting) {
                    if (!networkMonitor.isOnline.value) {
                        continue
                    }
                    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                        active = false
                        emitState(SfuConnectionState.FAILED)
                        break
                    }
                    reconnectAttempts++
                    if (tokenNeedsRefresh() && tokenRefreshes < MAX_TOKEN_REFRESHES) {
                        val fresh = runCatching { tokenProvider?.invoke() }.getOrNull()
                        if (!fresh.isNullOrEmpty()) {
                            tokenRefreshes++
                            this@MezonSfuSession.token = fresh
                            tokenRejected = false
                        }
                    }
                    if (!isActive || !active || !joined || socketOpen || connecting) continue
                    openConnection(initial = false)
                }
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
                    if (changed) restartSession("network path changed")
                }
            }
        }
    }

    private fun openConnection(initial: Boolean) {
        connecting = true
        connectionGen++
        val gen = connectionGen
        stateRestored = false
        admitted = false
        selfPeerId = null
        participantActionCallbacks.clear()
        clearOfferReissueDeadline()
        clearTransportWatchdog()
        clearModeratorMuteCheck()
        clearDeferredRestart()
        lastConnectionOpenedAtMs = SystemClock.elapsedRealtime()
        if (!initial) {
            if (pttActive) {
                pttActive = false
                localAudioTrack?.setEnabled(false)
                onPushToTalkActive?.invoke(false)
            }
            socketOpen = false
            runCatching { webSocket?.close(1000, null) }
            peerConnection?.let { previous ->
                closeOffMain(retiringPeerConnection)
                retiringPeerConnection = previous
                scheduleRetiringPeerConnectionClose()
            }
            peerConnection = null
            negotiating = false
            pendingOffer = null
            localTracksAdded = false
            userIdByMid.clear()
            peerIdByMid.clear()
            roleByMid.clear()
            memberByPeerId.clear()
            remote.clear()
            emitParticipants()
        }
        remoteMediaSyncScheduled = false
        remoteMediaSyncRunning = false
        transceiverCache = emptyList()
        remoteSnapshot = emptyList()
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
    }

    private fun pollSpeaking(pc: PeerConnection) {
        val localId = userId
        val localAudible = micEnabled || pttActive
        val midOwners = HashMap(userIdByMid)
        appScope.launch(webRtcDispatcher) {
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
                appScope.launch(mainDispatcher) { onSpeaking?.invoke(speaking) }
            }
        }
    }

    fun leave() {
        active = false
        connectionGen++
        socketOpen = false
        connecting = false
        stateRestored = false
        admitted = false
        selfPeerId = null
        participantActionCallbacks.clear()
        pttRequested = false
        clearOfferReissueDeadline()
        clearTransportWatchdog()
        clearModeratorMuteCheck()
        clearDeferredRestart()
        retiringCloseJob?.cancel()
        retiringCloseJob = null
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
        remoteMediaSyncScheduled = false
        remoteMediaSyncRunning = false
        transceiverCache = emptyList()
        remoteSnapshot = emptyList()
        iceRecoveryJob?.cancel()
        iceRecoveryJob = null
        closeOffMain(peerConnection)
        closeOffMain(retiringPeerConnection)
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
    }

    fun setMicEnabled(on: Boolean) {
        micEnabled = on
        localAudioTrack?.setEnabled(on)
        send(JSONObject().put("type", "mute").put("is_mute", !on))
    }

    fun setCameraEnabled(on: Boolean) {
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
        if (role != SfuRole.AUDIENCE) return
        pttRequested = true
        send(JSONObject().put("type", "mute").put("is_mute", false))
        send(JSONObject().put("type", "push_to_talk").put("active", true))
    }

    fun pttRelease() {
        if (role != SfuRole.AUDIENCE) return
        pttRequested = false
        send(JSONObject().put("type", "push_to_talk").put("active", false))
        send(JSONObject().put("type", "mute").put("is_mute", true))
    }

    fun sendParticipantAction(token: String, onResult: (Boolean, String?) -> Unit): Boolean {
        val ws = webSocket ?: return false
        if (!active || !socketOpen || !admitted) return false
        if (!ws.send(JSONObject().put("type", "participant_action").put("token", token).toString())) return false
        participantActionCallbacks.addLast(onResult)
        return true
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
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val source = webRtcInfra.factory.createAudioSource(constraints)
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
                handleMessage(text)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            val cause = removalCause(code)
            if (cause != null) {
                appScope.launch(mainDispatcher) { handleRemoved(gen, cause, reason) }
            }
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
                val cause = removalCause(code)
                if (cause != null) handleRemoved(gen, cause, reason)
                else if (active && joined) emitState(SfuConnectionState.DISCONNECTED)
                else if (active) emitState(SfuConnectionState.FAILED)
            }
        }
    }

    private fun handleRemoved(gen: Int, cause: SfuRemovalCause, reason: String) {
        if (gen != connectionGen || !active) return
        Log.w(TAG, "sfu removed this peer from the room cause=$cause reason='$reason'")
        active = false
        onRemoved?.invoke(cause, reason)
    }

    private fun handleMessage(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "ping" -> send(JSONObject().put("type", "pong"))
            "pong" -> {}
            "joined" -> {
                emitState(SfuConnectionState.AWAITING_OFFER)
            }
            "room_snapshot" -> {
                if (applyPeers(msg.optJSONArray("members")) && peerConnection != null) syncRemoteMedia()
                joined = true
                admitted = true
                selfPeerId = msg.opt("self_peer_id")?.toString()
                reconnectAttempts = 0
                tokenRefreshes = 0
                tokenRejected = false
                if (!stateRestored) {
                    stateRestored = true
                    val resumePushToTalk = role == SfuRole.AUDIENCE && pttRequested
                    send(JSONObject().put("type", "mute").put("is_mute", !micEnabled && !resumePushToTalk))
                    if (resumePushToTalk) {
                        send(JSONObject().put("type", "push_to_talk").put("active", true))
                    }
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
                if (peer != null && applyPeers(org.json.JSONArray().put(peer)) && peerConnection != null) syncRemoteMedia()
                emitParticipants()
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
                val active = msg.optBoolean("active")
                pttActive = active
                localAudioTrack?.setEnabled(active)
                onPushToTalkActive?.invoke(active)
            }
            "role_changed" -> {
                val newRole = SfuRole.fromWire(msg.optString("role"))
                scope?.launch { handleRoleChanged(newRole) }
            }
            "offer" -> {
                clearOfferReissueDeadline()
                val sdp = msg.optString("sdp")
                if (sdp.isNotEmpty()) onOffer(msg.optLong("offer_generation"), sdp)
            }
            "mute_changed" -> clearModeratorMuteCheck()
            "participant_action_completed" -> {
                participantActionCallbacks.removeFirstOrNull()?.invoke(true, null)
            }
            "error" -> {
                val detail = msg.optString("message")
                if (detail == "invalid_token" || detail == "missing_token") tokenRejected = true
                when {
                    detail == "invalid_push_to_talk" || detail == "push_to_talk_rejected" -> {
                        pttActive = false
                        pttRequested = false
                        localAudioTrack?.setEnabled(false)
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
                    (detail == "invalid_token" || detail == "missing_token") && active && joined && tokenRefreshes >= MAX_TOKEN_REFRESHES -> {
                        Log.e(TAG, "sfu rejected the token ($detail) after $tokenRefreshes refreshes; giving up")
                        active = false
                        runCatching { webSocket?.close(1000, null) }
                        onError?.invoke(detail, msg.optString("message"))
                        emitState(SfuConnectionState.FAILED)
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

    private fun armOfferReissueDeadline() {
        if (offerReissueJob?.isActive == true) return
        val gen = connectionGen
        offerReissueJob = scope?.launch {
            delay(OFFER_REISSUE_DEADLINE_MS)
            offerReissueJob = null
            if (gen != connectionGen || !active || !joined) return@launch
            Log.w(TAG, "sfu never reissued the rejected offer; reconnecting")
            runCatching { webSocket?.close(1000, null) }
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
        val pc = peerConnection ?: return
        if (pc.connectionState() == PeerConnection.PeerConnectionState.CONNECTED) return
        transportWatchdogJob = scope?.launch {
            delay(DTLS_CONNECT_DEADLINE_MS)
            transportWatchdogJob = null
            if (gen != connectionGen || !active || !joined) return@launch
            val current = peerConnection ?: return@launch
            if (current.connectionState() == PeerConnection.PeerConnectionState.CONNECTED) return@launch
            Log.w(TAG, "dtls never completed after ice connected; restarting the sfu session")
            restartSession("dtls handshake never completed")
        }
    }

    private fun clearTransportWatchdog() {
        transportWatchdogJob?.cancel()
        transportWatchdogJob = null
    }

    private fun onOffer(generation: Long, sdp: String) {
        parseMsids(sdp)
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
            remoteMediaRevision++
            var answerSent = false
            try {
                val previousRemoteSdp = withContext(webRtcDispatcher) { pc.remoteDescription?.description }
                if (!isCurrentConnection(pc, gen)) return
                val stableSdp = stabilizeInactiveVideoSections(sdp, previousRemoteSdp)
                awaitSetRemote(pc, SessionDescription(SessionDescription.Type.OFFER, stableSdp))
                if (!isCurrentConnection(pc, gen)) return
                attachLocalTracks(pc)
                val answer = awaitCreateAnswer(pc)
                if (!isCurrentConnection(pc, gen)) return
                send(
                    JSONObject()
                        .put("type", "answer")
                        .put("offer_generation", generation)
                        .put("sdp", patchAnswerForSfu(answer.description))
                )
                answerSent = true
                awaitSetLocal(pc, SessionDescription(SessionDescription.Type.ANSWER, answer.description))
                if (!isCurrentConnection(pc, gen)) return
                val transceivers = withContext(webRtcDispatcher) { pc.transceivers }
                if (!isCurrentConnection(pc, gen)) return
                transceiverCache = transceivers
                val snapshot = withContext(webRtcDispatcher) { captureRemoteSnapshot(transceivers) }
                if (!isCurrentConnection(pc, gen)) return
                remoteSnapshot = snapshot
                syncRemoteMedia()
            } catch (e: Exception) {
                if (!isCurrentConnection(pc, gen)) return
                if (answerSent) {
                    runCatching { webSocket?.close(1000, null) }
                    return
                }
                Log.e(TAG, "negotiate failed: ${e.message}")
            }
            offer = pendingOffer
            pendingOffer = null
            if (offer != null) delay(50)
        }
        negotiating = false
        scheduleRemoteMediaSync()
    }

    private fun isCurrentConnection(pc: PeerConnection, gen: Int): Boolean =
        gen == connectionGen && peerConnection === pc

    private fun attachLocalTracks(pc: PeerConnection) {
        if (localTracksAdded) {
            if (role == SfuRole.SPEAKER && screenOn) reattachScreen(pc)
            return
        }
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
            prepareVideoSender(pc)
            if (screenOn) reattachScreen(pc)
        }
        localTracksAdded = true
    }

    private suspend fun handleRoleChanged(newRole: SfuRole) {
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

    private fun prepareVideoSender(addingTo: PeerConnection? = null) {
        if (cameraTrack == null) {
            val source = webRtcInfra.factory.createVideoSource(false)
            val track = webRtcInfra.factory.createVideoTrack("sfu_camera", source)
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
        val track = webRtcInfra.factory.createVideoTrack("sfu_screen", source)
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
        for (i in 0 until members.length()) {
            val peer = members.optJSONObject(i) ?: continue
            val peerId = peer.opt("peer_id")?.toString() ?: continue
            val state = memberByPeerId.getOrPut(peerId) { MemberState() }
            peer.optString("user_id").takeIf { it.isNotEmpty() }?.let { state.userId = it }
            if (peer.has("role")) state.role = SfuRole.fromWire(peer.optString("role"))
            if (peer.has("is_mute")) state.muted = peer.optBoolean("is_mute")
            if (peer.has("camera_active")) state.cameraActive = peer.optBoolean("camera_active")
            if (peer.has("screen_active")) state.screenActive = peer.optBoolean("screen_active")
            val mids = listOf(
                peer.opt("mid_audio"), peer.opt("mid_video"), peer.opt("mid_screen")
            ).mapNotNull { it?.toString() }.filter { it.isNotEmpty() && it != "0" }
            for (mid in mids) {
                if (claimMid(mid, peerId)) ownershipChanged = true
            }
            val existing = remote.entries.firstOrNull { it.value.peerId == peerId }?.key
            val participantId = existing ?: mids.firstOrNull()?.let { remoteParticipantId(it) } ?: continue
            applyMemberState(remote.getOrPut(participantId) { RemoteEntry(participantId) }, peerId)
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
        state.screenActive?.let { entry.screenActive = it }
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

    private fun scheduleRemoteMediaSync() {
        remoteMediaSyncScheduled = true
        if (remoteMediaSyncRunning || negotiating || !active) return
        val roomScope = scope ?: return
        val pc = peerConnection ?: return
        val gen = connectionGen
        remoteMediaSyncRunning = true
        roomScope.launch {
            try {
                while (remoteMediaSyncScheduled && active && isCurrentConnection(pc, gen)) {
                    if (negotiating) return@launch
                    remoteMediaSyncScheduled = false
                    val revision = remoteMediaRevision
                    // Read live receivers, not the snapshot from the last SDP negotiation.
                    val transceivers = withContext(webRtcDispatcher) { pc.transceivers }
                    if (!isCurrentConnection(pc, gen)) return@launch
                    val snapshot = withContext(webRtcDispatcher) { captureRemoteSnapshot(transceivers) }
                    if (!isCurrentConnection(pc, gen)) return@launch
                    if (negotiating) {
                        remoteMediaSyncScheduled = true
                        return@launch
                    }
                    if (revision != remoteMediaRevision) {
                        remoteMediaSyncScheduled = true
                        continue
                    }
                    transceiverCache = transceivers
                    remoteSnapshot = snapshot
                    syncRemoteMedia()
                }
            } finally {
                if (isCurrentConnection(pc, gen)) remoteMediaSyncRunning = false
            }
        }
    }

    private fun captureRemoteSnapshot(transceivers: List<RtpTransceiver>): List<RemoteTransceiverSnapshot> =
        transceivers.map { tc ->
            val direction = tc.currentDirection ?: tc.direction
            val receiving = direction != RtpTransceiver.RtpTransceiverDirection.INACTIVE &&
                direction != RtpTransceiver.RtpTransceiverDirection.STOPPED
            RemoteTransceiverSnapshot(tc.mid, direction, if (receiving) tc.receiver?.track() else null)
        }

    private fun closeOffMain(pc: PeerConnection?) {
        if (pc == null) return
        appScope.launch(webRtcDispatcher) { runCatching { pc.close() } }
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
            if (kind == "screen" && track is VideoTrack) {
                // Preserve a frame while the participant tile is still being created.
                VideoTrackLastFrameStore.observe(track)
            }
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
                }
            }
            if (entry.audio == null && entry.video == null && entry.screen == null) {
                remote.remove(id)
            }
        }
        releaseRetiringPeerConnection()
        emitParticipants()
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
        closeOffMain(previous)
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
            closeOffMain(retiring)
        }
    }

    private fun restartSession(reason: String) {
        if (!active || !joined || connecting) return
        val sinceLastOpenMs = SystemClock.elapsedRealtime() - lastConnectionOpenedAtMs
        if (lastConnectionOpenedAtMs > 0L && sinceLastOpenMs < MIN_SESSION_RESTART_SPACING_MS) {
            deferRestart(reason, MIN_SESSION_RESTART_SPACING_MS - sinceLastOpenMs)
            return
        }
        iceRecoveryJob?.cancel()
        iceRecoveryJob = null
        if (tokenNeedsRefresh()) {
            socketOpen = false
            emitState(SfuConnectionState.DISCONNECTED)
            runCatching { webSocket?.close(1000, null) }
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
                    if (uid != null) {
                        userIdByMid[mid] = uid
                        if (pid != null && pid != "0") claimMid(mid, pid)
                    }
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
        ws.send(json.toString())
    }

    private fun emitState(state: SfuConnectionState) {
        onConnectionState?.invoke(state)
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

    private fun makePeerObserver(gen: Int) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            appScope.launch(mainDispatcher) {
                if (gen != connectionGen) return@launch
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> clearTransportWatchdog()
                    PeerConnection.PeerConnectionState.FAILED -> {
                        clearTransportWatchdog()
                        if (active && joined) {
                            emitState(SfuConnectionState.DISCONNECTED)
                            restartSession("peer connection failed")
                        }
                    }
                    else -> {}
                }
            }
        }
        override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            appScope.launch(mainDispatcher) {
                if (gen != connectionGen) return@launch
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        iceRecoveryJob?.cancel()
                        iceRecoveryJob = null
                        emitState(SfuConnectionState.CONNECTED)
                        armTransportWatchdog(gen)
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        iceRecoveryJob?.cancel()
                        iceRecoveryJob = null
                        if (active && joined) {
                            emitState(SfuConnectionState.DISCONNECTED)
                            restartSession("ice failed")
                        } else if (active) {
                            emitState(SfuConnectionState.FAILED)
                        }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        emitState(SfuConnectionState.DISCONNECTED)
                        if (active && joined && iceRecoveryJob == null) {
                            iceRecoveryJob = scope?.launch {
                                delay(ICE_RECOVERY_GRACE_MS)
                                iceRecoveryJob = null
                                restartSession("ice stayed disconnected")
                            }
                        }
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
            appScope.launch(mainDispatcher) {
                if (gen != connectionGen) return@launch
                if (peerConnection == null) return@launch
                scheduleRemoteMediaSync()
            }
        }
    }
}
