# Getting Started

Step-by-step guide to set up and run the Mezon Android project.

## Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or newer
- **JDK 17** (bundled with Android Studio)
- **Android SDK 34** (install via SDK Manager)
- **Git**

## 1. Clone the repository

```bash
git clone <repo-url>
cd mezon
```

## 2. Clone mezon-protocol (for proto files)

The `:core-proto` module uses symlinks to `.proto` files from the `mezon-protocol` repository:

```bash
git clone https://github.com/mezonai/mezon-protocol.git /path/to/mezon-protocol
```

Then create the symlinks (if they don't already exist):

```bash
mkdir -p core-proto/src/main/proto
ln -s /path/to/mezon-protocol/api   core-proto/src/main/proto/api
ln -s /path/to/mezon-protocol/rtapi core-proto/src/main/proto/rtapi
```

> Replace `/path/to/mezon-protocol` with the actual path where you cloned the repo.

## 3. Open in Android Studio

1. Open Android Studio
2. **File > Open** > select the `mezon` project root folder
3. Wait for Gradle Sync to complete (downloads all dependencies)

## 4. Build the project

**From Android Studio:**

Click **Build > Make Project** (`Ctrl+F9` / `Cmd+F9`)

**From command line:**

```bash
./gradlew assembleDebug
```

## 5. Run the app

1. Connect a physical device (USB debugging enabled) or start an Android Emulator (API 24+)
2. Select the device in the toolbar
3. Click **Run** (`Shift+F10` / `Ctrl+R`)

**From command line:**

```bash
./gradlew installDebug
adb shell am start -n ai.mezon.app/.MainActivity
```

## 6. Login

The app starts with a login screen. Use your Mezon account email and password.

After login:
- Bottom navigation: **Clans** | **Messages** | **Notifications** | **Profile**
- WebSocket connects automatically (green indicator = connected)
- Messages tab loads your DM/group chat list — cached from Room on relaunch, fresh from API otherwise

## Project Structure

```
mezon/
├── app/                              # Main application module
│   └── src/main/java/ai/mezon/app/
│       ├── auth/                     # Login (MVI: ViewModel, Repository, Screen)
│       ├── data/db/                  # Room: MezonDatabase, MessageDao, DirectMessageDao
│       ├── di/                       # Hilt DI: AppModule, DatabaseModule, qualifiers
│       ├── home/                     # Home screen + bottom navigation
│       │   ├── chat/                 # Chat thread: MessageEntity, MessageStore,
│       │   │                         # ChatRepository, ChatViewModel, ChatScreen,
│       │   │                         # MessageAdapter (RecyclerView)
│       │   ├── messages/             # DM list: DirectMessage, DirectStore,
│       │   │                         # DirectRepository, MessagesViewModel, MessagesScreen
│       │   ├── clans/                # Clans tab
│       │   ├── notifications/        # Notifications tab
│       │   └── profile/              # Profile tab (with logout)
│       ├── navigation/               # Compose Navigation (NavGraph, routes)
│       ├── network/                  # MezonApi (REST), MezonSocket (WebSocket),
│       │                             # SocketEventDispatcher
│       └── session/                  # Session persistence (DataStore)
│   └── src/main/res/
│       ├── drawable/                 # bg_bubble_sent.xml, bg_bubble_received.xml,
│       │                             # bg_avatar_circle.xml
│       └── layout/                   # item_message_sent.xml, item_message_received.xml
├── core-proto/                       # Protobuf code generation module
│   └── src/main/proto/
│       ├── api/   -> mezon-protocol/api/api.proto
│       └── rtapi/ -> mezon-protocol/rtapi/realtime.proto
├── gradle/libs.versions.toml         # Dependency version catalog
├── CLAUDE.md                         # AI coding assistant instructions
└── SETUP.md                          # This file
```

## Key Commands

| Command | Description |
|---------|-------------|
| `./gradlew assembleDebug` | Build debug APK |
| `./gradlew assembleRelease` | Build release APK |
| `./gradlew test` | Run unit tests |
| `./gradlew connectedAndroidTest` | Run instrumented tests |
| `./gradlew :core-proto:assembleDebug` | Generate protobuf classes only |
| `./gradlew app:dependencies` | Show dependency tree |

## Tech Stack

| Layer | Technology |
|-------|-----------|
| UI (screens) | Jetpack Compose + Material3 |
| Chat message list | RecyclerView + ListAdapter + DiffUtil (via AndroidView) |
| Architecture | MVI (ViewModel + StateFlow) + Dual-Write Store |
| Persistence | Room 2.6.1 (SQLite, WAL mode, KSP generated) |
| DI | Hilt (KSP) + DatabaseModule |
| REST API | Ktor Client + OkHttp engine |
| WebSocket | OkHttp WebSocket + Protobuf binary Envelope |
| Images | Coil 2.6.0 |
| Session | DataStore Preferences |
| Proto | Protobuf Lite (auto-generated from .proto files) |

## Architecture Overview

```
WebSocket ──→ SocketEventDispatcher ──→ Store (StateFlow + async Room write)
REST API  ──→ Repository            ──→ Store
Room DB   ──→ Store.init()          ──→ instant UI on cold start
Store     ──→ ViewModel             ──→ Screen (Compose + RecyclerView)
```

- **Dual-write**: every socket event or REST response updates in-memory `StateFlow` first (instant UI), then writes to Room asynchronously (persistence).
- **Cold start**: `Store.init()` loads from Room before making REST calls — user sees cached data immediately.
- **RecyclerView**: used for chat message list for Telegram-level scroll performance. DM list uses `LazyColumn`.

## Troubleshooting

### Proto symlinks not working (Windows)

Windows requires admin privileges for symlinks. Copy instead:

```bash
xcopy /E /I \path\to\mezon-protocol\api   core-proto\src\main\proto\api
xcopy /E /I \path\to\mezon-protocol\rtapi core-proto\src\main\proto\rtapi
```

### "Unresolved reference" errors in IDE

Generated protobuf classes require a build first:

```bash
./gradlew :core-proto:assembleDebug
```

Then: **File > Sync Project with Gradle Files**

### KSP version mismatch

KSP version must match Kotlin version. Check `gradle/libs.versions.toml`:

- Kotlin `1.9.0` requires KSP `1.9.0-1.0.13`

### Room schema changes

Room uses `fallbackToDestructiveMigration()` during development — the DB is dropped and recreated on schema changes. Before releasing to production, replace with proper `Migration` objects.
