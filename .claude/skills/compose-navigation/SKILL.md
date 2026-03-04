---
name: compose-navigation
description: Jetpack Compose Navigation with type-safe routes, Hilt ViewModels, deep links. Use when adding screens, routes, or navigation logic.
---

# Compose Navigation

## When to Use

- Adding new screens to the app
- Setting up navigation graphs
- Implementing deep links
- Reviewing navigation architecture

## Type-Safe Routes

Define routes as data objects:

```kotlin
@Serializable
data object HomeRoute

@Serializable
data class ChatRoute(val channelId: Long, val channelName: String)
```

## NavHost Setup

```kotlin
NavHost(navController, startDestination = HomeRoute) {
    composable<HomeRoute> { HomeScreen(onNavigateToChat = { id, name ->
        navController.navigate(ChatRoute(id, name))
    }) }
    composable<ChatRoute> { ChatScreen(onBack = { navController.popBackStack() }) }
}
```

## ViewModel Integration

- Hilt provides `@HiltViewModel` automatically via `hiltViewModel()`
- Route arguments are available in `SavedStateHandle`

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository
) : ViewModel() {
    private val args = savedStateHandle.toRoute<ChatRoute>()
    private val channelId = args.channelId
}
```

## Best Practices

- NEVER pass complex objects as nav arguments — use IDs and fetch from Store/DB
- Single `NavHost` in `MainActivity`
- Navigate with `navController.navigate(Route) { launchSingleTop = true }`
