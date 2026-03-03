# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Mezon is an Android chat/communication app (application ID: `ai.mezon.app`) built with Kotlin, Jetpack Compose, and a pragmatic Clean Architecture + MVI pattern. The project is currently bootstrapped as a single `:app` module but is planned to expand into a 12-module architecture (see README.MD for full migration roadmap).

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests
./gradlew app:testDebugUnitTest  # Run unit tests for app module only
```

## Build Configuration

- **Gradle:** 8.7 with Kotlin DSL (`.kts` files)
- **AGP:** 8.5.2, **Kotlin:** 1.9.0, **Compose Compiler:** 1.5.1
- **Compile/Target SDK:** 34, **Min SDK:** 24, **Java/JVM target:** 1.8
- **Annotation processing:** KSP (not kapt) for Room, Hilt
- **Version catalog:** `gradle/libs.versions.toml` — all dependency versions managed here

## Architecture

**Pattern:** MVI (Intent → ViewModel → UiState → UI) with Shared State layer

- **No UseCase layer by default** — ViewModels call Repositories directly. Only add UseCases when there is real business logic to encapsulate.
- **Shared State:** `@Singleton` `StateHolder` classes holding `StateFlow`/`SharedFlow` for cross-feature real-time state (messages, presence, typing, channels). These replace a Redux-style global store.
- **Single Activity** (`MainActivity`) with Compose Navigation.
- **`SocketEventDispatcher`** fans out WebSocket events to all StateHolders.

### Planned Module Structure

```
app          → all feature modules (single Activity, nav, Hilt entry)
feature-*    → core-state, core-ui, core-common
core-state   → core-data, core-network
core-data    → core-network, core-common
core-network → core-common
```

## Protocol & Code Generation

**Do NOT convert mezon-js to Kotlin.** Use the `.proto` files from mezon-protocol to auto-generate all models, then write a pure Kotlin transport layer (Ktor/OkHttp) using `protoc-gen-grpc-kotlin`.

### Source Locations

| Resource | Path |
|----------|------|
| Proto definitions | `/Users/huy/dev/company/mezon-protocol/` |
| React Native reference app | `/Users/huy/dev/company/mezon/apps/mobile/` |
| mezon-js SDK (reference only) | [github.com/mezonai/mezon-js](https://github.com/mezonai/mezon-js) |
| mezon-protocol repo | [github.com/mezonai/mezon-protocol](https://github.com/mezonai/mezon-protocol) |

### Proto Files

There are exactly 2 proto files:
- **`api/api.proto`** (~3700 lines) — REST/gRPC request/response message definitions. Package: `mezon.api`. Java package: `com.mezon.mezon.api`.
- **`rtapi/realtime.proto`** (~1430 lines) — WebSocket realtime envelope. Package: `mezon.realtime`. Java package: `com.mezon.mezon.rtapi`.

Both protos already have Java/Kotlin options set (`java_multiple_files = true`, `java_package`, `java_outer_classname`). There are **no gRPC service definitions** in these protos — only message types. The REST endpoints are defined server-side.

### WebSocket Protocol

The realtime layer uses a single **`Envelope`** protobuf message as the WebSocket frame. It contains:
- `cid` — correlation ID for request-response pairing
- `oneof message` — 94 distinct event types (fields 2–94)

Key event categories in the Envelope:
- **Messages:** `ChannelMessageSend` (C→S), `ChannelMessage` (S→C), `ChannelMessageUpdate`, `ChannelMessageRemove`, `ChannelMessageAck`
- **Presence:** `ChannelPresenceEvent`, `StatusPresenceEvent`, `MessageTypingEvent`
- **Channels:** `ChannelJoin`/`ChannelLeave` (C→S), `ChannelCreatedEvent`/`ChannelUpdatedEvent`/`ChannelDeletedEvent` (S→C)
- **Clans:** `ClanJoin` (C→S), `ClanUpdatedEvent`/`ClanDeletedEvent` (S→C)
- **Voice/Video:** `VoiceJoinedEvent`, `VoiceLeavedEvent`, `VoiceStartedEvent`, `VoiceEndedEvent`, `WebrtcSignalingFwd`
- **Friends:** `AddFriend`, `RemoveFriend`, `BlockFriend`, `UnblockFriend`
- **Reactions:** `MessageReaction` (C↔S)
- **Control:** `Ping`/`Pong`, `Error`, `LastSeenMessageEvent`, `MarkAsRead`

WebSocket connect URL: `wss://<ws_url>/ws?token=<token>&status=true&platform=1&lang=<lang>` (platform=1 for mobile).

### REST API Patterns (from React Native reference)

All REST calls use `Authorization: Bearer <session.token>`. Request/response bodies are protobuf-encoded (`Content-Type: application/proto`, `Accept: application/proto`). Key endpoints:
- `POST /v2/account/authenticate/email` — email+password auth
- `POST /v2/account/authenticate/mezon` — OAuth/phone auth
- `POST /v2/session/refresh` — token refresh
- `POST /v2/session/logout` — logout + FCM cleanup
- `GET /v2/account` — current user account
- `GET /v2/clans`, `GET /v2/channels` — list clans/channels
- `GET /v2/channels/{id}/messages` — message history (paginated)

### Authentication Flow

1. Authenticate via REST → receive `Session` (token, refresh_token, api_url, ws_url)
2. `api_url` and `ws_url` come from the server in the session response — they configure the client's base URLs
3. Connect WebSocket with session token
4. Join clan chats (`ClanJoin`) and channels (`ChannelJoin`)
5. Token refresh uses `SessionRefreshRequest` with the refresh_token

### Key Domain Models (from api.proto)

| Model | Description |
|-------|-------------|
| `Session` | Auth result: token, refresh_token, user_id, api_url, ws_url |
| `Account` / `User` | User profile (id, username, display_name, avatar, mezon_id) |
| `ClanDesc` | Clan/server (name, logo, banner, community settings) |
| `CategoryDesc` | Channel category within a clan |
| `ChannelDescription` | Channel (type, label, private, E2EE, topic, last_msg) |
| `ChannelMessage` | Message (content, reactions, mentions, attachments, references) |
| `Friend` | Friend with state (FRIEND, INVITE_SENT, INVITE_RECEIVED, BLOCKED) |
| `Role` / `Permission` | RBAC roles and permissions |
| `Notification` | Push notification model |

Channel types: CHANNEL(1), GROUP(2), DM(3), FORUM(5), STREAMING(6), THREAD(7), APP(8), ANNOUNCEMENT(9), MEZON_VOICE(10).

## Key Technology Choices

| Layer | Technology | Notes |
|-------|-----------|-------|
| REST | Ktor client + OkHttp engine + Cronet (HTTP/3) | Bearer token plugin with auto-refresh |
| WebSocket | OkHttp WebSocket + Protobuf binary | CID correlation for request-response |
| Database | Room (WAL mode) + Paging 3 | Upsert support, compound indices |
| DI | Hilt | `@HiltViewModel`, `@InstallIn(SingletonComponent)` |
| UI | Jetpack Compose (Material3) | RecyclerView for message list (not LazyColumn — performance) |
| Images | Coil 3 + ImgProxy | Server-side resize, 25% memory / 100MB disk cache |
| Voice/Video | LiveKit Android SDK | WebRTC |
| Crash reporting | Sentry | |
| Logging | Timber | |

## Code Conventions

- **Sealed interfaces** (not sealed classes) for `UiState` and `Intent` types
- **`StateFlow`** for UI state, **`SharedFlow`** for one-shot events
- **Coroutine dispatchers** injected via qualifier annotations (`@IoDispatcher`)
- **`@ApplicationScope`** qualifier for application-scoped coroutine scope
- **`ConcurrentHashMap`** for thread-safe per-channel state storage
- **`Mutex`** for token refresh coordination in `SessionManager`
- **`CompletableDeferred`** pattern for WebSocket request-response (CID correlation)
- **Proto DSL builders** (`envelope { ... }`) for Protobuf message construction
- **`ListAdapter` + `DiffUtil.ItemCallback`** with change payloads for RecyclerView
- **Kotlin code style:** `official` (set in `gradle.properties`)
- **Non-transitive R classes** enabled

