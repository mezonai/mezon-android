# Mezon Android - High-Performance Kotlin Architecture

## Performance Targets

| Metric | React Native (current) | Kotlin (target) |
|--------|----------------------|-----------------|
| Cold startup | 2.5-3.5s | < 1s |
| Message list FPS | 30-45 fps | 55-60 fps |
| Image load time | 800-1500ms | 200-400ms |
| Protobuf parse/msg | 5-15ms (JS) | 0.5-2ms (native) |
| Memory/100 messages | 15-25 MB | 5-8 MB |
| Idle battery drain | 15-20%/hr | 3-5%/hr |
| WebSocket reconnect | 1-6s | < 1s |

---

## 1. Architecture: Pragmatic Clean + MVI + Shared State

```
┌─────────────────────────────────────────────────────────┐
│  UI Layer                                               │
│  Compose (screens/forms) + RecyclerView (message list)  │
├─────────────────────────────────────────────────────────┤
│  Presentation Layer                                     │
│  MVI: Intent → ViewModel → UiState → UI                │
├─────────────────────────────────────────────────────────┤
│  Shared State Layer           ← CORE LAYER              │
│  StateHolder @Singleton (SharedFlow/StateFlow)          │
│  Cross-feature reactive state for real-time events      │
├─────────────────────────────────────────────────────────┤
│  Data Layer                                             │
│  Repository + Room (WAL) + Paging 3                     │
├─────────────────────────────────────────────────────────┤
│  Network Layer                                          │
│  Cronet (REST/HTTP3) + OkHttp (WebSocket) + Protobuf   │
├─────────────────────────────────────────────────────────┤
│  DI: Hilt  │  Startup: Baseline Profiles + App Startup  │
└─────────────────────────────────────────────────────────┘
```

### Why not standard Clean Architecture

Standard Clean Architecture has UseCase layer between ViewModel and Repository.
For Mezon (real-time chat), 90% of UseCases are pass-through:

```kotlin
// BAD: boilerplate pass-through
class GetMessagesUseCase(private val repo: MessageRepository) {
    suspend operator fun invoke(channelId: String) = repo.getMessages(channelId)
}
```

In this architecture:
- ViewModel calls Repository directly (90% of cases)
- UseCase only exists when there is real business logic (composite operations, validation)
- Shared StateHolder replaces Redux global store for cross-feature reactive state

### Why Shared State Layer

A single socket event like "new message" must update 5+ features simultaneously:

```
Socket: "new message" →
  ├── Chat screen (new message in list)
  ├── Home screen (unread badge)
  ├── Channel list (last message preview)
  ├── DM list (reorder by latest)
  └── Notification (show push)
```

StateHolder is a @Singleton that holds StateFlow/SharedFlow, injected into any ViewModel that needs it. One source of truth, multiple consumers.

---

## 2. Project Structure (12 modules)

```
com.mezon.android/
├── app/                          # Single Activity, navigation, Hilt entry
│   ├── MezonApp.kt
│   ├── MainActivity.kt
│   ├── navigation/
│   └── di/
│
├── core/
│   ├── core-network/            # Mezon SDK: REST (Cronet) + WebSocket (OkHttp) + Protobuf
│   │   ├── api/                 # REST client (auto-gen from proto)
│   │   ├── socket/              # WebSocket + event batching
│   │   ├── proto/               # Generated protobuf models
│   │   ├── auth/                # Session, token refresh
│   │   └── di/
│   │
│   ├── core-data/               # Repositories + Room DB + Mappers
│   │   ├── repository/
│   │   ├── db/
│   │   │   ├── dao/
│   │   │   ├── entity/
│   │   │   └── MezonDatabase.kt
│   │   └── mapper/
│   │
│   ├── core-state/              # Shared StateHolders (cross-feature reactive state)
│   │   ├── MessageStateHolder.kt
│   │   ├── ChannelStateHolder.kt
│   │   ├── PresenceStateHolder.kt
│   │   ├── TypingStateHolder.kt
│   │   ├── VoiceStateHolder.kt
│   │   ├── NotificationStateHolder.kt
│   │   └── SocketEventDispatcher.kt
│   │
│   ├── core-ui/                 # Compose components + Theme + RecyclerView adapters
│   │   ├── compose/             # Compose components
│   │   ├── recyclerview/        # High-perf RecyclerView for message list
│   │   └── theme/
│   │
│   └── core-common/             # Extensions, utils, Result wrapper
│
├── feature/
│   ├── feature-auth/            # Login, OTP, QR
│   ├── feature-home/            # Home + Clan + Channel list
│   ├── feature-chat/            # Messages + DM (shared message rendering)
│   ├── feature-voice/           # Voice/Video (LiveKit native)
│   ├── feature-social/          # Friends + Profile + User management
│   └── feature-settings/        # Settings + Roles + Notifications
│
└── service/
    ├── FcmService.kt
    ├── CallService.kt
    └── SocketService.kt         # Foreground service giữ WebSocket alive
```

### Module Dependencies

```
feature-*    → core-state, core-ui, core-common
core-state   → core-data (writes to DB), core-network (listens to socket)
core-data    → core-network (API calls), core-common
core-network → core-common (pure network, no Android UI)
app          → all feature modules
```

---

## 3. Network Layer (High Performance)

### 3.1 REST API: Cronet (HTTP/3, QUIC)

Cronet (Chromium network stack) supports HTTP/3 + QUIC + Brotli compression. Already used in current RN app via NativeModule.

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideCronetEngine(@ApplicationContext context: Context): CronetEngine {
        return CronetEngine.Builder(context)
            .enableQuic(true)
            .enableBrotli(true)
            .enableHttp2(true)
            .setStoragePath(context.cacheDir.absolutePath)
            .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 50 * 1024 * 1024)
            .build()
    }

    @Provides
    @Singleton
    fun provideHttpClient(
        cronetEngine: CronetEngine,
        sessionManager: SessionManager,
    ): HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                addInterceptor(CronetInterceptor.newBuilder(cronetEngine).build())
            }
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(Auth) {
            bearer {
                loadTokens { BearerTokens(sessionManager.accessToken, sessionManager.refreshToken) }
                refreshTokens {
                    val session = sessionManager.refresh()
                    BearerTokens(session.token, session.refreshToken)
                }
            }
        }
        defaultRequest { url(sessionManager.apiUrl) }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
```

### 3.2 WebSocket: OkHttp + Protobuf + Event Batching

```kotlin
@Singleton
class MezonSocket @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val sessionManager: SessionManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var webSocket: WebSocket? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _rawEvents = MutableSharedFlow<Envelope>(extraBufferCapacity = 256)

    val channelMessages: SharedFlow<ChannelMessage> = _rawEvents
        .filter { it.hasChannelMessage() }
        .map { it.channelMessage.toDomain() }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    val typingEvents: SharedFlow<List<MessageTypingEvent>> = _rawEvents
        .filter { it.hasMessageTyping() }
        .map { it.messageTyping.toDomain() }
        .bufferTimeout(count = 10, duration = 200.milliseconds)
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    val presenceEvents: SharedFlow<List<ChannelPresenceEvent>> = _rawEvents
        .filter { it.hasChannelPresence() }
        .map { it.channelPresence.toDomain() }
        .bufferTimeout(count = 20, duration = 200.milliseconds)
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    val voiceEvents: SharedFlow<VoiceEvent> = _rawEvents
        .filter { it.hasVoiceJoined() || it.hasVoiceLeaved() || it.hasVoiceEnded() }
        .map { it.toVoiceEvent() }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    val notificationEvents: SharedFlow<NotificationEvent> = _rawEvents
        .filter { it.hasNotification() }
        .map { it.notification.toDomain() }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<Envelope>>()

    fun connect() {
        val url = "${sessionManager.wsUrl}?token=${sessionManager.accessToken}&format=protobuf&status=true&platform=1"

        webSocket = okHttpClient.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    _connectionState.value = ConnectionState.Connected
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    val envelope = Envelope.parseFrom(bytes.toByteArray())

                    val cid = envelope.cid
                    if (cid.isNotEmpty()) {
                        pendingResponses.remove(cid)?.complete(envelope)
                        return
                    }

                    scope.launch { _rawEvents.emit(envelope) }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    _connectionState.value = ConnectionState.Error(t)
                    reconnect()
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        )
    }

    suspend fun <T> sendAndAwait(
        envelope: Envelope,
        transform: (Envelope) -> T,
    ): T {
        val cid = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Envelope>()
        pendingResponses[cid] = deferred

        val tagged = envelope.toBuilder().setCid(cid).build()
        webSocket?.send(ByteString.of(*tagged.toByteArray()))

        return withTimeout(10_000) { transform(deferred.await()) }
    }

    suspend fun sendMessage(
        clanId: String,
        channelId: String,
        content: String,
        mode: Int = 0,
    ): ChannelMessageAck = sendAndAwait(
        envelope {
            channelMessageSend = channelMessageSend {
                this.clanId = clanId
                this.channelId = channelId
                this.content = content
                this.mode = mode
            }
        }
    ) { it.channelMessageAck.toDomain() }

    private fun reconnect() {
        scope.launch {
            var attempt = 0
            while (attempt < 6 && connectionState.value !is ConnectionState.Connected) {
                val delay = (1000L * (1 shl attempt.coerceAtMost(4))).coerceAtMost(30_000L)
                delay(delay + Random.nextLong(0, 1000))
                attempt++
                if (sessionManager.isTokenExpired()) sessionManager.refresh()
                connect()
            }
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        _connectionState.value = ConnectionState.Disconnected
    }
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connected : ConnectionState
    data class Error(val throwable: Throwable) : ConnectionState
}
```

### 3.3 Event Batching Utility

High-frequency events (typing, presence) are batched every 200ms to reduce UI updates and battery drain by 50%.

```kotlin
fun <T> Flow<T>.bufferTimeout(
    count: Int,
    duration: Duration,
): Flow<List<T>> = channelFlow {
    val buffer = mutableListOf<T>()
    val mutex = Mutex()
    var timerJob: Job? = null

    collect { item ->
        mutex.withLock {
            buffer.add(item)
            if (buffer.size >= count) {
                send(buffer.toList())
                buffer.clear()
                timerJob?.cancel()
                timerJob = null
            } else if (timerJob == null) {
                timerJob = launch {
                    delay(duration)
                    mutex.withLock {
                        if (buffer.isNotEmpty()) {
                            send(buffer.toList())
                            buffer.clear()
                        }
                        timerJob = null
                    }
                }
            }
        }
    }
}
```

### 3.4 Session Management

```kotlin
@Singleton
class SessionManager @Inject constructor(
    private val dataStore: DataStore<MezonPreferences>,
    private val apiClient: Lazy<MezonApiClient>,
) {
    @Volatile var accessToken: String = ""
        private set
    @Volatile var refreshToken: String = ""
        private set
    @Volatile var userId: String = ""
        private set
    @Volatile var apiUrl: String = ""
        private set
    @Volatile var wsUrl: String = ""
        private set

    private val refreshMutex = Mutex()
    private var lastRefreshToken: String = ""
    private var failCount = 0

    suspend fun saveSession(session: MezonSession) {
        accessToken = session.token
        refreshToken = session.refreshToken
        userId = session.userId
        apiUrl = session.apiUrl
        wsUrl = session.wsUrl
        dataStore.updateData { prefs ->
            prefs.toBuilder()
                .setAccessToken(session.token)
                .setRefreshToken(session.refreshToken)
                .setUserId(session.userId)
                .setApiUrl(session.apiUrl)
                .setWsUrl(session.wsUrl)
                .build()
        }
    }

    suspend fun refresh(): MezonSession = refreshMutex.withLock {
        if (refreshToken == lastRefreshToken && failCount >= 5) {
            throw SessionExpiredException()
        }
        if (refreshToken != lastRefreshToken) {
            lastRefreshToken = refreshToken
            failCount = 0
        }
        try {
            val newSession = apiClient.get().sessionRefresh(accessToken, refreshToken)
            saveSession(newSession)
            failCount = 0
            newSession
        } catch (e: Exception) {
            failCount++
            throw e
        }
    }

    fun isTokenExpired(): Boolean {
        if (accessToken.isBlank()) return true
        return try {
            val parts = accessToken.split(".")
            val payload = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP)
            val json = JSONObject(String(payload))
            System.currentTimeMillis() / 1000 > json.getLong("exp")
        } catch (_: Exception) { true }
    }

    suspend fun restore(): Boolean {
        val prefs = dataStore.data.first()
        if (prefs.accessToken.isBlank()) return false
        accessToken = prefs.accessToken
        refreshToken = prefs.refreshToken
        userId = prefs.userId
        apiUrl = prefs.apiUrl
        wsUrl = prefs.wsUrl
        return !isTokenExpired()
    }
}
```

---

## 4. Shared State Layer (core-state)

### 4.1 MessageStateHolder

```kotlin
@Singleton
class MessageStateHolder @Inject constructor(
    private val socket: MezonSocket,
    private val messageDao: MessageDao,
    private val apiClient: MezonApiClient,
    private val mapper: MessageMapper,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _channelMessages = ConcurrentHashMap<String, MutableStateFlow<List<Message>>>()

    fun messagesForChannel(channelId: String): StateFlow<List<Message>> {
        return _channelMessages.getOrPut(channelId) { MutableStateFlow(emptyList()) }
    }

    fun lastMessagePerChannel(): Flow<Map<String, Message>> {
        return combine(_channelMessages.values.toList()) { arrays ->
            arrays.flatMap { it.toList() }
                .groupBy { it.channelId }
                .mapValues { (_, msgs) -> msgs.maxBy { it.createdAt } }
        }
    }

    fun unreadCount(channelId: String, lastReadTimestamp: Long): Flow<Int> {
        return messagesForChannel(channelId).map { msgs ->
            msgs.count { it.createdAt > lastReadTimestamp }
        }
    }

    init {
        scope.launch {
            socket.channelMessages.collect { msg ->
                messageDao.upsert(listOf(mapper.toEntity(msg)))

                val flow = _channelMessages.getOrPut(msg.channelId) { MutableStateFlow(emptyList()) }
                flow.update { current -> (listOf(msg) + current).take(500) }
            }
        }
    }

    suspend fun loadMessages(channelId: String, before: Long? = null, limit: Int = 50): List<Message> {
        val response = apiClient.listChannelMessages(channelId, before = before, limit = limit)
        val messages = response.messages.map { mapper.toDomain(it) }
        messageDao.upsert(response.messages.map { mapper.toEntity(it) })

        val flow = _channelMessages.getOrPut(channelId) { MutableStateFlow(emptyList()) }
        if (before == null) {
            flow.value = messages
        } else {
            flow.update { current -> current + messages }
        }
        return messages
    }

    fun evictChannel(channelId: String) {
        _channelMessages.remove(channelId)
    }
}
```

### 4.2 TypingStateHolder (with auto-expiry)

```kotlin
@Singleton
class TypingStateHolder @Inject constructor(
    private val socket: MezonSocket,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _typingUsers = ConcurrentHashMap<String, MutableStateFlow<Set<TypingUser>>>()

    fun typingUsersForChannel(channelId: String): StateFlow<Set<TypingUser>> {
        return _typingUsers.getOrPut(channelId) { MutableStateFlow(emptySet()) }
    }

    init {
        scope.launch {
            socket.typingEvents.collect { batch ->
                batch.forEach { event ->
                    val flow = _typingUsers.getOrPut(event.channelId) { MutableStateFlow(emptySet()) }
                    val user = TypingUser(event.userId, event.displayName, System.currentTimeMillis())
                    flow.update { it + user }

                    launch {
                        delay(3_000)
                        flow.update { users -> users.filter { it.userId != event.userId } }
                    }
                }
            }
        }
    }
}

data class TypingUser(val userId: String, val displayName: String, val timestamp: Long)
```

### 4.3 SocketEventDispatcher

```kotlin
@Singleton
class SocketEventDispatcher @Inject constructor(
    private val socket: MezonSocket,
    private val messageState: MessageStateHolder,
    private val typingState: TypingStateHolder,
    private val presenceState: PresenceStateHolder,
    private val voiceState: VoiceStateHolder,
    private val notificationState: NotificationStateHolder,
    private val channelState: ChannelStateHolder,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch { socket.voiceEvents.collect { voiceState.handle(it) } }
        scope.launch { socket.notificationEvents.collect { notificationState.handle(it) } }
        scope.launch { socket.presenceEvents.collect { batch -> batch.forEach { presenceState.handle(it) } } }
    }
}
```

---

## 5. Presentation Layer (MVI)

### 5.1 MVI Pattern

```kotlin
sealed interface ChatIntent {
    data class SendMessage(val content: String) : ChatIntent
    data class LoadMore(val before: Long) : ChatIntent
    data class React(val messageId: String, val emoji: String) : ChatIntent
    data class DeleteMessage(val messageId: String) : ChatIntent
    data object Retry : ChatIntent
}

sealed interface ChatUiState {
    data object Loading : ChatUiState
    data class Active(
        val messages: List<Message> = emptyList(),
        val typingUsers: Set<TypingUser> = emptySet(),
        val hasMore: Boolean = true,
        val isSending: Boolean = false,
    ) : ChatUiState
    data class Error(val message: String?) : ChatUiState
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messageState: MessageStateHolder,
    private val typingState: TypingStateHolder,
    private val socket: MezonSocket,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val channelId: String = savedStateHandle["channelId"]!!

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val uiState: StateFlow<ChatUiState> = _uiState

    init {
        viewModelScope.launch {
            messageState.loadMessages(channelId)
        }

        viewModelScope.launch {
            combine(
                messageState.messagesForChannel(channelId),
                typingState.typingUsersForChannel(channelId),
            ) { messages, typing ->
                ChatUiState.Active(messages = messages, typingUsers = typing)
            }.collect { _uiState.value = it }
        }
    }

    fun onIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.SendMessage -> viewModelScope.launch {
                _uiState.update { if (it is ChatUiState.Active) it.copy(isSending = true) else it }
                socket.sendMessage(clanId = "", channelId = channelId, content = intent.content)
                _uiState.update { if (it is ChatUiState.Active) it.copy(isSending = false) else it }
            }
            is ChatIntent.LoadMore -> viewModelScope.launch {
                messageState.loadMessages(channelId, before = intent.before)
            }
            is ChatIntent.React -> viewModelScope.launch {
                socket.sendReaction(channelId, intent.messageId, intent.emoji)
            }
            is ChatIntent.DeleteMessage -> viewModelScope.launch {
                socket.deleteMessage(channelId, intent.messageId)
            }
            is ChatIntent.Retry -> viewModelScope.launch {
                _uiState.value = ChatUiState.Loading
                messageState.loadMessages(channelId)
            }
        }
    }
}
```

---

## 6. UI Layer (High-Performance Rendering)

### 6.1 Message List: RecyclerView (not LazyColumn)

For chat message lists with 1000+ items, frequent updates, and complex item layouts,
RecyclerView outperforms LazyColumn by ~20% on initial render and provides better
scroll performance through ViewHolder pooling. Use Compose for everything else.

```kotlin
class MessageAdapter(
    private val onReact: (String, String) -> Unit,
    private val onLongPress: (Message) -> Unit,
) : ListAdapter<Message, MessageAdapter.ViewHolder>(MessageDiffCallback()) {

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        return when {
            message.attachments.isNotEmpty() -> VIEW_TYPE_MEDIA
            message.isSystemMessage -> VIEW_TYPE_SYSTEM
            else -> VIEW_TYPE_TEXT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return when (viewType) {
            VIEW_TYPE_MEDIA -> MediaMessageViewHolder(
                ComposeView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                }
            )
            VIEW_TYPE_SYSTEM -> SystemMessageViewHolder(/* inflate */)
            else -> TextMessageViewHolder(/* inflate */)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        const val VIEW_TYPE_TEXT = 0
        const val VIEW_TYPE_MEDIA = 1
        const val VIEW_TYPE_SYSTEM = 2
    }
}

class MessageDiffCallback : DiffUtil.ItemCallback<Message>() {
    override fun areItemsTheSame(old: Message, new: Message) = old.id == new.id
    override fun areContentsTheSame(old: Message, new: Message) = old == new
    override fun getChangePayload(old: Message, new: Message): Any? {
        if (old.reactions != new.reactions) return PAYLOAD_REACTIONS
        if (old.content != new.content) return PAYLOAD_CONTENT
        return null
    }
}
```

### 6.2 RecyclerView in Compose (interop)

```kotlin
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { ChatTopBar(onBack = onNavigateBack) },
        bottomBar = {
            MessageComposer(
                onSend = { viewModel.onIntent(ChatIntent.SendMessage(it)) },
                isSending = (uiState as? ChatUiState.Active)?.isSending == true,
            )
        },
    ) { padding ->
        when (val state = uiState) {
            is ChatUiState.Loading -> CenteredLoader()
            is ChatUiState.Active -> {
                Column(Modifier.padding(padding)) {
                    HighPerfMessageList(
                        messages = state.messages,
                        onLoadMore = { before ->
                            viewModel.onIntent(ChatIntent.LoadMore(before))
                        },
                        onReact = { msgId, emoji ->
                            viewModel.onIntent(ChatIntent.React(msgId, emoji))
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (state.typingUsers.isNotEmpty()) {
                        TypingIndicator(users = state.typingUsers.toList())
                    }
                }
            }
            is ChatUiState.Error -> ErrorView(
                message = state.message,
                onRetry = { viewModel.onIntent(ChatIntent.Retry) },
            )
        }
    }
}

@Composable
fun HighPerfMessageList(
    messages: List<Message>,
    onLoadMore: (Long) -> Unit,
    onReact: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    AndroidView(
        factory = { ctx ->
            RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx).apply {
                    reverseLayout = true
                    stackFromEnd = true
                }
                adapter = MessageAdapter(onReact = onReact, onLongPress = {})
                setHasFixedSize(false)
                itemAnimator = null

                setRecycledViewPool(RecycledViewPool().apply {
                    setMaxRecycledViews(MessageAdapter.VIEW_TYPE_TEXT, 30)
                    setMaxRecycledViews(MessageAdapter.VIEW_TYPE_MEDIA, 10)
                    setMaxRecycledViews(MessageAdapter.VIEW_TYPE_SYSTEM, 5)
                })

                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                        val lm = rv.layoutManager as LinearLayoutManager
                        val lastVisible = lm.findLastVisibleItemPosition()
                        val total = lm.itemCount
                        if (lastVisible >= total - 5 && total > 0) {
                            val oldest = (rv.adapter as MessageAdapter).currentList.lastOrNull()
                            oldest?.let { onLoadMore(it.createdAt) }
                        }
                    }
                })
            }
        },
        update = { rv ->
            (rv.adapter as MessageAdapter).submitList(messages)
        },
        modifier = modifier,
    )
}
```

### 6.3 Theme

```kotlin
@Composable
fun MezonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFF5865F2),
            surface = Color(0xFF1E1F22),
            background = Color(0xFF313338),
            onSurface = Color(0xFFDBDEE1),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF5865F2),
            surface = Color(0xFFFFFFFF),
            background = Color(0xFFF2F3F5),
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MezonTypography,
        content = content,
    )
}
```

---

## 7. Database Layer (Room + Performance)

### 7.1 Entity with Compound Indices

```kotlin
@Entity(
    tableName = "messages",
    indices = [
        Index("channel_id", "created_at"),
        Index("sender_id"),
        Index("clan_id", "channel_id"),
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "channel_id") val channelId: String,
    @ColumnInfo(name = "clan_id") val clanId: String,
    @ColumnInfo(name = "sender_id") val senderId: String,
    @ColumnInfo(name = "sender_name") val senderName: String,
    @ColumnInfo(name = "sender_avatar") val senderAvatar: String?,
    val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val attachments: String?,
    val mentions: String?,
    val reactions: String?,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
)

@Entity(
    tableName = "channels",
    indices = [Index("clan_id")]
)
data class ChannelEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "clan_id") val clanId: String,
    val name: String,
    val type: Int,
    @ColumnInfo(name = "last_message_id") val lastMessageId: String?,
    @ColumnInfo(name = "last_message_at") val lastMessageAt: Long?,
    @ColumnInfo(name = "unread_count") val unreadCount: Int = 0,
)
```

### 7.2 DAOs with Paging

```kotlin
@Dao
interface MessageDao {

    @Query("""
        SELECT * FROM messages
        WHERE channel_id = :channelId
        ORDER BY created_at DESC
        LIMIT :limit
    """)
    suspend fun getLatest(channelId: String, limit: Int = 50): List<MessageEntity>

    @Query("""
        SELECT * FROM messages
        WHERE channel_id = :channelId AND created_at < :before
        ORDER BY created_at DESC
        LIMIT :limit
    """)
    suspend fun getBefore(channelId: String, before: Long, limit: Int = 50): List<MessageEntity>

    @Query("""
        SELECT * FROM messages
        WHERE channel_id = :channelId
        ORDER BY created_at DESC
    """)
    fun pagingSource(channelId: String): PagingSource<Int, MessageEntity>

    @Upsert
    suspend fun upsert(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun delete(messageId: String)

    @Query("DELETE FROM messages WHERE channel_id = :channelId")
    suspend fun clearChannel(channelId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE channel_id = :channelId AND created_at > :since")
    suspend fun unreadCount(channelId: String, since: Long): Int
}
```

### 7.3 Database Configuration (WAL mode)

```kotlin
@Database(
    entities = [
        MessageEntity::class,
        ChannelEntity::class,
        ClanEntity::class,
        UserEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MezonDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun channelDao(): ChannelDao
    abstract fun clanDao(): ClanDao
    abstract fun userDao(): UserDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MezonDatabase {
        return Room.databaseBuilder(context, MezonDatabase::class.java, "mezon.db")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback({ sql, args -> Timber.d("Room: $sql | $args") }, Executors.newSingleThreadExecutor())
            .fallbackToDestructiveMigration()
            .build()
    }
}
```

---

## 8. Performance Optimizations

### 8.1 Startup: Baseline Profiles

Baseline Profiles pre-compile critical code paths via AOT. Reddit achieved 51% faster startup.

```kotlin
// benchmark/src/main/java/com/mezon/benchmark/BaselineProfileGenerator.kt
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generateBaselineProfile() {
        rule.collect(
            packageName = "com.mezon.android",
            maxIterations = 5,
            stableIterations = 3,
        ) {
            pressHome()
            startActivityAndWait()

            // Critical user journeys
            device.findObject(By.text("General")).click()
            device.waitForIdle()

            device.findObject(By.res("message_input")).click()
            device.findObject(By.res("message_input")).text = "Hello"
            device.findObject(By.res("send_button")).click()
            device.waitForIdle()
        }
    }
}
```

```kotlin
// app/build.gradle.kts
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    baselineProfile(project(":benchmark"))
}
```

### 8.2 App Startup: Lazy Initialization

```kotlin
class MezonInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        Timber.plant(Timber.DebugTree())
    }
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

class CoilInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        Coil.setImageLoader {
            ImageLoader.Builder(context)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(context, 0.25)
                        .build()
                }
                .diskCachePolicy(CachePolicy.ENABLED)
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.cacheDir.resolve("image_cache"))
                        .maxSizeBytes(100 * 1024 * 1024)
                        .build()
                }
                .crossfade(150)
                .build()
        }
    }
    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(MezonInitializer::class.java)
}
```

### 8.3 Memory: LRU Channel Cache

```kotlin
@Singleton
class ChannelMemoryManager @Inject constructor() {
    private val maxActiveChannels = 15
    private val accessOrder = LinkedHashMap<String, Long>(maxActiveChannels, 0.75f, true)

    fun onChannelAccessed(channelId: String) {
        accessOrder[channelId] = System.currentTimeMillis()
        if (accessOrder.size > maxActiveChannels) {
            val oldest = accessOrder.entries.first()
            accessOrder.remove(oldest.key)
            onChannelEvicted(oldest.key)
        }
    }

    private fun onChannelEvicted(channelId: String) {
        // Release message list from memory, keep in Room DB
    }

    fun getActiveChannels(): Set<String> = accessOrder.keys.toSet()
}
```

### 8.4 Image Performance: Coil 3 with ImgProxy

```kotlin
@Composable
fun ChatImage(
    url: String,
    width: Int,
    height: Int,
    modifier: Modifier = Modifier,
) {
    val resizedUrl = remember(url, width) {
        buildImgProxyUrl(url, targetWidth = width)
    }

    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(resizedUrl)
            .size(width, height)
            .crossfade(150)
            .memoryCacheKey(url)
            .diskCacheKey(url)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
    )
}

fun buildImgProxyUrl(originalUrl: String, targetWidth: Int): String {
    val encoded = Base64.encodeToString(originalUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
    return "${IMGPROXY_BASE}/rs:fit:${targetWidth}:0:0/plain/$encoded"
}
```

### 8.5 JankStats Monitoring

```kotlin
@Composable
fun rememberJankStats(activity: ComponentActivity): JankStats {
    return remember {
        JankStats.createAndTrack(activity.window) { frameData ->
            if (frameData.isJank) {
                Timber.w("Jank detected: ${frameData.frameDurationUiNanos / 1_000_000}ms | states: ${frameData.states}")
            }
        }
    }
}
```

---

## 9. Voice/Video: LiveKit Native SDK

```kotlin
@HiltViewModel
class VoiceCallViewModel @Inject constructor(
    private val mezonSocket: MezonSocket,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val channelId: String = savedStateHandle["channelId"]!!
    private val _room = MutableStateFlow<Room?>(null)
    val room: StateFlow<Room?> = _room

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState

    fun join(token: String, url: String) {
        viewModelScope.launch {
            _callState.value = CallState.Connecting
            val lkRoom = LiveKit.create(context)
            lkRoom.connect(url, token)
            _room.value = lkRoom
            _callState.value = CallState.Connected

            lkRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.ParticipantConnected -> {}
                    is RoomEvent.ParticipantDisconnected -> {}
                    is RoomEvent.Disconnected -> { _callState.value = CallState.Disconnected }
                    else -> {}
                }
            }
        }
    }

    fun toggleMic() {
        _room.value?.localParticipant?.let { p ->
            viewModelScope.launch { p.setMicrophoneEnabled(!p.isMicrophoneEnabled()) }
        }
    }

    fun toggleCamera() {
        _room.value?.localParticipant?.let { p ->
            viewModelScope.launch { p.setCameraEnabled(!p.isCameraEnabled()) }
        }
    }

    fun leave() {
        _room.value?.disconnect()
        _room.value = null
        _callState.value = CallState.Idle
    }
}

sealed interface CallState {
    data object Idle : CallState
    data object Connecting : CallState
    data object Connected : CallState
    data object Disconnected : CallState
}
```

---

## 10. Navigation

```kotlin
@Composable
fun MezonNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = "home") {
        composable("auth") {
            AuthScreen(onSuccess = {
                navController.navigate("home") { popUpTo("auth") { inclusive = true } }
            })
        }
        composable("home") {
            HomeScreen(
                onClan = { navController.navigate("clan/$it") },
                onDm = { navController.navigate("chat/$it") },
            )
        }
        composable("clan/{clanId}") {
            ClanScreen(
                onChannel = { navController.navigate("chat/$it") },
            )
        }
        composable("chat/{channelId}") {
            ChatScreen(onBack = { navController.popBackStack() })
        }
        composable("voice/{channelId}") {
            VoiceCallScreen(onBack = { navController.popBackStack() })
        }
        navigation(startDestination = "settings_main", route = "settings") {
            composable("settings_main") { SettingsScreen() }
            composable("settings_profile") { ProfileScreen() }
        }
    }
}
```

---

## 11. Dependencies (gradle/libs.versions.toml)

```toml
[versions]
kotlin = "2.1.0"
agp = "8.8.2"
compose-bom = "2025.02.00"
ktor = "3.1.0"
room = "2.7.1"
hilt = "2.54"
coroutines = "1.10.1"
paging = "3.3.6"
livekit = "2.12.0"
coil = "3.1.0"
protobuf = "4.29.3"
lifecycle = "2.9.0"
navigation = "2.8.9"
okhttp = "4.12.0"
media3 = "1.5.3"
firebase-bom = "33.9.0"
sentry = "7.22.0"
timber = "5.0.1"
cronet = "119.6045.31"
profileinstaller = "1.4.1"
jankstats = "1.0.0-beta01"
startup = "1.2.0"

[libraries]
# Compose
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-navigation = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }
compose-lifecycle = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }

# Network
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-client-auth = { module = "io.ktor:ktor-client-auth", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
protobuf-kotlin = { module = "com.google.protobuf:protobuf-kotlin", version.ref = "protobuf" }
cronet-api = { module = "org.chromium.net:cronet-api", version.ref = "cronet" }
cronet-okhttp = { module = "com.google.net.cronet:cronet-okhttp", version = "0.1.0" }

# Database
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
room-paging = { module = "androidx.room:room-paging", version.ref = "room" }
datastore-proto = { module = "androidx.datastore:datastore", version = "1.1.4" }

# DI
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-compiler", version.ref = "hilt" }
hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version = "1.2.0" }

# Async
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
lifecycle-viewmodel = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }

# Media
coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }
media3-exoplayer = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
media3-ui = { module = "androidx.media3:media3-ui", version.ref = "media3" }
livekit-android = { module = "io.livekit:livekit-android", version.ref = "livekit" }

# Paging
paging-runtime = { module = "androidx.paging:paging-runtime", version.ref = "paging" }
paging-compose = { module = "androidx.paging:paging-compose", version.ref = "paging" }

# Performance
profileinstaller = { module = "androidx.profileinstaller:profileinstaller", version.ref = "profileinstaller" }
jankstats = { module = "androidx.metrics:metrics-performance", version.ref = "jankstats" }
startup-runtime = { module = "androidx.startup:startup-runtime", version.ref = "startup" }

# Firebase
firebase-bom = { module = "com.google.firebase:firebase-bom", version.ref = "firebase-bom" }
firebase-messaging = { module = "com.google.firebase:firebase-messaging-ktx" }
firebase-analytics = { module = "com.google.firebase:firebase-analytics-ktx" }

# Utils
timber = { module = "com.jakewharton.timber:timber", version.ref = "timber" }
sentry-android = { module = "io.sentry:sentry-android", version.ref = "sentry" }
kotlinx-serialization = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version = "1.7.3" }

[bundles]
compose = ["compose-material3", "compose-ui-tooling", "compose-navigation", "compose-lifecycle"]
ktor = ["ktor-client-core", "ktor-client-okhttp", "ktor-client-auth", "ktor-client-content-negotiation", "ktor-serialization-json"]
room = ["room-runtime", "room-ktx", "room-paging"]
media = ["coil-compose", "media3-exoplayer", "media3-ui"]
performance = ["profileinstaller", "jankstats", "startup-runtime"]

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version = "2.1.0-1.0.29" }
protobuf = { id = "com.google.protobuf", version = "0.9.4" }
```

---

## 12. RN to Kotlin Mapping

| React Native | Kotlin Android | Performance Note |
|---|---|---|
| Redux (54 slices) | StateHolder + ViewModel | No serialization overhead, compile-time type safety |
| ChatContext (50+ handlers) | SocketEventDispatcher | Coroutine-based, non-blocking |
| FlatList (inverted) | RecyclerView (reversed) | ViewHolder pooling, DiffUtil = 2-3x faster |
| React Navigation | Compose Navigation | No JS bridge delay |
| AsyncStorage | Room (WAL) + DataStore | Indexed queries, 10x faster reads |
| mezon-js Client | Ktor + Cronet (HTTP/3) | QUIC + Brotli + multiplexing |
| mezon-js Socket | OkHttp WebSocket + Protobuf | 10x faster deserialization |
| FlashList | RecyclerView | Native ViewHolder recycling |
| Reanimated | Compose Animation | No bridge, 60fps |
| react-native-video | ExoPlayer (Media3) | Hardware decoding, less memory |
| FastImage | Coil 3 | 3-tier cache, coroutine-native |
| LiveKit RN | LiveKit Android SDK | No bridge overhead for WebRTC |
| Notifee | NotificationCompat | System-level integration |
| MMKV | DataStore Proto | Proto serialization, async writes |
| Sentry RN | Sentry Android | Native stack traces, ANR detection |
| Lottie RN | Lottie Compose | Hardware-accelerated |
| Bottom Sheet (Gorhom) | Material3 BottomSheet | System gesture integration |

---

## 13. Migration Roadmap

```
Phase 0 (2w)  Setup + Mezon Kotlin SDK (auto-gen from proto)
              - Project structure, CI/CD, Gradle config
              - Baseline Profile setup
              - Proto code generation

Phase 1 (3w)  Auth + Network Foundation
              - SessionManager + Cronet REST client
              - OkHttp WebSocket + Protobuf
              - SocketEventDispatcher + event batching

Phase 2 (4w)  Home + Navigation
              - Clan list, Channel list
              - Compose Navigation
              - Room DB setup + initial sync

Phase 3 (6w)  Chat (most critical)
              - RecyclerView message list
              - MessageStateHolder + TypingStateHolder
              - Send/receive/react/delete messages
              - Image/file attachments with Coil

Phase 4 (3w)  DM + Social
              - Direct messages (shared chat rendering)
              - Friends list, friend requests
              - Push notifications (FCM)

Phase 5 (4w)  Voice/Video
              - LiveKit Android SDK integration
              - Call UI + foreground service
              - WebRTC signaling via socket

Phase 6 (3w)  Media + Search
              - Gallery viewer (ExoPlayer + Coil)
              - File upload to MinIO
              - Message search

Phase 7 (2w)  Settings + Polish
              - Settings, Profile, Roles
              - JankStats monitoring
              - Memory profiling + optimization

Phase 8 (2w)  Release Prep
              - Baseline Profile generation
              - R8 optimization
              - Sentry integration
              - Beta testing
```

Total: ~29 weeks (7 months) for 2-3 Android devs.

Critical first step: locate .proto files on server to auto-gen Kotlin API client.
