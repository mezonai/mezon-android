# Mezon Android

Kotlin chat app porting from React Native. Targets scroll performance using custom Canvas-drawn Views, a controller-based state layer, and `NotificationCenter` for decoupled UI updates.

---

## Setup

### Prerequisites

| Tool | Version |
|------|---------|
| Android Studio | Hedgehog (2023.1.1) or newer |
| JDK | 17+ (bundled with Android Studio) |
| Android SDK | compileSdk 35, minSdk 24 |
| Kotlin | 1.9.0 |

### 1. Clone repositories

```bash
# App chính
git clone <mezon-android-repo> ~/AndroidStudioProjects/mezon

# Proto definitions — clone ở bất kỳ đâu, ghi nhớ đường dẫn tuyệt đối
git clone https://github.com/mezonai/mezon-protocol.git ~/dev/company/mezon-protocol

# (Tùy chọn) React Native reference — dùng để hiểu UI/UX khi porting feature
git clone <mezon-reactnative-repo> ~/AndroidStudioProjects/mezon-reactnative
```

### 2. Tạo symlink proto

Module `:core-proto` dùng symlink **absolute path** trỏ vào `mezon-protocol/` để Gradle protobuf plugin tự sinh Java + Kotlin Lite classes khi build.

```bash
cd ~/AndroidStudioProjects/mezon

mkdir -p core-proto/src/main/proto

# Thay /absolute/path/to/mezon-protocol bằng đường dẫn thực tế trên máy bạn
PROTO_DIR="/absolute/path/to/mezon-protocol"

ln -sf "$PROTO_DIR/api"   core-proto/src/main/proto/api
ln -sf "$PROTO_DIR/rtapi" core-proto/src/main/proto/rtapi
```

Ví dụ với đường dẫn thực tế hiện tại:

```bash
ln -sf /Users/huy/dev/company/mezon-protocol/api   core-proto/src/main/proto/api
ln -sf /Users/huy/dev/company/mezon-protocol/rtapi core-proto/src/main/proto/rtapi
```

Kiểm tra:

```bash
ls -la core-proto/src/main/proto/
# api   -> /absolute/path/to/mezon-protocol/api
# rtapi -> /absolute/path/to/mezon-protocol/rtapi
```

> Symlink phải dùng **absolute path** (không dùng relative `../../../`). Gradle protobuf plugin follow symlink theo absolute path khi resolve import trong `.proto` files.

### 3. Thêm google-services.json

File `app/google-services.json` không được commit vào git. Lấy file từ Firebase Console (project `mezon-772fa`) hoặc từ teammate, đặt vào:

```
mezon/app/google-services.json
```

### 4. Sync và build

Mở project `mezon/` bằng Android Studio, chờ Gradle sync, sau đó:

```bash
cd mezon

# Sinh proto classes + build debug APK
./gradlew assembleDebug

# Cài thẳng lên device/emulator
./gradlew installDebug
```

### 5. Cập nhật proto (khi mezon-protocol có bản mới)

```bash
cd mezon-protocol
git pull origin main

cd ../mezon
./gradlew :core-proto:generateDebugProto

# Kiểm tra compile không lỗi
./gradlew app:compileDebugKotlin 2>&1 | grep "^e:"
```

---

## Build

```bash
cd mezon
./gradlew assembleDebug    # debug APK
./gradlew assembleRelease  # release APK
./gradlew test             # unit tests
```

## Architecture

```
WebSocket ──► SocketEventDispatcher ──► Controller (in-memory cache + async Room write)
REST API  ──► Controller                ──► NotificationCenter.post(event)
Room DB   ──► Controller.init()         ──► cold-start cache (instant UI on relaunch)
NotificationCenter ──► Fragment.observe() ──► RecyclerView / custom View invalidate()
```

### Layers

| Layer | Role |
|-------|------|
| **Controller** (`@Singleton`) | Owns in-memory state (`StateFlow`, `LongSparseArray`, `ConcurrentHashMap`). Dual-write: update cache first, then `appScope.launch { dao.upsert() }` async. Observes socket events in `init {}`. |
| **NotificationCenter** | Custom event bus (integer IDs, `SparseArray<List<Observer>>`). Always dispatched on main thread via `postNotificationOnMainThread`. |
| **BaseFragment** | Registers `NotificationCenter` observers on `onViewCreated`, auto-removes on `onDestroyView`. Provides `wrapWithActionBar()` helper. |
| **Custom Views** | `onDraw(Canvas)` cells for high-performance lists (`ChatMessageCell`, `DialogCell`, `NotificationCell`, `ChannelItemCell`, `ClanCell`). Local `Paint` objects per instance — no shared mutable paint. |
| **Room DB** | WAL mode. `@Upsert`, bounded queries (`LIMIT 200`). Accessed only from `@IoDispatcher`. |
| **SocketEventDispatcher** | Fans out `Envelope` proto frames to 40+ typed `SharedFlow`s. |

### Navigation

Single `Activity` (`MainActivity`) with `FragmentManager` back-stack. Each screen is a `BaseFragment` subclass. No Jetpack Navigation — fragment transactions use `replace()` + `addToBackStack()` directly.

### NotificationCenter Events

| Event | Posted by | Consumed by |
|-------|-----------|-------------|
| `clansDidLoad` | `ClansController` | `ClansFragment` |
| `channelsDidLoad` | `ChannelController` | `ClansFragment` |
| `clanInfoUpdated` | `ClansController` | `ClansFragment` |
| `messagesDidLoad` | `ChatController` | `ChatFragment` |
| `didReceiveNewMessages` | `ChatController` | `ChatFragment` |
| `messageDidUpdate` | `ChatController` | `ChatFragment` |
| `messageDidDelete` | `ChatController` | `ChatFragment` |
| `dialogsNeedReload` | `MessagesController` / `DialogsController` | `MessagesFragment` |
| `notificationsDidLoad` | `NotificationStore` | `NotificationsFragment` |
| `accountInfoLoaded` | `AccountController` | `ProfileFragment`, `EditProfileFragment` |
| `blockedUsersLoaded` | `AccountController` | `BlockedUsersFragment` |
| `userDataLoaded` | `UserController` | `ProfileFragment` |
| `connectionStateChanged` | `ConnectionController` | `HomeFragment` |
| `sessionExpired` | `AuthRepository` | `MainActivity` |
| `themeChanged` | `ThemeManager` | all fragments via `BaseFragment` |

## Package Structure

```
app/src/main/java/com/mezon/mobile/
├── MainActivity.kt           # single Activity, fragment host
├── MezonApplication.kt       # Hilt entry point
├── auth/
│   ├── AuthRepository.kt     # login, session save/refresh
│   └── LoginFragment.kt
├── core/
│   ├── BaseFragment.kt       # NotificationCenter auto-wiring, ActionBar helper
│   ├── NotificationCenter.kt # integer-keyed event bus
│   ├── ThemeColors.kt        # singleton Paint/color tokens (4 themes)
│   ├── LayoutHelper.kt       # dp/sp helpers, MeasureSpec utils
│   └── AvatarDrawable.kt     # fallback initials avatar
├── data/db/
│   ├── MezonDatabase.kt      # Room DB, WAL mode
│   ├── MessageDao.kt
│   ├── DirectMessageDao.kt
│   ├── ClanDao.kt
│   └── ClanChannelDao.kt
├── di/
│   ├── AppModule.kt          # Hilt bindings
│   ├── DatabaseModule.kt
│   └── CoroutineDispatchers.kt  # @IoDispatcher, @ApplicationScope
├── home/
│   ├── ChatController.kt     # message cache (LongSparseArray), send, history, socket
│   ├── MessagesController.kt # DM/group conversation list cache
│   ├── DialogsController.kt  # dialog metadata cache
│   ├── ConnectionController.kt  # WebSocket lifecycle
│   ├── CachedChannel.kt
│   ├── CachedUser.kt
│   ├── HomeFragment.kt       # bottom nav tabs host
│   ├── chat/
│   │   ├── ChatFragment.kt
│   │   ├── ChatAdapter.kt    # RecyclerView adapter, VIEW_TYPE_MESSAGE / LOADING
│   │   ├── ChatMessageCell.kt  # Canvas-drawn bubble (local Paint per instance)
│   │   ├── MessageEntity.kt
│   │   └── ImageReceiver.kt  # MezonImageLoader async image into custom View
│   ├── clans/
│   │   ├── ClansController.kt  # clan list StateFlow + Room dual-write
│   │   ├── ChannelController.kt  # per-clan channel cache, findChannelById
│   │   ├── ClansFragment.kt
│   │   ├── ClanCell.kt       # Canvas-drawn clan icon cell
│   │   ├── ChannelItemCell.kt
│   │   ├── ChannelListView.kt
│   │   ├── ChannelSectionCell.kt
│   │   ├── ChannelThreadCell.kt
│   │   ├── ClanEntity.kt
│   │   └── ClanChannelEntity.kt
│   ├── messages/
│   │   ├── MessagesFragment.kt
│   │   ├── DmListAdapter.kt
│   │   ├── DialogCell.kt     # Canvas-drawn DM row
│   │   └── DirectMessage.kt
│   ├── notifications/
│   │   ├── NotificationStore.kt  # load/clear notifications, proto + JSON parsing
│   │   ├── NotificationsFragment.kt  # tab chips (All / Mentions)
│   │   ├── NotificationAdapter.kt
│   │   ├── NotificationCell.kt  # Canvas-drawn notification row
│   │   └── NotificationEntity.kt  # DirectFcmProto + JSON content parser
│   └── profile/
│       ├── AccountController.kt  # account info, blocked users, password update
│       ├── UserController.kt     # user profile cache
│       ├── ProfileFragment.kt
│       ├── AccountSettingFragment.kt
│       ├── EditProfileFragment.kt
│       ├── UpdateEmailFragment.kt
│       ├── UpdatePhoneFragment.kt
│       ├── VerifyOtpFragment.kt
│       ├── SetPasswordFragment.kt
│       └── BlockedUsersFragment.kt
├── navigation/
│   └── NavRoutes.kt          # fragment factory helpers
├── network/
│   ├── MezonApi.kt           # Ktor REST (proto content-type)
│   ├── MezonSocket.kt        # OkHttp WebSocket, Envelope proto, heartbeat, reconnect
│   ├── SocketEventDispatcher.kt  # 40+ typed SharedFlow<T> from Envelope oneof
│   ├── NetworkMonitor.kt
│   ├── ApiCacheTracker.kt    # 20-min TTL dedup for REST calls
│   └── ConnectionState.kt
├── notification/
│   ├── MezonFirebaseService.kt  # FCM token + push dispatch
│   ├── NotificationHelper.kt    # system notification builder
│   ├── NotificationObserver.kt  # WebSocket new-message → local notification
│   ├── FcmRepository.kt
│   └── ActiveChannelTracker.kt  # suppress notifications for open channel
├── session/
│   ├── SessionManager.kt     # DataStore: token, apiUrl, wsUrl
│   ├── ThemeManager.kt       # DataStore: theme mode
│   ├── LocaleManager.kt
│   └── SessionKeys.kt
├── ui/
│   ├── cells/                # reusable custom Views
│   │   ├── ActionBarView.kt  # top action bar (title + back button)
│   │   ├── ActionButton.kt
│   │   ├── AvatarView.kt
│   │   ├── HeaderCell.kt
│   │   ├── InputCell.kt
│   │   ├── TextSettingsCell.kt
│   │   ├── TextDetailCell.kt
│   │   ├── TextCheckCell.kt
│   │   ├── SwitchView.kt
│   │   ├── RadioCell.kt
│   │   ├── TabPagerView.kt
│   │   ├── SearchCell.kt
│   │   ├── ProfileHeaderCell.kt
│   │   ├── ScreenStateView.kt
│   │   ├── ToastOverlay.kt
│   │   ├── PopupMenu.kt
│   │   ├── SelectPopup.kt
│   │   ├── SlideOptionView.kt
│   │   ├── MezonBottomSheetDialog.kt
│   │   ├── MezonIcon.kt
│   │   ├── CdnIconView.kt
│   │   ├── BadgeDrawable.kt
│   │   ├── ColoredImageSpan.kt
│   │   ├── IconButton.kt
│   │   ├── ImagePickerHelper.kt
│   │   └── ShadowSectionCell.kt
│   └── theme/
│       ├── Color.kt
│       ├── Theme.kt          # 4 modes: Light / Dark / Abyss / System
│       ├── Type.kt
│       └── Dimens.kt
└── util/
    ├── ContentParser.kt      # cached Regex, parseContentText(), parseContentPreview(), formatRelativeTime()
    └── ImageProxy.kt         # imgproxy URL builder
```

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 1.9.0 |
| Build | AGP 8.5.2, KSP 1.9.0-1.0.13 |
| DI | Hilt 2.51.1 |
| REST | Ktor 2.3.12 + OkHttp 4.12.0 engine |
| WebSocket | OkHttp 4.12.0 + Protobuf binary (`Envelope` oneof) |
| Database | Room 2.6.1, WAL mode, `@Upsert` |
| Images | `MezonImageLoader` (custom OkHttp + LruCache + disk cache singleton, loaded into custom Views via `ImageReceiver`) |
| Session | DataStore Preferences 1.1.1 |
| Push | Firebase Cloud Messaging (BOM 33.1.0) |
| Proto | Protobuf Lite 4.28.2 (`:core-proto` module, auto-generated) |
| Coroutines | kotlinx-coroutines 1.7.3 |

## Key Performance Notes

- **Local Paint per cell**: `ChatMessageCell`, `DialogCell`, `NotificationCell` each own their own `Paint` objects — no shared mutable state between recycled views.
- **Dual-write**: Controllers update in-memory state first (instant UI), then `appScope.launch { dao.upsert() }` async.
- **Cold-start**: Room cache loads in `Controller.init {}` → `NotificationCenter` event → Fragment renders before REST response arrives.
- **ApiCacheTracker** (20-min TTL): prevents redundant REST fetches on repeated navigation.
- **ChannelController pre-warm**: `ClansController.init` calls `channelController.loadChannelsForClan(firstClanId)` so channel list is ready immediately on relaunch.
- **Bounded queries**: `getLatestByChannel(limit=200)` — never load unbounded lists from DB.
- **`@IoDispatcher`** for all Room and network I/O; main thread is never blocked.

## Protocol

Two proto files auto-generated into `:core-proto`:
- `api/api.proto` — REST request/response messages (`com.mezon.mezon.api`)
- `rtapi/realtime.proto` — WebSocket `Envelope` oneof 94 event types (`com.mezon.mezon.rtapi`)

WebSocket URL: `wss://<ws_url>/ws?token=<token>&status=true&platform=1&lang=en&format=protobuf`

REST: Bearer token, `Content-Type: application/proto`. Exception: auth uses JSON + Basic auth.
