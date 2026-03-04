---
name: kotlin-coroutines
description: Kotlin Coroutines expert — structured concurrency, lifecycle safety, dispatcher injection, exception handling. Use when writing async code, fixing coroutine bugs, or reviewing concurrency patterns.
---

# Kotlin Coroutines Rules

## When to Use

- Writing any async/concurrent code
- Reviewing coroutine usage for bugs
- Fixing memory leaks, ANRs, race conditions
- Converting callback APIs to Flow

## Dispatcher Injection

ALWAYS inject `CoroutineDispatcher` via constructor (`@IoDispatcher`, `@ApplicationScope`). NEVER hardcode `Dispatchers.IO` inside classes.

```kotlin
// CORRECT
class UserRepository(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO)

// INCORRECT
class UserRepository { fun getData() = withContext(Dispatchers.IO) { ... } }
```

## Main-Safety

All suspend functions in Data/Domain layers MUST be main-safe. Use `withContext(ioDispatcher)` internally.

## Exception Handling

```kotlin
try {
    doSuspendWork()
} catch (e: CancellationException) {
    throw e  // MUST rethrow!
} catch (e: Exception) {
    handleError(e)
}
```

NEVER swallow `CancellationException` in generic catch blocks.

## Cooperative Cancellation

Add `ensureActive()` in tight loops for cooperative cancellation.

## Callback Conversion

Use `callbackFlow` with `awaitClose` to convert callback APIs to Flow.

## Scope Guidelines

| Scope | Use When | Lifecycle |
|-------|----------|-----------|
| `viewModelScope` | ViewModel operations | Cleared with ViewModel |
| `lifecycleScope` | UI operations | Destroyed with lifecycle owner |
| `@ApplicationScope` | App-wide background work | Application lifetime |
| `GlobalScope` | **NEVER USE** | Breaks structured concurrency |

## State Encapsulation

- NEVER expose `MutableStateFlow` or `MutableSharedFlow` publicly
- Always `.asStateFlow()` / `.asSharedFlow()`
