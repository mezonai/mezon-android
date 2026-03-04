---
name: gradle-performance
description: Gradle build optimization — version catalogs, build cache, parallel builds, dependency analysis. Use when configuring Gradle or improving build times.
---

# Gradle Performance

## When to Use

- Configuring `build.gradle.kts` or `libs.versions.toml`
- Improving build speed
- Adding/updating dependencies
- Reviewing Gradle configuration

## Version Catalog (`libs.versions.toml`)

ALL dependency versions must be defined in `gradle/libs.versions.toml`:

```toml
[versions]
room = "2.7.1"

[libraries]
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
```

NEVER hardcode versions in `build.gradle.kts`.

## Build Cache & Parallel Builds

In `gradle.properties`:

```properties
org.gradle.caching=true
org.gradle.parallel=true
org.gradle.configuration-cache=true
org.gradle.jvmargs=-Xmx4g -XX:+UseG1GC
```

## KSP over KAPT

Use KSP (`ksp(...)`) instead of KAPT (`kapt(...)`) for Room, Hilt, and other annotation processors.

## Dependency Management

- Use `implementation` (not `api`) unless the dependency is part of the module's public API
- Avoid unused dependencies — they slow compilation
- Group related dependencies with BOM when available
