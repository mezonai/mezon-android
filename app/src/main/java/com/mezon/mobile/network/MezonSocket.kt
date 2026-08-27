package com.mezon.mobile.network

import com.mezon.mobile.BuildConfig
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.util.SentryReporter
import android.net.Network
import android.util.Log
import com.google.protobuf.ByteString as ProtoByteString
import com.google.protobuf.StringValue
import com.mezon.mezon.api.MessageAttachment
import com.mezon.mezon.api.MessageMention
import com.mezon.mezon.api.MessageRef
import com.mezon.mezon.rtapi.EnvelopeKt
import com.mezon.mezon.rtapi.Envelope
import com.mezon.mezon.rtapi.apiRequestEvent
import com.mezon.mezon.rtapi.channelJoin
import com.mezon.mezon.rtapi.channelLeave
import com.mezon.mezon.rtapi.channelMessageSend
import com.mezon.mezon.rtapi.channelMessageUpdate
import com.mezon.mezon.rtapi.checkNameExistedEvent
import com.mezon.mezon.rtapi.clanJoin
import com.mezon.mezon.rtapi.customStatusEvent
import com.mezon.mezon.rtapi.envelope
import com.mezon.mezon.rtapi.incomingCallPush
import com.mezon.mezon.rtapi.lastPinMessageEvent
import com.mezon.mezon.rtapi.lastSeenMessageEvent
import com.mezon.mezon.rtapi.markAsRead
import com.mezon.mezon.rtapi.messageTypingEvent
import com.mezon.mezon.rtapi.pong
import com.mezon.mezon.rtapi.statusFollow
import com.mezon.mezon.rtapi.statusUnfollow
import com.mezon.mezon.rtapi.statusUpdate
import com.mezon.mezon.rtapi.voiceReactionSend
import com.mezon.mezon.rtapi.webrtcSignalingFwd
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MezonSocket @Inject constructor(
    private val sessionManager: SessionManager,
    private val networkMonitor: NetworkMonitor,
    private val sentryReporter: SentryReporter,
    @ApplicationScope private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MezonSocket"
        private const val HEARTBEAT_INTERVAL_MS = 8_000L
        private const val PONG_TIMEOUT_MS = HEARTBEAT_INTERVAL_MS * 3
        private const val RECONNECT_MIN_MS = 1_000L
        private const val RECONNECT_MAX_MS = 30_000L
        private const val JITTER_RANGE_MS = 1_000L
        private const val MAX_RECONNECT_FAILS = 6
        private const val SEND_TIMEOUT_MS = 10_000L
        private const val STABLE_CONNECTION_RESET_MS = 10_000L
        private const val CONNECT_ACK_GRACE_MS = 1_000L

        const val TYPE_CHECK_CLAN = 0
        const val TYPE_CHECK_CATEGORY = 1
        const val TYPE_CHECK_CHANNEL = 2
        const val TYPE_CHECK_THREAD = 3
        const val TYPE_CHECK_NICKNAME = 4
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    val events: SharedFlow<Envelope> = _events.asSharedFlow()

    private val _reconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reconnected: SharedFlow<Unit> = _reconnected.asSharedFlow()

    private var transport: AbridgedTcpTransport? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectHealthResetJob: Job? = null
    private var connectReadyTimeoutJob: Job? = null
    private var currentWsUrl: String? = null
    private var currentToken: String? = null
    private var currentTcpUrl: String? = null
    @Volatile private var currentNetwork: Network? = null

    private val cidCounter = AtomicInteger(0)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<Envelope>>()
    private val pendingApiRequests = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()
    private data class JoinedChannelKey(val clanId: Long, val channelId: Long)
    private val joinedChannelGenerations = ConcurrentHashMap<JoinedChannelKey, Int>()
    private fun nextCid(): Int = cidCounter.updateAndGet { c -> if (c >= 65534) 1 else c + 1 }
    private var reconnectDelayMs = RECONNECT_MIN_MS
    private var reconnectFailCount = 0
    @Volatile private var isReconnecting = false
    @Volatile private var userDisconnected = false
    @Volatile private var hasConnectedBefore = false
    @Volatile private var forceRefreshNextReconnect = false
    @Volatile private var lastPongAtMs = 0L
    @Volatile private var lastPingSentAtMs = 0L
    @Volatile private var transportReadyPending = false
    @Volatile private var confirmedInboundGen = 0
    @Volatile private var lastConfirmedInboundAtMs = 0L
    private val totalReconnectAttempts = AtomicInteger(0)

    @Volatile var connectGen: Int = 0
        private set
    @Volatile var socketTokenFingerprint: String? = null
        private set

    private fun tokenFp(token: String?): String =
        if (token.isNullOrEmpty()) "?" else token.takeLast(6)

    private val connectLock = Any()

    init {
        scope.launch { observeNetwork() }
    }

    private suspend fun observeNetwork() {
        networkMonitor.activeNetwork.collect { network ->
            var handoffKick = false
            var reconnectKick = false
            synchronized(connectLock) {
                val previous = currentNetwork
                currentNetwork = network
                if (!userDisconnected && currentWsUrl != null && currentToken != null && network != null) {
                    val state = _connectionState.value
                    val connectedish = state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING
                    if (connectedish) {
                        handoffKick = previous != null && previous != network
                    } else {
                        reconnectKick = true
                    }
                }
            }
            if (handoffKick) kickReconnectForHandoff("active network changed")
            else if (reconnectKick) reconnectNow("network available")
        }
    }

    private fun cancelReconnectHealthReset() {
        reconnectHealthResetJob?.cancel()
        reconnectHealthResetJob = null
    }

    private fun cancelConnectReadyTimeout() {
        connectReadyTimeoutJob?.cancel()
        connectReadyTimeoutJob = null
    }

    fun connect(wsUrl: String, token: String, tcpUrl: String? = null) {
        synchronized(connectLock) {
            if (_connectionState.value == ConnectionState.CONNECTED ||
                _connectionState.value == ConnectionState.CONNECTING
            ) {
                Log.d(TAG, "Already connected or connecting, skipping")
                return
            }

            reconnectJob?.cancel()
            isReconnecting = false
            reconnectFailCount = 0
            reconnectDelayMs = RECONNECT_MIN_MS
            userDisconnected = false

            cancelReconnectHealthReset()
            currentWsUrl = wsUrl
            currentToken = token
            currentTcpUrl = tcpUrl

            doConnect(wsUrl, token, tcpUrl)
        }
    }

    fun reconnectNow(reason: String) {
        synchronized(connectLock) {
            if (_connectionState.value != ConnectionState.DISCONNECTED) return
            if (currentWsUrl == null || currentToken == null) return
            if (userDisconnected) return
            if (!networkMonitor.isOnline.value) {
                Log.d(TAG, "reconnectNow($reason): offline, skipping")
                return
            }

            Log.d(TAG, "reconnectNow($reason) — cancelling pending backoff, reconnecting immediately")
            reconnectJob?.cancel()
            isReconnecting = false
            cancelReconnectHealthReset()
            reconnectFailCount = 0
            reconnectDelayMs = RECONNECT_MIN_MS
            scheduleReconnect()
        }
    }

    private fun kickReconnectForHandoff(reason: String) {
        synchronized(connectLock) {
            if (userDisconnected) return
            if (currentWsUrl == null || currentToken == null) return

            Log.w(TAG, "Network handoff ($reason) — dropping stale socket, redialing on new network")
            sentryReporter.logSocketWarning("network_handoff", "reason=$reason gen=$connectGen")
            reconnectJob?.cancel()
            isReconnecting = false
            heartbeatJob?.cancel()
            cancelReconnectHealthReset()
            cancelConnectReadyTimeout()
            transportReadyPending = false
            val t = transport
            transport = null
            _connectionState.value = ConnectionState.DISCONNECTED
            cancelAllPending(reason)
            t?.close()
            reconnectFailCount = 0
            reconnectDelayMs = RECONNECT_MIN_MS
            scheduleReconnect()
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnect requested")
        synchronized(connectLock) {
            userDisconnected = true
            hasConnectedBefore = false
            currentWsUrl = null
            currentToken = null
            currentTcpUrl = null
            heartbeatJob?.cancel()
            reconnectJob?.cancel()
            cancelReconnectHealthReset()
            cancelConnectReadyTimeout()
            transportReadyPending = false
            isReconnecting = false
            reconnectFailCount = 0
            reconnectDelayMs = RECONNECT_MIN_MS
            transport?.close()
            transport = null
            _connectionState.value = ConnectionState.DISCONNECTED
            cancelAllPending("Disconnected")
        }
    }

    fun forceReconnectForAuthFailure(reason: String) {
        if (userDisconnected) return
        Log.w(TAG, "forceReconnectForAuthFailure: $reason")
        synchronized(connectLock) {
            forceRefreshNextReconnect = true
            val t = transport
            transport = null
            _connectionState.value = ConnectionState.DISCONNECTED
            heartbeatJob?.cancel()
            cancelReconnectHealthReset()
            cancelConnectReadyTimeout()
            transportReadyPending = false
            cancelAllPending("Auth failure reconnect")
            t?.close()
            if (!userDisconnected) scheduleReconnect()
        }
    }

    fun forceReconnect(reason: String) {
        if (userDisconnected) return
        Log.w(TAG, "forceReconnect: $reason")
        sentryReporter.logSocketWarning("force_reconnect", "reason=$reason gen=$connectGen")
        synchronized(connectLock) {
            val t = transport
            transport = null
            _connectionState.value = ConnectionState.DISCONNECTED
            heartbeatJob?.cancel()
            cancelReconnectHealthReset()
            cancelConnectReadyTimeout()
            transportReadyPending = false
            cancelAllPending("Force reconnect")
            t?.close()
            if (!userDisconnected) scheduleReconnect()
        }
    }

    suspend fun send(block: EnvelopeKt.Dsl.() -> Unit): Envelope {
        val cid = nextCid()
        val env = envelope {
            this.cid = cid
            block()
        }

        val t = transport
            ?: throw IllegalStateException("Socket not connected")

        val deferred = CompletableDeferred<Envelope>()
        pendingRequests[cid] = deferred

        val bytes = env.toByteArray()
        t.send(bytes) { error ->
            if (error != null) {
                pendingRequests.remove(cid)?.completeExceptionally(
                    IllegalStateException("Failed to send envelope: ${error.message}")
                )
                sentryReporter.logSocketFailure("send_enqueue", error, "case=${env.messageCase}")
            }
        }

        return try {
            withTimeout(SEND_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingRequests.remove(cid)
            val err = RuntimeException("Request timed out: cid=$cid case=${env.messageCase}", e)
            sentryReporter.logSocketFailure("send_timeout", err)
            throw err
        }
    }

    fun sendFireAndForget(env: Envelope) {
        val t = transport ?: return
        t.send(env.toByteArray()) { }
    }

    fun joinClanChat(clanId: Long) {
        val cid = nextCid()
        sendFireAndForget(envelope {
            this.cid = cid
            this.clanJoin = clanJoin { this.clanId = clanId }
        })
    }

    suspend fun awaitConnected(timeoutMs: Long = 15_000L): Boolean {
        if (_connectionState.value == ConnectionState.CONNECTED) return true
        return try {
            withTimeout(timeoutMs) {
                _connectionState.first { it == ConnectionState.CONNECTED }
            }
            true
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "awaitConnected: timeout ${timeoutMs}ms")
            sentryReporter.logSocketWarning("awaitConnected", "timeout ${timeoutMs}ms")
            false
        }
    }

    suspend fun sendApiRequest(
        apiName: String,
        body: ByteArray,
        timeoutMs: Long = SEND_TIMEOUT_MS
    ): ByteArray {
        val t = transport
            ?: throw IllegalStateException("Socket not connected")

        val cid = nextCid()
        val env = envelope {
            this.cid = cid
            this.apiRequestEvent = apiRequestEvent {
                this.apiIndex = MezonApiNameRegistry.indexOf(apiName)
                this.apiName = apiName
                this.body = ProtoByteString.copyFrom(body)
            }
        }

        val deferred = CompletableDeferred<ByteArray>()
        pendingApiRequests[cid] = deferred

        t.send(env.toByteArray()) { error ->
            if (error != null) {
                pendingApiRequests.remove(cid)?.completeExceptionally(
                    IllegalStateException("Failed to send api_request_event '$apiName': ${error.message}")
                )
                sentryReporter.logSocketFailure("api_request_enqueue", error, "api=$apiName")
            }
        }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingApiRequests.remove(cid)
            val err = RuntimeException("api_request_event '$apiName' timed out after ${timeoutMs}ms", e)
            sentryReporter.logSocketFailure("api_request_timeout", err, "api=$apiName")
            throw err
        }
    }

    suspend fun joinChat(
        clanId: Long,
        channelId: Long,
        channelType: Int,
        isPublic: Boolean
    ): Envelope {
        val generation = connectGen
        val response = send {
            this.channelJoin = channelJoin {
                this.clanId = clanId
                this.channelId = channelId
                this.channelType = channelType
                this.isPublic = isPublic
            }
        }
        if (generation == connectGen && isRealtimeTransportFresh(generation)) {
            joinedChannelGenerations[JoinedChannelKey(clanId, channelId)] = generation
        }
        return response
    }

    /**
     * A TCP/TLS socket can be open before the realtime server is ready, or can be stale after
     * the app resumes. In both cases channel messages should use HTTP instead of waiting for the
     * socket request timeout. A successful inbound frame and ChannelJoin for this connection
     * generation are required before using the realtime send path.
     */
    fun canSendChannelMessageRealtime(clanId: Long, channelId: Long): Boolean {
        val generation = connectGen
        return isRealtimeTransportFresh(generation) &&
            joinedChannelGenerations[JoinedChannelKey(clanId, channelId)] == generation
    }

    private fun isRealtimeTransportFresh(generation: Int): Boolean {
        return synchronized(connectLock) {
            if (generation == 0 || generation != connectGen) return@synchronized false
            if (_connectionState.value != ConnectionState.CONNECTED) return@synchronized false
            if (confirmedInboundGen != generation || transport == null) return@synchronized false
            val ageMs = System.currentTimeMillis() - lastConfirmedInboundAtMs
            ageMs >= 0L && ageMs <= PONG_TIMEOUT_MS
        }
    }

    suspend fun leaveChat(
        clanId: Long,
        channelId: Long,
        channelType: Int,
        isPublic: Boolean
    ): Envelope = send {
        this.channelLeave = channelLeave {
            this.clanId = clanId
            this.channelId = channelId
            this.channelType = channelType
            this.isPublic = isPublic
        }
    }

    suspend fun writeChatMessage(
        clanId: Long,
        channelId: Long,
        mode: Int,
        isPublic: Boolean,
        content: String,
        mentions: List<MessageMention>? = null,
        attachments: List<MessageAttachment>? = null,
        references: List<MessageRef>? = null,
        anonymousMessage: Boolean = false,
        mentionEveryone: Boolean = false,
        avatar: String = "",
        code: Int = 0,
        topicId: Long = 0L
    ): Envelope = send {
        this.channelMessageSend = channelMessageSend {
            this.clanId = clanId
            this.channelId = channelId
            this.mode = mode
            this.isPublic = isPublic
            this.content = content
            mentions?.takeIf { it.isNotEmpty() }?.let { this.mentions.addAll(it) }
            attachments?.takeIf { it.isNotEmpty() }?.let { this.attachments.addAll(it) }
            references?.takeIf { it.isNotEmpty() }?.let { this.references.addAll(it) }
            this.anonymousMessage = anonymousMessage
            this.mentionEveryone = mentionEveryone
            this.avatar = avatar
            this.code = code
            this.topicId = topicId
        }
    }

    suspend fun updateChatMessage(
        clanId: Long,
        channelId: Long,
        mode: Int,
        isPublic: Boolean,
        messageId: Long,
        content: String,
        mentions: List<MessageMention>? = null,
        attachments: List<MessageAttachment>? = null,
        hideEditted: Boolean = false,
        topicId: Long = 0L,
        isUpdateMsgTopic: Boolean = false,
        createTimeSeconds: Int = 0
    ): Envelope = send {
        this.channelMessageUpdate = channelMessageUpdate {
            this.clanId = clanId
            this.channelId = channelId
            this.messageId = messageId
            this.content = content
            mentions?.takeIf { it.isNotEmpty() }?.let { this.mentions.addAll(it) }
            attachments?.takeIf { it.isNotEmpty() }?.let { this.attachments.addAll(it) }
            this.mode = mode
            this.isPublic = isPublic
            this.hideEditted = hideEditted
            if (topicId != 0L) this.topicId = topicId
            if (isUpdateMsgTopic) this.isUpdateMsgTopic = isUpdateMsgTopic
            if (createTimeSeconds > 0) this.createTimeSeconds = createTimeSeconds
        }
    }

    suspend fun writeMessageTyping(
        clanId: Long,
        channelId: Long,
        mode: Int,
        isPublic: Boolean,
        senderDisplayName: String,
        topicId: Long = 0L
    ): Envelope = send {
        this.messageTypingEvent = messageTypingEvent {
            this.clanId = clanId
            this.channelId = channelId
            this.mode = mode
            this.isPublic = isPublic
            this.senderDisplayName = senderDisplayName
            this.topicId = topicId
        }
    }

    suspend fun writeLastSeenMessage(
        clanId: Long,
        channelId: Long,
        mode: Int,
        messageId: Long,
        timestampSeconds: Int,
        badgeCount: Int
    ): Envelope = send {
        this.lastSeenMessageEvent = lastSeenMessageEvent {
            this.clanId = clanId
            this.channelId = channelId
            this.mode = mode
            this.messageId = messageId
            this.timestampSeconds = timestampSeconds
            this.badgeCount = badgeCount
        }
    }

    suspend fun writeLastPinMessage(
        clanId: Long,
        channelId: Long,
        mode: Int,
        isPublic: Boolean,
        messageId: Long,
        timestampSeconds: Int,
        operation: Int,
        messageSenderAvatar: String = "",
        messageSenderId: String = "",
        messageSenderUsername: String = "",
        messageContent: String = "",
        messageAttachment: String = "",
        messageCreatedTime: String = ""
    ): Envelope = send {
        this.lastPinMessageEvent = lastPinMessageEvent {
            this.clanId = clanId
            this.channelId = channelId
            this.mode = mode
            this.isPublic = isPublic
            this.messageId = messageId
            this.timestampSeconds = timestampSeconds
            this.operation = operation
            this.messageSenderAvatar = messageSenderAvatar
            this.messageSenderId = messageSenderId
            this.messageSenderUsername = messageSenderUsername
            this.messageContent = messageContent
            this.messageAttachment = messageAttachment
            this.messageCreatedTime = messageCreatedTime
        }
    }

    suspend fun writeCustomStatus(
        clanId: Long,
        status: String,
        timeReset: Int,
        noClear: Boolean
    ): Envelope = send {
        this.customStatusEvent = customStatusEvent {
            this.clanId = clanId
            this.status = status
            this.timeReset = timeReset
            this.noClear = noClear
        }
    }

    suspend fun markAsRead(
        channelId: Long,
        categoryId: Long = 0L,
        clanId: Long = 0L
    ): Envelope = send {
        this.markAsRead = markAsRead {
            this.channelId = channelId
            this.categoryId = categoryId
            this.clanId = clanId
        }
    }

    suspend fun followUsers(userIds: List<Long>): Envelope = send {
        this.statusFollow = statusFollow {
            this.userIds.addAll(userIds)
        }
    }

    suspend fun unfollowUsers(userIds: List<Long>): Envelope = send {
        this.statusUnfollow = statusUnfollow {
            this.userIds.addAll(userIds)
        }
    }

    suspend fun updateStatus(status: String): Envelope = send {
        this.statusUpdate = statusUpdate {
            this.status = StringValue.of(status)
        }
    }

    suspend fun forwardWebrtcSignaling(
        receiverId: Long,
        dataType: Int,
        jsonData: String,
        channelId: Long,
        callerId: Long
    ): Envelope = send {
        this.webrtcSignalingFwd = webrtcSignalingFwd {
            this.receiverId = receiverId
            this.dataType = dataType
            this.jsonData = jsonData
            this.channelId = channelId
            this.callerId = callerId
        }
    }

    suspend fun makeCallPush(
        receiverId: Long,
        jsonData: String,
        channelId: Long,
        callerId: Long
    ): Envelope {
        return send {
            this.incomingCallPush = incomingCallPush {
                this.receiverId = receiverId
                this.jsonData = jsonData
                this.channelId = channelId
                this.callerId = callerId
            }
        }
    }

    suspend fun writeVoiceReaction(
        emojis: List<String>,
        channelId: Long
    ): Envelope = send {
        this.voiceReactionSend = voiceReactionSend {
            this.emojis.addAll(emojis)
            this.channelId = channelId
        }
    }

    suspend fun checkDuplicateName(
        name: String,
        conditionId: Long,
        type: Int,
        clanId: Long
    ): Envelope = send {
        this.checkNameExistedEvent = checkNameExistedEvent {
            this.name = name
            this.conditionId = conditionId
            this.type = type
            this.clanId = clanId
        }
    }

    suspend fun checkDuplicateClanName(name: String): Boolean {
        val env = checkDuplicateName(name = name, conditionId = 0L, type = TYPE_CHECK_CLAN, clanId = 0L)
        if (env.messageCase != Envelope.MessageCase.CHECK_NAME_EXISTED_EVENT) {
            return false
        }
        val result = env.checkNameExistedEvent
        return result.type == TYPE_CHECK_CLAN && result.exist
    }

    private fun resolveHost(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        s = s.substringBefore('/').substringBefore('?')
        val host = s.substringBefore(':')
        return host.ifBlank { null }
    }

    private fun resolvePort(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        s = s.substringBefore('/').substringBefore('?')
        val colon = s.indexOf(':')
        if (colon < 0) return null
        return s.substring(colon + 1).toIntOrNull()
    }

    private fun doConnect(wsUrl: String, token: String, tcpUrl: String?) {
        synchronized(connectLock) {
            cancelReconnectHealthReset()
            cancelConnectReadyTimeout()
            transportReadyPending = false
            confirmedInboundGen = 0
            lastConfirmedInboundAtMs = 0L
            joinedChannelGenerations.clear()
            transport?.close()
            transport = null
            val serverHost = resolveHost(tcpUrl)
            val host = serverHost
                ?: if (BuildConfig.MEZON_ABRIDGED_FALLBACK) resolveHost(wsUrl) else null
            if (host.isNullOrBlank()) {
                _connectionState.value = ConnectionState.DISCONNECTED
                Log.i(TAG, "Abridged TCP unavailable (no tcp_url from server) - using HTTP fallback, keeping session")
                return
            }
            if (serverHost.isNullOrBlank()) {
                Log.i(TAG, "Abridged TCP: no tcp_url from server, falling back to ws host=$host")
            }

            _connectionState.value = ConnectionState.CONNECTING
            connectGen++
            socketTokenFingerprint = tokenFp(token)

            val port = resolvePort(tcpUrl) ?: BuildConfig.MEZON_TCP_PORT
            val credential = token

            Log.d(TAG, "[ABRIDGED] connecting host=$host port=$port cred=JWT-token (transport=abridged-tcp, gen=$connectGen)")

            val network = currentNetwork ?: networkMonitor.activeNetwork.value
            val t = AbridgedTcpTransport()
            transport = t
            t.onOpen = { handleTransportOpen(t) }
            t.onClose = { wasClean -> handleTransportClose(t, wasClean) }
            t.onError = { error -> handleTransportError(t, error) }
            t.onEvents = { events -> handleTransportEvents(t, events) }
            t.connect(host, port, credential, network)
        }
    }

    private fun handleTransportOpen(t: AbridgedTcpTransport) {
        Log.d(TAG, "[ABRIDGED] transport OPEN — TLS up + handshake sent")
        synchronized(connectLock) {
            if (t !== transport) {
                Log.d(TAG, "Ignoring onOpen(non-active)")
                return
            }
            transportReadyPending = true
            scheduleConnectReadyTimeout(t)
            lastPingSentAtMs = System.currentTimeMillis()
            t.sendPing(nextCid())
            Log.d(TAG, "[ABRIDGED] → readiness ping sent gen=$connectGen")
        }
    }

    private fun handleTransportError(t: AbridgedTcpTransport, error: Throwable) {
        if (t !== transport) return
        Log.e(TAG, "Abridged transport error: ${error.message} (type=${error.javaClass.simpleName})")
        sentryReporter.logSocketFailure("connection", error)
    }

    private fun handleTransportClose(t: AbridgedTcpTransport, wasClean: Boolean) {
        Log.d(TAG, "Closed (abridged) wasClean=$wasClean")
        synchronized(connectLock) {
            if (t !== transport) {
                Log.d(TAG, "Ignoring onClose(non-active)")
                return
            }
            val rejectionSuspect = transportReadyPending
            transportReadyPending = false
            cancelConnectReadyTimeout()
            cancelReconnectHealthReset()
            _connectionState.value = ConnectionState.DISCONNECTED
            heartbeatJob?.cancel()
            transport = null
            cancelAllPending("Connection closed")

            if (rejectionSuspect) {
                forceRefreshNextReconnect = true
                Log.w(TAG, "Abridged handshake closed before ack - refreshing session and retrying; HTTP fallback active, session kept")
            }
            if (!userDisconnected) scheduleReconnect()
        }
    }

    private fun handleTransportEvents(t: AbridgedTcpTransport, events: List<AbridgedParsedEvent>) {
        if (t !== transport || events.isEmpty()) return
        confirmInboundReadiness(t)
        for (event in events) {
            when (event) {
                is AbridgedParsedEvent.Pong -> {
                    val now = System.currentTimeMillis()
                    val rtt = if (lastPingSentAtMs > 0) now - lastPingSentAtMs else -1
                    lastPongAtMs = now
                    Log.d(TAG, "[ABRIDGED] ← pong (rtt=${rtt}ms) — heartbeat healthy")
                }
                is AbridgedParsedEvent.ApiResponse -> {
                    handleApiResponse(event.cid, event.code, event.payload)
                }
                is AbridgedParsedEvent.Realtime -> {
                    handleRealtime(event.payload)
                }
            }
        }
    }

    private fun confirmInboundReadiness(t: AbridgedTcpTransport) {
        val generation: Int
        val firstConfirmation: Boolean
        synchronized(connectLock) {
            if (t !== transport) return
            generation = connectGen
            firstConfirmation = confirmedInboundGen != generation
            confirmedInboundGen = generation
            lastConfirmedInboundAtMs = System.currentTimeMillis()
        }
        if (firstConfirmation) {
            Log.d(TAG, "[ABRIDGED] realtime readiness confirmed by inbound frame gen=$generation")
        }
    }

    private fun scheduleConnectReadyTimeout(t: AbridgedTcpTransport) {
        cancelConnectReadyTimeout()
        connectReadyTimeoutJob = scope.launch {
            delay(CONNECT_ACK_GRACE_MS)
            handleTransportReadyTimeout(t)
        }
    }

    private fun handleTransportReadyTimeout(t: AbridgedTcpTransport) {
        markTransportReady(t)
    }

    private fun markTransportReady(t: AbridgedTcpTransport): Boolean {
        val emitReconnect: Boolean
        val readyGen: Int
        synchronized(connectLock) {
            if (t !== transport) return false
            if (_connectionState.value == ConnectionState.CONNECTED) return true
            if (_connectionState.value != ConnectionState.CONNECTING || !transportReadyPending) return false

            transportReadyPending = false
            cancelConnectReadyTimeout()
            _connectionState.value = ConnectionState.CONNECTED
            isReconnecting = false
            lastPongAtMs = System.currentTimeMillis()
            cancelReconnectHealthReset()
            reconnectHealthResetJob = scope.launch {
                delay(STABLE_CONNECTION_RESET_MS)
                synchronized(connectLock) {
                    if (_connectionState.value == ConnectionState.CONNECTED && !userDisconnected) {
                        reconnectFailCount = 0
                        reconnectDelayMs = RECONNECT_MIN_MS
                    }
                }
            }
            readyGen = connectGen
            emitReconnect = hasConnectedBefore
            hasConnectedBefore = true
        }

        Log.d(TAG, "[ABRIDGED] transport CONNECTED after handshake grace gen=$readyGen")
        if (emitReconnect) {
            Log.d(TAG, "Socket reconnected — emitting reconnect event gen=$readyGen")
            _reconnected.tryEmit(Unit)
        }
        startHeartbeat()
        return true
    }

    private fun handleApiResponse(cid: Int, code: Int, payload: ByteArray) {
        val deferred = pendingApiRequests.remove(cid) ?: return
        if (code == 0) {
            deferred.complete(payload)
        } else {
            val msg = if (payload.isNotEmpty()) String(payload, Charsets.UTF_8) else ""
            deferred.completeExceptionally(
                SocketRpcServerException("Server error code=$code msg='$msg'", code)
            )
        }
    }

    private fun handleRealtime(payload: ByteArray) {
        try {
            handleEnvelope(Envelope.parseFrom(payload))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Envelope", e)
            sentryReporter.logSocketFailure("parse_envelope", e)
        }
    }

    private fun handleEnvelope(envelope: Envelope) {
        val cid = envelope.cid
        val case = envelope.messageCase

        if (cid != 0) {
            val deferred = pendingRequests.remove(cid)
            if (deferred != null) {
                if (case == Envelope.MessageCase.ERROR) {
                    deferred.completeExceptionally(
                        RuntimeException("Server error: ${envelope.error.message}")
                    )
                } else {
                    deferred.complete(envelope)
                }
                return
            }
            val apiDeferred = pendingApiRequests.remove(cid)
            if (apiDeferred != null) {
                if (envelope.messageCase == Envelope.MessageCase.ERROR) {
                    val error = envelope.error
                    apiDeferred.completeExceptionally(
                        SocketRpcServerException("Server error: ${error.message}", error.code)
                    )
                } else when (envelope.messageCase) {
                    Envelope.MessageCase.CHANNEL_MESSAGE_ACK -> {
                        apiDeferred.complete(envelope.channelMessageAck.toByteArray())
                    }
                    else -> apiDeferred.complete(ByteArray(0))
                }
                return
            }
        }

        when (case) {
            Envelope.MessageCase.PONG -> {
                lastPongAtMs = System.currentTimeMillis()
                return
            }
            Envelope.MessageCase.PING -> {
                sendFireAndForget(envelope { this.pong = pong {} })
                return
            }
            Envelope.MessageCase.REFRESH_SESSION_EVENT -> {
                val refreshed = envelope.refreshSessionEvent
                scope.launch {
                    try {
                        sessionManager.applyRefreshedSession(refreshed)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to apply refresh_session_event", e)
                    }
                }
                return
            }
            else -> Unit
        }

        if (BuildConfig.DEBUG) {
            when (case) {
                Envelope.MessageCase.MESSAGE_TYPING_EVENT,
                Envelope.MessageCase.STATUS_PRESENCE_EVENT -> Unit
                else -> Log.d(TAG, "Event: $case")
            }
        }
        if (!_events.tryEmit(envelope)) {
            scope.launch { _events.emit(envelope) }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (_connectionState.value != ConnectionState.CONNECTED) break

                val sinceLastPong = System.currentTimeMillis() - lastPongAtMs
                if (sinceLastPong > PONG_TIMEOUT_MS) {
                    Log.w(TAG, "[ABRIDGED] pong timeout: no pong ${sinceLastPong}ms (limit=${PONG_TIMEOUT_MS}ms) — forcing reconnect")
                    handleDeadConnection("heartbeat pong timeout")
                    break
                }

                val t = transport
                if (t == null) {
                    handleDeadConnection("heartbeat transport null")
                    break
                }
                lastPingSentAtMs = System.currentTimeMillis()
                t.sendPing(nextCid())
                Log.d(TAG, "[ABRIDGED] → ping sent (sinceLastPong=${sinceLastPong}ms)")
            }
        }
    }

    private fun handleDeadConnection(reason: String) {
        synchronized(connectLock) {
            if (_connectionState.value == ConnectionState.DISCONNECTED) return
            val t = transport
            transport = null
            _connectionState.value = ConnectionState.DISCONNECTED
            heartbeatJob?.cancel()
            cancelReconnectHealthReset()
            cancelConnectReadyTimeout()
            transportReadyPending = false
            cancelAllPending(reason)
            t?.close()
            if (!userDisconnected) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        synchronized(connectLock) {
            if (currentWsUrl == null || currentToken == null) return
            if (isReconnecting) return

            reconnectFailCount++

            if (reconnectFailCount > MAX_RECONNECT_FAILS) {
                Log.w(TAG, "Reconnect: $MAX_RECONNECT_FAILS attempts failed — continuing with max-interval (${RECONNECT_MAX_MS}ms) retry")
                reconnectFailCount = MAX_RECONNECT_FAILS
                reconnectDelayMs = RECONNECT_MAX_MS
            }

            isReconnecting = true
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                if (!networkMonitor.isOnline.value) {
                    Log.d(TAG, "Offline — waiting for network before reconnect")
                    networkMonitor.isOnline.first { it }
                    Log.d(TAG, "Network restored — proceeding with reconnect")
                    synchronized(connectLock) {
                        reconnectFailCount = 1
                        reconnectDelayMs = RECONNECT_MIN_MS
                    }
                }

                if (userDisconnected) {
                    synchronized(connectLock) { isReconnecting = false }
                    return@launch
                }

                val jitter = Random.nextLong(JITTER_RANGE_MS)
                val baseMs = synchronized(connectLock) { reconnectDelayMs }
                val delayMs = baseMs + jitter
                val totalAttempts = totalReconnectAttempts.incrementAndGet()
                Log.d(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectFailCount/$MAX_RECONNECT_FAILS, totalSinceProcessStart=$totalAttempts)")
                delay(delayMs)
                synchronized(connectLock) {
                    reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_MAX_MS)
                }

                try {
                    val session = if (forceRefreshNextReconnect) {
                        Log.d(TAG, "Force-refreshing session before reconnect")
                        forceRefreshNextReconnect = false
                        try {
                            sessionManager.refresh()
                        } catch (e: Exception) {
                            Log.w(TAG, "Forced refresh failed, falling back to requireValidSession", e)
                            sessionManager.requireValidSession()
                        }
                    } else {
                        sessionManager.requireValidSession()
                    }
                    synchronized(connectLock) {
                        if (userDisconnected) {
                            isReconnecting = false
                            return@launch
                        }
                        currentWsUrl = session.wsUrl
                        currentToken = session.token
                        currentTcpUrl = session.tcpUrl
                        doConnect(session.wsUrl, session.token, session.tcpUrl)
                        isReconnecting = false
                    }
                } catch (e: com.mezon.mobile.session.SessionExpiredException) {
                    Log.e(TAG, "Session expired, stopping reconnect — logout will be triggered")
                    synchronized(connectLock) {
                        currentWsUrl = null
                        currentToken = null
                        isReconnecting = false
                        reconnectFailCount = 0
                    }
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "Network error during reconnect, will wait for network", e)
                    synchronized(connectLock) { isReconnecting = false }
                    scheduleReconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error before reconnect, retrying...", e)
                    synchronized(connectLock) { isReconnecting = false }
                    scheduleReconnect()
                }
            }
        }
    }

    private fun cancelAllPending(reason: String) {
        pendingRequests.forEach { (_, deferred) ->
            deferred.completeExceptionally(RuntimeException(reason))
        }
        pendingRequests.clear()
        pendingApiRequests.forEach { (_, deferred) ->
            deferred.completeExceptionally(RuntimeException(reason))
        }
        pendingApiRequests.clear()
    }
}
