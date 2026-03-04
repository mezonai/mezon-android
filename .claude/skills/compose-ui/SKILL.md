---
name: compose-ui
description: Best practices for building UI with Jetpack Compose — state hoisting, modifiers, performance, theming, previews. Use when writing or refactoring Composable functions.
---

# Jetpack Compose Best Practices

## When to Use

- Writing new Composable functions
- Refactoring existing UI code
- Reviewing Compose performance
- Setting up themes and design systems

## 1. State Hoisting (Unidirectional Data Flow)

Make Composables stateless whenever possible:

```kotlin
@Composable
fun MyComponent(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
)
```

Screen-level Composable retrieves state from ViewModel: `viewModel.uiState.collectAsStateWithLifecycle()`

## 2. Modifiers

- Always provide `modifier: Modifier = Modifier` as the first optional parameter
- Apply this `modifier` to the root layout element
- Ordering matters: `padding().clickable()` differs from `clickable().padding()`

## 3. Performance

- `remember { ... }` to cache expensive calculations across recompositions
- `derivedStateOf { ... }` when a state changes frequently but UI only needs a threshold
- Prefer method references (`viewModel::onEvent`) or remembered lambdas for stability
- Hoist `RoundedCornerShape`, `Color`, `TextStyle` as top-level vals

## 4. Theming

- Use `MaterialTheme.colorScheme` and `MaterialTheme.typography` — never hardcode
- Organize shared UI into `ui/components/` and `ui/theme/`

## 5. Previews

- Create `@Preview(showBackground = true)` for every public Composable
- Include Light/Dark mode previews
- Pass dummy data to the stateless Composable
