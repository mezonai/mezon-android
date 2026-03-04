---
name: accessibility
description: Android accessibility — contentDescription, semantics, touch targets, TalkBack. Use when building UI or auditing accessibility compliance.
---

# Accessibility

## When to Use

- Adding contentDescription to images and icons
- Ensuring touch target sizes meet guidelines
- Testing with TalkBack
- Reviewing UI for accessibility compliance

## Content Descriptions

```kotlin
Icon(
    imageVector = Icons.Default.Send,
    contentDescription = "Send message"
)

AsyncImage(
    model = avatarUrl,
    contentDescription = "$username avatar"
)
```

- Decorative images: `contentDescription = null`
- Actionable images: MUST have a description

## Touch Targets

Minimum touch target: 48dp x 48dp

```kotlin
IconButton(onClick = { ... }) {
    Icon(Icons.Default.Close, contentDescription = "Close")
}
```

`IconButton` already ensures 48dp minimum. For custom touchables, use `Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)`.

## Semantics

```kotlin
Row(
    modifier = Modifier.semantics(mergeDescendants = true) { }
) {
    Text(username)
    Text(timestamp)
}
```

- `mergeDescendants = true` groups related text for TalkBack
- Use `Modifier.clearAndSetSemantics { }` to override child semantics

## Color Contrast

- Normal text: minimum 4.5:1 contrast ratio
- Large text (18sp+): minimum 3:1
- Use `MaterialTheme.colorScheme` which is designed for accessibility

## Testing

- Enable TalkBack and navigate the app by swipe
- Use Accessibility Scanner from Google Play
- Run `composeTestRule.onRoot().printToLog("TREE")` to inspect semantics tree
