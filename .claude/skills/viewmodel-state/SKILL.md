---
name: viewmodel-state
description: Best practices for Android ViewModel — StateFlow for UI state, SharedFlow for one-off events, flatMapLatest for channel-scoped flows. Use when implementing or reviewing ViewModels.
---

# ViewModel & State Management

## When to Use

- Creating new ViewModels
- Reviewing ViewModel state management patterns
- Fixing state-related bugs (stale data, unnecessary emissions)

## 1. UI State (StateFlow)

```kotlin
private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
val uiState: StateFlow<UiState> = _uiState.asStateFlow()
```

- Use `.update { oldState -> ... }` for thread-safe updates
- NEVER expose `MutableStateFlow` publicly

## 2. One-Off Events (SharedFlow)

```kotlin
private val _uiEvent = MutableSharedFlow<UiEvent>(replay = 0)
val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()
```

- `replay = 0` prevents re-triggering on rotation
- Send via `.emit(event)` (suspend) or `.tryEmit(event)`

## 3. Channel-Scoped Flows

```kotlin
val uiState: StateFlow<ChatUiState> = _channelId
    .flatMapLatest { channelId ->
        if (channelId == null) return@flatMapLatest flowOf(ChatUiState.Loading)
        combine(
            messageStore.getChannelFlow(channelId),
            _isLoadingMore
        ) { channelState, isLoadingMore -> ... }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState.Loading)
```

## 4. Collecting in Compose

- `collectAsStateWithLifecycle()` for StateFlow
- `LaunchedEffect` for SharedFlow collection

## 5. Scope

- Use `viewModelScope` for all ViewModel coroutines
- Delegate to Repositories/Stores for actual data operations
