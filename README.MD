# Mezon Android

Native Android client for the Mezon messaging platform. The app is built in **Kotlin** with a **Telegram-style** UI layer: custom `Canvas`-based cells, a custom navigation stack, and **Room** for offline-first persistence. Architecture centers on controllers, `NotificationCenter`, and custom views (no Jetpack Compose for primary UI).

---

## Table of contents

- [Overview](#overview)
- [Requirements](#requirements)
- [Getting started](#getting-started)
- [Protocol buffer setup](#protocol-buffer-setup)
- [Firebase configuration](#firebase-configuration)
- [Build and test](#build-and-test)
- [Updating `mezon-protocol`](#updating-mezon-protocol)
- [Architecture](#architecture)
- [Repository layout](#repository-layout)
- [Technology stack](#technology-stack)
- [Performance notes](#performance-notes)
- [Protocol and networking](#protocol-and-networking)

---

## Overview

| Area | Approach |
|------|----------|
| **UI** | Custom `View` / `ViewGroup` cells (`BaseCell`), `StaticLayout`, shared paints from `ThemeColors` — optimized for list scrolling |
| **State** | `@Singleton` controllers hold in-memory caches (`ArrayList`, `LongSparseArray`); UI updates via `NotificationCenter` (not `StateFlow` for screens) |
| **Persistence** | Dual-write: update memory first, then async Room `@Upsert` on I/O dispatcher |
| **Realtime** | OkHttp WebSocket with protobuf `Envelope`; events fanned out in `SocketEventDispatcher` |
| **Remote API** | Ktor + OkHttp, protobuf request/response bodies |

---

## Requirements

| Tool | Notes |
|------|--------|
| **Android Studio** | Current stable channel (e.g. Ladybug or newer) |
| **JDK** | 17+ (bundled with Android Studio is fine) |
| **Android SDK** | `compileSdk 35`, `minSdk 24` (see `app/build.gradle.kts`) |
| **Kotlin** | 1.9.x (see `gradle/libs.versions.toml`) |

---

## Getting started

### 1. Clone this repository (including submodules)

Protobuf definitions live in **`mezon-protocol`**, included as a **Git submodule** at the repo root.

```bash
git clone --recurse-submodules <mezon-android-repository-url> ~/AndroidStudioProjects/mezon
```

If you already cloned without submodules:

```bash
cd ~/AndroidStudioProjects/mezon
git submodule update --init --recursive
```

The submodule tracks [github.com/mezonai/mezon-protocol](https://github.com/mezonai/mezon-protocol) (pinned commit in `.gitmodules` / superproject).

### 2. Open and sync

Open the `mezon/` directory in Android Studio and let Gradle sync complete.

---

## Protocol buffer setup

- Generated sources live in the **`:core-proto`** module.
- `.proto` files are read from the **`mezon-protocol`** submodule (Gradle uses the repo root so imports like `api/api.proto` stay valid).

---

## Firebase configuration

`app/google-services.json` is not committed. Obtain it from the Firebase project (e.g. `mezon-772fa`) or from your team, then place it at:

```text
mezon/app/google-services.json
```

Copy `mezon/mezon.secrets.properties.example` to `mezon/mezon.secrets.properties` and fill in values (the real file is not committed; obtain from your team or internal secret store). Gradle fails early if this file is missing.

Both attachment CDN keys are required, with no trailing slash:

| Key | Used when the server returns |
| --- | --- |
| `MEZON_BASE_IMG_URL` | `type_cdn = 2` |
| `MEZON_BASE_MEZON_IMG_URL` | `type_cdn = 1` |

For existing setups, add `MEZON_BASE_MEZON_IMG_URL` to your `mezon.secrets.properties` and obtain the values for both CDN keys from your team or internal secret store. Gradle rejects missing or empty values during configuration. These origins are used to construct attachment URLs for viewing; uploads continue to use the presigned URL returned by the server.

---

## Build and test

All commands are run from the `mezon/` directory:

```bash
cd mezon

./gradlew assembleDebug
./gradlew installDebug
./gradlew assembleRelease
./gradlew test
```

For a full debug build including proto generation:

```bash
./gradlew assembleDebug
```

---

## Updating `mezon-protocol`

Point the submodule at a newer commit, then regenerate:

```bash
cd /path/to/mezon/mezon-protocol
git fetch origin
git checkout main   # or a release tag
git pull origin main

cd /path/to/mezon
git add mezon-protocol
git commit -m "Bump mezon-protocol submodule"

./gradlew :core-proto:generateDebugProto
./gradlew app:compileDebugKotlin
```

To only sync your working copy to the commit recorded by the parent repo (no bump):

```bash
cd /path/to/mezon
git submodule update --init --recursive
```

---

## Architecture

Data flow (high level):

```text
WebSocket ──► SocketEventDispatcher ──► Controller (cache + async Room)
REST     ──► Controller                ──► NotificationCenter.postOnMainThread
Room     ──► Controller init / cold load ──► same caches + UI events

NotificationCenter ──► BaseFragment.observe() ──► adapters / cell.invalidate / partial row updates
```

### Layers

| Layer | Responsibility |
|-------|------------------|
| **Controller** | `@Singleton` services: synchronized in-memory models, REST and socket side effects, dual-write to Room, post NC events from `init` / API / socket |
| **NotificationCenter** | Main-thread event bus (integer event IDs); fragments register with `BaseFragment.observe()` |
| **BaseFragment** | Custom lifecycle (managed by `ActionBarLayout`, not `AndroidX FragmentManager` for the main stack) |
| **Cells** | `BaseCell` subclasses: `onDraw(Canvas)`, `update(mask)`, shared theme paints |
| **Room** | WAL, `@Upsert`, bounded list queries (e.g. message cap per channel) |
| **SocketEventDispatcher** | Demultiplexes `Envelope` into typed `SharedFlow`s for controllers |

### Navigation

Single-activity: **`MainActivity`**. Screen stack and transitions use **`ActionBarLayout`** (custom `ArrayList` of `BaseFragment`, animated transitions, swipe-back). This is not the AndroidX Navigation Component graph.

### Example `NotificationCenter` consumers

| Event (examples) | Typical publisher | Typical subscriber |
|--------------------|-------------------|--------------------|
| `dialogsNeedReload` | `DialogsController` / messages pipeline | `MessagesFragment` |
| `messagesDidLoad` / new/update/delete | `ChatController` | `ChatFragment` |
| `clansDidLoad` / channels | `ClansController` / `ChannelController` | `ClansFragment` |
| `themeChanged` / `languageChanged` | `ThemeManager` / `LocaleManager` | Fragments via rebuild or observers |
| `connectionStateChanged` | `ConnectionController` | Shell / home |
| `sessionExpired` | `AuthRepository` | `MainActivity` |

The canonical list and IDs live in `NotificationCenter` and related controllers.

---

## Repository layout

```text
app/src/main/java/com/mezon/mobile/
├── MainActivity.kt
├── MezonApplication.kt
├── auth/                 # AuthRepository, LoginFragment
├── core/                 # BaseFragment, ActionBarLayout, NotificationCenter, ThemeColors, BaseCell, …
├── data/db/              # MezonDatabase, DAOs, entities
├── di/                   # Hilt modules, dispatchers
├── home/                 # Controllers, MainTabsActivity, chat / messages / clans / profile / notifications
├── network/              # MezonApi, MezonSocket, SocketEventDispatcher, ApiCacheTracker
├── notification/        # FCM, local notifications, active channel
├── session/              # SessionManager, theme, locale
├── ui/cells/             # Reusable custom views (action bar, settings rows, …)
└── util/                 # ContentParser, image helpers, …
```

A fuller map of types may exist in the parent workspace (e.g. `CLAUDE.md` at the monorepo root, if present).

---

## Technology stack

| Area | Technology |
|------|------------|
| Language | Kotlin 1.9.x |
| Build | Gradle with Kotlin DSL, **KSP** (Room, Hilt) — versions in `gradle/libs.versions.toml` |
| DI | Hilt |
| HTTP | Ktor + OkHttp |
| WebSocket | OkHttp, binary protobuf |
| DB | Room, WAL, `@Upsert` |
| Images | Custom `MezonImageLoader` (OkHttp, memory + disk cache) + `ImageReceiver` / `AvatarDrawable` |
| Session | DataStore Preferences |
| Push | Firebase Cloud Messaging (see BOM in version catalog) |
| Messages | Protobuf Lite (`:core-proto`) |
| Concurrency | Kotlin coroutines, `@ApplicationScope` / `@IoDispatcher` |

---

## Performance notes

- **Lists**: `DiffUtil` where appropriate; `updateVisibleRows(mask)`-style partial updates to avoid full adapter churn; scroll-state guards where implemented.
- **Controllers**: in-memory update first, Room write off the main thread.
- **Cold start**: load bounded data from Room in controllers, then refresh from network.
- **API**: `ApiCacheTracker` (TTL) to reduce duplicate REST work.
- **Canvas**: avoid per-frame allocations in `onDraw`; reuse layouts and paints per cell instance as documented in project guidelines.

---

## Protocol and networking

| Artifact | Role |
|----------|------|
| `api/api.proto` | REST messages (`com.mezon.mezon.api`) |
| `rtapi/realtime.proto` | WebSocket `Envelope` and realtime payloads (`com.mezon.mezon.rtapi`) |

- **WebSocket** (illustrative): `wss://<ws_url>/ws?token=<token>&status=true&platform=1&lang=en&format=protobuf`
- **REST**: `Content-Type: application/proto` with bearer token (auth flows may use JSON + Basic as defined by the API).

---

*Internal engineering documentation: keep setup steps in sync with `gradle/libs.versions.toml` and `app/build.gradle.kts` when versions change.*
