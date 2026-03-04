---
name: data-layer
description: Data layer guidance — Repository pattern, Room local + REST remote, offline-first sync, dual-write Store. Use when implementing data fetching, caching, or persistence.
---

# Data Layer & Offline-First

## When to Use

- Implementing new data fetching features
- Setting up Room entities and DAOs
- Configuring offline-first synchronization
- Reviewing repository patterns

## 1. Repository Pattern

- Single Source of Truth (SSOT): Store is the SSOT, backed by Room
- Repository decides cached data vs fresh fetch
- Expose data from Store (in-memory StateFlow), not raw DAO Flows

## 2. Local Persistence (Room)

- `@Entity` data classes with `@PrimaryKey` and `@Index`
- `@Upsert` over `@Insert(onConflict = REPLACE)`
- Use `suspend fun` for one-shot queries
- ALWAYS use LIMIT for queries loading into memory cache
- WAL mode enabled for concurrent reads/writes

## 3. Remote Data (Ktor + Protobuf)

- All REST calls via `MezonApi` with Bearer token
- Response bodies are protobuf-encoded
- Wrap in `runCatching` for error handling

## 4. Synchronization (Stale-While-Revalidate)

```kotlin
suspend fun loadMessages(channelId: Long) = withContext(ioDispatcher) {
    runCatching {
        val cached = messageStore.loadFromDb(channelId)
        if (cached.isNotEmpty()) return@runCatching
        val response = api.listChannelMessages(...)
        messageStore.setMessages(channelId, ...)
    }
}
```

## 5. Dependency Injection

- Repositories provided as `@Singleton` via Hilt constructor injection
- DAOs provided via `DatabaseModule`
