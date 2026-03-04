---
name: coil-compose
description: Image loading with Coil 3 in Jetpack Compose — AsyncImage, caching, placeholders, transformations. Use when loading images or avatars.
---

# Coil Image Loading

## When to Use

- Loading network images (avatars, thumbnails, attachments)
- Setting up image caching strategy
- Implementing placeholder/error states
- Circle-cropping avatars

## Basic Usage

```kotlin
AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data(url)
        .crossfade(true)
        .build(),
    contentDescription = description,
    modifier = modifier
        .size(48.dp)
        .clip(CircleShape),
    contentScale = ContentScale.Crop
)
```

## Caching

Configure in `Application.onCreate()` or Hilt module:

```kotlin
ImageLoader.Builder(context)
    .memoryCachePolicy(CachePolicy.ENABLED)
    .diskCachePolicy(CachePolicy.ENABLED)
    .memoryCache {
        MemoryCache.Builder()
            .maxSizePercent(context, 0.25)
            .build()
    }
    .diskCache {
        DiskCache.Builder()
            .directory(context.cacheDir.resolve("image_cache"))
            .maxSizePercent(0.02)
            .build()
    }
    .build()
```

## Performance

- Set explicit `Modifier.size()` to avoid intrinsic size measurement
- Use `crossfade(true)` for smooth transitions
- Provide `placeholder` and `error` drawables
- Use `SubcomposeAsyncImage` only when you need Composable placeholders
