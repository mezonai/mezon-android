---
name: compose-performance-audit
description: Audit Jetpack Compose for performance — stability, recomposition, LazyColumn keys, remembered lambdas. Use when profiling or optimizing Compose UI performance.
---

# Compose Performance Audit

## When to Use

- Profiling recomposition counts
- Optimizing LazyColumn/LazyRow scrolling
- Fixing jank or dropped frames
- Reviewing Composable stability

## Stability Checklist

- Use `@Immutable` or `@Stable` on data classes passed to Composables
- Use `kotlinx.collections.immutable` (`ImmutableList`, `PersistentList`)
- Avoid passing lambda literals inline; use method references or `remember { }` wrappers
- Hoist constant values (`Shape`, `Color`, `TextStyle`) as top-level or companion vals

## LazyColumn/LazyRow

- ALWAYS provide `key = { item.id }` — must be stable (Long/Int preferred)
- ALWAYS provide `contentType = { ... }` for heterogeneous lists
- Avoid using `items(list.size) { index -> }` — use `items(list, key, contentType)`
- NEVER wrap LazyColumn in a scrollable Column
- Use `derivedStateOf` for scroll-dependent state (e.g., show/hide FAB)

## Image Loading (Coil)

- `AsyncImage` with `crossfade(true)`
- Set explicit `size()` to prevent intrinsic measurement
- Use memory + disk cache

## Layout

- Minimize nesting — prefer `Box`, `Row`, `Column` over deep trees
- Use `Modifier.drawBehind` instead of `Canvas` Composable when possible
- Avoid `Modifier.onGloballyPositioned` in hot paths
