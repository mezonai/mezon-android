# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Mezon is an Android chat/communication app (application ID: `com.mezon.mobile`) built with Kotlin, Jetpack Compose, and a high-performance architecture: **MVI + Per-Channel Store + Room SQLite + Compose**. The project is porting the React Native app to native Android.

## Build Commands

All commands run from the `mezon/` subdirectory:

```bash
cd mezon
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK
./gradlew test                   # Run all unit tests
./gradlew app:testDebugUnitTest  # Run unit tests for app module only
./gradlew connectedAndroidTest   # Run instrumented tests (requires device/emulator)
```

## Build Configuration

- **Gradle:** 8.7 with Kotlin DSL (`.kts` files)
- **AGP:** 8.5.2, **Kotlin:** 1.9.0, **Compose Compiler:** 1.5.1
- **Compile/Target SDK:** 34, **Min SDK:** 24, **Java/JVM target:** 1.8
- **Annotation processing:** KSP (not kapt) for Room, Hilt
- **Version catalog:** `gradle/libs.versions.toml` — all dependency versions managed here
- **Modules:** `:app` (main application) and `:core-proto` (auto-generated protobuf classes)
- **Proto path:** `mezonProtocolPath` in `gradle.properties` points to the proto source directory

## Architecture

**Pattern:** MVI (Intent → ViewModel → UiState → UI) with Per-Channel Store layer and Room persistence.

### Data Flow

```
WebSocket → SocketEventDispatcher → Store (per-channel StateFlow + async DAO write)
REST      → Repository            → Store (same dual-write)
Store     → ViewModel (flatMapLatest → channel-scoped flow → UiState)
Room DB   → Store.init()          → cold-start cache (instant UI on relaunch)
```

### Layer Responsibilities

| Layer | Classes | Responsibility |
|-------|---------|---------------|
| **Room DB** | `MezonDatabase`, `*Dao` | Persistent SQLite with WAL mode. Composite PK for messages `[channelId, id]`. `@Upsert`, `@Index`, bounded queries (LIMIT 200) |
| **Store** | `MessageStore`, `DirectStore` | `@Singleton` in-memory cache. MessageStore: `ConcurrentHashMap` + LRU eviction (30 channels). DirectStore: flat `MutableStateFlow<List>` + `CompletableDeferred` load ordering. Dual-write: StateFlow update (instant UI) then async DAO write |
| **Repository** | `ChatRepository`, `DirectRepository` | REST fetch via `MezonApi` → push to Store. `ApiCacheTracker` prevents redundant fetches. `NetworkMonitor` for offline handling |
| **ViewModel** | `ChatViewModel`, `MessagesViewModel` | `flatMapLatest` on channel ID → `combine` channel-scoped Store flows → `stateIn`. Single `onIntent()` entry point. `_hasLoadedOnce` + `_error` for proper Loading/Empty/Error/Success |
| **Screen** | `ChatScreen`, `MessagesScreen` | Compose UI. `collectAsStateWithLifecycle()`. `LazyColumn` with `key`/`contentType`. Index-based items (zero allocation) |
| **Notification** | `NotificationHelper`, `NotificationObserver`, `MezonFirebaseService` | FCM push notifications + local notifications from WebSocket events. `ActiveChannelTracker` suppresses for current channel |
| **Util** | `ContentParser` | Shared cached Regex for content parsing and time formatting |

### No UseCase layer by default

ViewModels call Repositories directly. Only add UseCases for genuine multi-step business logic.

### Single Activity

`MainActivity` with Compose Navigation. `AppNavGraph` defines all routes. Deep-linking from notifications via `PendingIntent`.

### Planned Module Structure

```
app          → all feature modules (single Activity, nav, Hilt entry)
feature-*    → core-state, core-ui, core-common
core-state   → core-data, core-network
core-data    → core-network, core-common
core-network → core-common
```

## Package Structure

```
app/src/main/java/com/mezon/mobile/
├── auth/             # AuthRepository, AuthViewModel, LoginScreen
├── data/db/          # MezonDatabase, MessageDao, DirectMessageDao
├── di/               # AppModule, DatabaseModule, CoroutineDispatchers
├── home/
│   ├── chat/         # MessageEntity, ChannelState, MessageStore, ChatRepository,
│   │                 # ChatViewModel, ChatScreen, MessageBubble
│   └── messages/     # DirectMessage, DirectStore, DirectRepository,
│                     # MessagesViewModel, MessagesScreen
├── navigation/       # NavRoutes, AppNavGraph
├── network/          # MezonApi (REST), MezonSocket (WebSocket), SocketEventDispatcher,
│                     # NetworkMonitor, ApiCacheTracker
├── notification/     # NotificationHelper, NotificationObserver, MezonFirebaseService,
│                     # FcmRepository, ActiveChannelTracker
├── session/          # SessionManager (DataStore)
├── ui/
│   ├── components/   # MezonAvatar, MezonBadge, MezonScreenStates
│   └── theme/        # Color, Theme (4 modes: Light/Dark/Abyss/System),
│                     # ThemeManager, Dimens, Type
└── util/             # ContentParser (shared Regex, parseContentText, formatRelativeTime)
```

## Protocol & Code Generation

**Do NOT convert mezon-js to Kotlin.** Use the `.proto` files from mezon-protocol to auto-generate all models via the `:core-proto` module, then write a pure Kotlin transport layer.

### Source Locations

| Resource | Path |
|----------|------|
| Proto definitions | `/Users/huy/dev/company/mezon-protocol/` |
| React Native reference app | `/Users/huy/AndroidStudioProjects/mezon-reactnative/mezon/apps/mobile/` |
| mezon-js SDK (reference only) | [github.com/mezonai/mezon-js](https://github.com/mezonai/mezon-js) |

### Proto Files

Two proto files, auto-generated into `:core-proto` as Protobuf Lite:
- **`api/api.proto`** (~3700 lines) — REST request/response messages. Java package: `com.mezon.mezon.api`
- **`rtapi/realtime.proto`** (~1430 lines) — WebSocket realtime envelope. Java package: `com.mezon.mezon.rtapi`

No gRPC service definitions — only message types. REST endpoints are defined server-side.

### WebSocket Protocol

Single **`Envelope`** protobuf message as the WebSocket frame:
- `cid` — correlation ID for request-response pairing
- `oneof message` — 94 distinct event types

Key event categories:
- **Messages:** `ChannelMessageSend` (C→S), `ChannelMessage` (S→C), `ChannelMessageUpdate`, `ChannelMessageRemove`
- **Presence:** `ChannelPresenceEvent`, `StatusPresenceEvent`, `MessageTypingEvent`
- **Channels:** `ChannelJoin`/`ChannelLeave` (C→S), Created/Updated/Deleted (S→C)
- **Clans:** `ClanJoin` (C→S), Updated/Deleted (S→C)
- **Voice/Video:** `VoiceJoinedEvent`, `VoiceLeavedEvent`, `WebrtcSignalingFwd`
- **Control:** `Ping`/`Pong`, `Error`, `MarkAsRead`, `LastSeenMessageEvent`

WebSocket URL: `wss://<ws_url>/ws?token=<token>&status=true&platform=1&lang=en&format=protobuf`

### REST API Patterns

Bearer token auth. Request/response bodies are protobuf-encoded (`Content-Type: application/proto`). Exception: auth endpoint uses JSON with Basic auth (API key).

Key endpoints:
- `POST /v2/account/authenticate/email` — email+password auth (JSON, Basic auth)
- `POST $apiUrl/mezon.api.Mezon/<MethodName>` — all other RPCs (proto, Bearer token)
- Message history: `listChannelMessages(channelId, clanId, messageId, direction, limit)` — direction 1=before, 2=after
- DM/group list: `listChannelDescs(channelType, page, limit)` — type=3 DM, type=2 group

### Authentication Flow

1. Authenticate via REST → receive `Session` (token, refresh_token, api_url, ws_url)
2. `api_url` and `ws_url` from the session response configure all subsequent base URLs
3. Connect WebSocket with session token
4. Join clan chats (`ClanJoin`) and channels (`ChannelJoin`)
5. Token refresh uses `SessionRefreshRequest` with the refresh_token

### Key Domain Models (from api.proto)

| Model | Description |
|-------|-------------|
| `Session` | Auth result: token, refresh_token, user_id, api_url, ws_url |
| `Account` / `User` | User profile (id, username, display_name, avatar, mezon_id) |
| `ClanDesc` | Clan/server (name, logo, banner, community settings) |
| `ChannelDescription` | Channel (type, label, private, topic, last_msg) |
| `ChannelMessage` | Message (content JSON, reactions, mentions, attachments, references) |

Channel types: CHANNEL(1), GROUP(2), DM(3), FORUM(5), STREAMING(6), THREAD(7), APP(8), ANNOUNCEMENT(9), MEZON_VOICE(10).

## Key Technology Choices

| Layer | Technology | Notes |
|-------|-----------|-------|
| REST | Ktor client 2.3.12 + OkHttp engine | Bearer token, protobuf content-type |
| WebSocket | OkHttp 4.12 WebSocket + Protobuf binary | `Envelope` oneof, CID correlation, 15s heartbeat, exponential backoff reconnect (1s→30s) |
| Database | Room 2.6.1 + KSP | WAL mode, `@Upsert`, composite PK, `@Index`, LIMIT 200 |
| DI | Hilt 2.51.1 | `@HiltViewModel`, `@InstallIn(SingletonComponent)` |
| UI | Jetpack Compose (Material3) | LazyColumn with `key`/`contentType`, index-based items |
| Theme | Material3 + ThemeManager | 4 modes: Light, Dark, Abyss, System. DataStore persistence |
| i18n | `strings.xml` + locale qualifiers | `stringResource()`, `AppCompatDelegate.setApplicationLocales()` |
| Images | `MezonImageLoader` (custom) | OkHttp + split LruCache + 50MB disk cache, explicit `reqWidth`/`reqHeight` for avatars, `AvatarDrawable` fallback |
| Session | DataStore Preferences | `SessionManager` with `sessionFlow`, Mutex-based refresh |
| Push | Firebase Cloud Messaging | `MezonFirebaseService` + local `NotificationObserver` for WebSocket events |
| Proto | Protobuf Lite 4.28.2 (auto-generated) | `:core-proto` module |
| Responsive | `WindowSize` enum + `LocalDimens` | Compact/Medium/Expanded breakpoints, `scaledTypography()` |

## Code Conventions

### Critical Rules

- **Never add comments** to Kotlin source — no KDoc, block comments, or inline comments. Write self-documenting code with clear names.
- **Sealed interfaces** (not sealed classes) for `UiState`, `Intent`, and `Event` types
- **Single `onIntent(intent)` entry point** per ViewModel — no direct public methods for actions
- **Constructor params always `private val`**

### State Management

- **`StateFlow`** for UI state, derived from Store via `combine`/`map` + `stateIn`
- **`Channel<T>(Channel.BUFFERED)` + `receiveAsFlow()`** for one-shot events (not SharedFlow)
- **`collectAsStateWithLifecycle()`** in Compose (not `collectAsState()`)
- **`flatMapLatest`** on channel ID in ChatViewModel — not global map combine
- **Dual-write**: `_state.update { }` first (synchronous UI), then `appScope.launch { dao.upsert() }` (async persistence)
- **Cap at 200 messages** per channel in-memory — trim oldest on append
- **LRU eviction at 30 channels** in MessageStore — prevents unbounded memory growth
- **`evictChannel()`** removes from memory only; data persists in Room
- **`_hasLoadedOnce` + `_error`** pattern in list ViewModels for proper Loading/Empty/Error/Success
- **`loadMoreError`** separate from main error — exposed in `Success` state for pagination failures

### Threading & DI

- **`@IoDispatcher`** for network/disk I/O, **`@MainDispatcher`** for main thread
- **`@ApplicationScope`** for process-lifetime coroutine scope (Store observers, async DAO writes)
- **`ConcurrentHashMap`** for thread-safe per-channel state storage
- **`CompletableDeferred`** pattern for WebSocket request-response (CID correlation, 10s timeout)
- **`CompletableDeferred`** in DirectStore to ensure DB loads before socket observers start

### Compose & UI

- **`remember(key) { compute() }`** for parsed content and formatted time in composables
- **Top-level `private val`** for shapes/constants — never create inside composition
- **Index-based `items(count)`** in LazyColumn — zero allocation (no `asReversed()`)
- **Screens receive navigation via lambdas** — never access NavController directly

### Proto & Network

- **Proto DSL builders** (`envelope { ... }`) for outgoing WebSocket messages
- **`Long` keys** everywhere for IDs — no `.toString()` conversions
- **Batch API calls** with `async`/`awaitAll` where possible
- **`ApiCacheTracker`** prevents redundant REST calls (20-min TTL)
- **`NetworkMonitor`** for offline-aware data loading

## RN → Kotlin Migration Guide

When porting a feature from the React Native app:

| RN Source | Kotlin Target | Notes |
|-----------|--------------|-------|
| Redux slice (state + reducers) | `*Store.kt` | Per-channel `ConcurrentHashMap` + Room dual-write + LRU eviction |
| Redux thunk (async fetch) | `*Repository.kt` | REST fetch → push to Store |
| ChatContext socket callbacks | `SocketEventDispatcher` + `Store.init` observers | Wire in `init` block, await DB load first |
| Screen component | `*Screen.kt` | Compose + LazyColumn |
| Hook / screen logic | `*ViewModel.kt` | MVI: `flatMapLatest` → `stateIn` |
| `useSelector` hook | `collectAsStateWithLifecycle()` | |
| Redux Persist | Room DB | Automatic via dual-write |
| Notifee push | FCM + NotificationHelper + NotificationObserver | |
| i18n / react-intl | `strings.xml` + locale qualifiers | |
| ThemeContext | ThemeManager (DataStore) + Material3 ColorScheme | 4 themes |

Steps: (1) Identify RN sources in `apps/mobile/src/`, (2) Create Kotlin files grouped by feature folder, (3) Add Room entity + DAO, (4) Wire socket events in Store.init, (5) Add route to `NavRoutes` + `AppNavGraph`, (6) Inject Store into `HomeViewModel` for eager initialization.
