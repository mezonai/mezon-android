package com.mezon.mobile.network

import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.session.SessionManager
import android.util.Log
import com.google.protobuf.StringValue
import com.mezon.mezon.api.ListClanBadgeCountResponse
import com.mezon.mezon.api.MessageAttachment
import com.mezon.mezon.api.MessageMention
import com.mezon.mezon.api.MessageRef
import com.mezon.mezon.rtapi.EnvelopeKt
import com.mezon.mezon.rtapi.Envelope
import com.mezon.mezon.rtapi.channelJoin
import com.mezon.mezon.rtapi.channelLeave
import com.mezon.mezon.rtapi.channelMessageRemove
import com.mezon.mezon.rtapi.channelMessageSend
import com.mezon.mezon.rtapi.channelMessageUpdate
import com.mezon.mezon.rtapi.checkNameExistedEvent
import com.mezon.mezon.rtapi.clanJoin
import com.mezon.mezon.rtapi.customStatusEvent
import com.mezon.mezon.rtapi.envelope
import com.mezon.mezon.rtapi.incomingCallPush
import com.mezon.mezon.rtapi.lastPinMessageEvent
import com.mezon.mezon.rtapi.lastSeenMessageEvent
import com.mezon.mezon.rtapi.listDataSocket
import com.mezon.mezon.rtapi.markAsRead
import com.mezon.mezon.rtapi.messageTypingEvent
import com.mezon.mezon.rtapi.ping
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MezonSocket @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val sessionManager: SessionManager,
    private val networkMonitor: NetworkMonitor,
    @ApplicationScope private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MezonSocket"
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val RECONNECT_MIN_MS = 1_000L
        private const val RECONNECT_MAX_MS = 30_000L
        private const val JITTER_RANGE_MS = 1_000L
        private const val MAX_RECONNECT_FAILS = 6
        private const val SEND_TIMEOUT_MS = 10_000L

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

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var currentWsUrl: String? = null
    private var currentToken: String? = null

    private val cidCounter = AtomicInteger(0)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<Envelope>>()
    private var reconnectDelayMs = RECONNECT_MIN_MS
    private var reconnectFailCount = 0
    @Volatile private var isReconnecting = false
    @Volatile private var userDisconnected = false
    @Volatile private var hasConnectedBefore = false

    fun connect(wsUrl: String, token: String) {
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

        currentWsUrl = wsUrl
        currentToken = token

        doConnect(wsUrl, token)
    }

    fun reconnectIfNeeded() {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return
        if (currentWsUrl == null || currentToken == null) return
        if (isReconnecting) return
        if (!networkMonitor.isOnline.value) {
            Log.d(TAG, "App resumed but offline, skipping reconnect")
            return
        }

        Log.d(TAG, "App resumed — socket disconnected, triggering reconnect")
        reconnectFailCount = 0
        reconnectDelayMs = RECONNECT_MIN_MS
        scheduleReconnect()
    }

    fun disconnect() {
        Log.d(TAG, "Disconnect requested")
        userDisconnected = true
        hasConnectedBefore = false
        currentWsUrl = null
        currentToken = null
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        isReconnecting = false
        reconnectFailCount = 0
        reconnectDelayMs = RECONNECT_MIN_MS
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        cancelAllPending("Disconnected")
    }

    suspend fun send(block: EnvelopeKt.Dsl.() -> Unit): Envelope {
        val cid = cidCounter.incrementAndGet()
        val env = envelope {
            this.cid = cid
            block()
        }

        val ws = webSocket
            ?: throw IllegalStateException("WebSocket not connected")

        val deferred = CompletableDeferred<Envelope>()
        pendingRequests[cid] = deferred

        val bytes = env.toByteArray().toByteString()
        val sent = ws.send(bytes)
        if (!sent) {
            pendingRequests.remove(cid)
            throw IllegalStateException("Failed to enqueue WebSocket message")
        }

        Log.d(TAG, "Sent: cid=$cid, case=${env.messageCase}")

        return try {
            withTimeout(SEND_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingRequests.remove(cid)
            throw RuntimeException("Request timed out: cid=$cid", e)
        }
    }

    fun sendFireAndForget(env: Envelope) {
        val ws = webSocket ?: return
        ws.send(env.toByteArray().toByteString())
    }

    suspend fun joinClanChat(clanId: Long): Envelope = send {
        this.clanJoin = clanJoin { this.clanId = clanId }
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
            false
        }
    }

    suspend fun fetchListClanBadgeCountSocket(): ListClanBadgeCountResponse {
        val env = send {
            this.listDataSocket = listDataSocket { apiName = "ListClanBadgeCount" }
        }
        require(env.messageCase == Envelope.MessageCase.LIST_DATA_SOCKET) {
            "ListClanBadgeCount: expected LIST_DATA_SOCKET, got ${env.messageCase}"
        }
        val data = env.listDataSocket
        return if (data.hasClanBadgeCount()) data.clanBadgeCount else ListClanBadgeCountResponse.getDefaultInstance()
    }

    suspend fun joinChat(
        clanId: Long,
        channelId: Long,
        channelType: Int,
        isPublic: Boolean
    ): Envelope = send {
        this.channelJoin = channelJoin {
            this.clanId = clanId
            this.channelId = channelId
            this.channelType = channelType
            this.isPublic = isPublic
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
            mentions?.let { this.mentions.addAll(it) }
            attachments?.let { this.attachments.addAll(it) }
            references?.let { this.references.addAll(it) }
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
        isUpdateMsgTopic: Boolean = false
    ): Envelope = send {
        this.channelMessageUpdate = channelMessageUpdate {
            this.clanId = clanId
            this.channelId = channelId
            this.messageId = messageId
            this.content = content
            mentions?.let { this.mentions.addAll(it) }
            attachments?.let { this.attachments.addAll(it) }
            this.mode = mode
            this.isPublic = isPublic
            this.hideEditted = hideEditted
            this.topicId = topicId
            this.isUpdateMsgTopic = isUpdateMsgTopic
        }
    }

    suspend fun removeChatMessage(
        clanId: Long,
        channelId: Long,
        mode: Int,
        isPublic: Boolean,
        messageId: Long,
        hasAttachment: Boolean = false,
        topicId: Long = 0L,
        mentions: com.google.protobuf.ByteString = com.google.protobuf.ByteString.EMPTY,
        references: com.google.protobuf.ByteString = com.google.protobuf.ByteString.EMPTY
    ): Envelope = send {
        this.channelMessageRemove = channelMessageRemove {
            this.clanId = clanId
            this.channelId = channelId
            this.messageId = messageId
            this.mode = mode
            this.isPublic = isPublic
            this.hasAttachment = hasAttachment
            this.topicId = topicId
            this.mentions = mentions
            this.references = references
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
    ): Envelope = send {
        this.incomingCallPush = incomingCallPush {
            this.receiverId = receiverId
            this.jsonData = jsonData
            this.channelId = channelId
            this.callerId = callerId
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

    private fun doConnect(wsUrl: String, token: String) {
        webSocket?.close(1000, "Reconnecting")
        webSocket = null
        _connectionState.value = ConnectionState.CONNECTING

        val host = if (wsUrl.startsWith("ws://") || wsUrl.startsWith("wss://")) {
            wsUrl
        } else {
            "wss://$wsUrl"
        }
        val url = "$host/ws?token=$token&status=true&platform=1&lang=en&format=protobuf"
        Log.d(TAG, "Connecting to: $host/ws?token=***&status=true&platform=1&lang=en&format=protobuf")

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        webSocket = okHttpClient.newWebSocket(request, socketListener)
    }

    private val socketListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "Connected")
            _connectionState.value = ConnectionState.CONNECTED
            reconnectDelayMs = RECONNECT_MIN_MS
            reconnectFailCount = 0
            isReconnecting = false
            if (hasConnectedBefore) {
                Log.d(TAG, "Socket reconnected — emitting reconnect event")
                _reconnected.tryEmit(Unit)
            }
            hasConnectedBefore = true
            startHeartbeat()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                val envelope = Envelope.parseFrom(bytes.toByteArray())
                handleEnvelope(envelope)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse Envelope", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Server closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Closed: $code $reason")
            _connectionState.value = ConnectionState.DISCONNECTED
            heartbeatJob?.cancel()
            cancelAllPending("Connection closed")
            if (!userDisconnected) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}")
            _connectionState.value = ConnectionState.DISCONNECTED
            heartbeatJob?.cancel()
            cancelAllPending("Connection failed: ${t.message}")
            if (!userDisconnected) scheduleReconnect()
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
        }

        if (case == Envelope.MessageCase.PONG) {
            return
        }

        when (case) {
            Envelope.MessageCase.MESSAGE_TYPING_EVENT,
            Envelope.MessageCase.STATUS_PRESENCE_EVENT -> Unit
            else -> Log.d(TAG, "Event: $case")
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
                try {
                    val pingEnvelope = envelope { ping = ping {} }
                    sendFireAndForget(pingEnvelope)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send ping", e)
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (currentWsUrl == null || currentToken == null) return
        if (isReconnecting) return

        reconnectFailCount++

        if (reconnectFailCount > MAX_RECONNECT_FAILS) {
            Log.e(TAG, "Max reconnect attempts ($MAX_RECONNECT_FAILS) reached, giving up")
            reconnectFailCount = 0
            isReconnecting = false
            return
        }

        isReconnecting = true
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (!networkMonitor.isOnline.value) {
                Log.d(TAG, "Offline — waiting for network before reconnect")
                networkMonitor.onlineEvents.first { it }
                Log.d(TAG, "Network restored — proceeding with reconnect")
                reconnectFailCount = 1
                reconnectDelayMs = RECONNECT_MIN_MS
            }

            if (userDisconnected) return@launch

            val jitter = Random.nextLong(JITTER_RANGE_MS)
            val delayMs = reconnectDelayMs + jitter
            Log.d(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectFailCount/$MAX_RECONNECT_FAILS, base=${reconnectDelayMs}ms, jitter=${jitter}ms)")
            delay(delayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_MAX_MS)

            try {
                val session = sessionManager.requireValidSession()
                currentWsUrl = session.wsUrl
                currentToken = session.token
                isReconnecting = false
                doConnect(session.wsUrl, session.token)
            } catch (e: com.mezon.mobile.session.SessionExpiredException) {
                Log.e(TAG, "Session expired, stopping reconnect — logout will be triggered")
                currentWsUrl = null
                currentToken = null
                isReconnecting = false
                reconnectFailCount = 0
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Network error during reconnect, will wait for network", e)
                isReconnecting = false
                scheduleReconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error before reconnect, retrying...", e)
                isReconnecting = false
                scheduleReconnect()
            }
        }
    }

    private fun cancelAllPending(reason: String) {
        pendingRequests.forEach { (_, deferred) ->
            deferred.completeExceptionally(RuntimeException(reason))
        }
        pendingRequests.clear()
    }
}
