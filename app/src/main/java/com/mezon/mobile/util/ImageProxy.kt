package com.mezon.mobile.util

import com.mezon.mobile.BuildConfig
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private const val IMGPROXY_BASE_URL = BuildConfig.MEZON_IMGPROXY_BASE_URL
private const val IMGPROXY_KEY = BuildConfig.MEZON_IMGPROXY_KEY
private const val MAX_BYTES = 2_097_152
private const val MAX_PROXY_DIM = 1200
private const val AVATAR_PROXY_SIZE_PX = 100
private const val MEDIA_THUMBNAIL_SIZE_PX = 200
private val SKIP_PROXY_EXTENSIONS = setOf("gif", "webp")
private val IMGPROXY_HOSTS = setOfNotNull(
    "imgproxy.mezon.ai", "imgproxy.mezon.vn", "imgproxy.komu.vn", "imgproxy.komu.ai",
    "dev-imgproxy.nccsoft.vn",
    runCatching { URI(IMGPROXY_BASE_URL).host?.lowercase() }.getOrNull()
)

private fun isImgproxyUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme?.lowercase() in setOf("http", "https") && uri.host?.lowercase() in IMGPROXY_HOSTS
}.getOrDefault(false)

private const val FIXED_IMAGE_CACHE_VERSION = "fixed-images-100-200-v1:"

fun fixedSizeImageCacheKey(url: String): String {
    if (url.startsWith(FIXED_IMAGE_CACHE_VERSION) || !isImgproxyUrl(url)) return url
    val options = url.substringBefore("/plain/").split('/')
    for (option in options) {
        val parts = option.split(':')
        if (parts.size < 4 || parts[0] !in setOf("rs", "resize")) continue
        if ((parts[1] == "fill-down" && parts[2] == "100" && parts[3] == "100") ||
            (parts[1] == "fill" && parts[2] == "200" && parts[3] == "200")
        ) return FIXED_IMAGE_CACHE_VERSION + url
    }
    return url
}

/** Normalize avatar/logo requests to a shared 100px rendition, including existing proxy URLs. */
fun normalizeAvatarImgproxyUrl(sourceUrl: String): String =
    resizeExistingImgproxyUrl(sourceUrl, AVATAR_PROXY_SIZE_PX, "fill-down", extendCanvas = true)

private fun resizeExistingImgproxyUrl(
    sourceUrl: String,
    sidePx: Int,
    resizeType: String,
    extendCanvas: Boolean
): String {
    if (!isImgproxyUrl(sourceUrl)) return sourceUrl
    val optionsEnd = sourceUrl.indexOf("/plain/").takeIf { it >= 0 } ?: sourceUrl.length
    val options = sourceUrl.substring(0, optionsEnd).split('/').joinToString("/") { part ->
        val resize = part.split(':').toMutableList()
        val width = resize.getOrNull(2)?.toIntOrNull()
        val height = resize.getOrNull(3)?.toIntOrNull()
        if (resize.firstOrNull() in setOf("rs", "resize") &&
            width != null && height != null && width >= 0 && height >= 0 && (width > 0 || height > 0)
        ) {
            while (resize.size < 5) resize.add("")
            resize[1] = resizeType
            resize[2] = sidePx.toString()
            resize[3] = sidePx.toString()
            resize[4] = if (extendCanvas) "0" else "1"
            if (extendCanvas) {
                while (resize.size < 6) resize.add("")
                resize[5] = "1"
            } else if (resize.size >= 6) {
                resize[5] = "0"
            }
            resize.joinToString(":")
        } else {
            part
        }
    }
    return options + sourceUrl.substring(optionsEnd)
}

private val CONFIGURED_MEDIA_BASES = listOf(
    BuildConfig.CLOUDFLARE_CDN_URL,
    BuildConfig.MEZON_CDN_URL,
    BuildConfig.PROFILE_CDN_URL,
)
    .map { it.trim().trimEnd('/') }
    .filter { it.isNotEmpty() }

fun isConfiguredMediaUrl(url: String): Boolean =
    CONFIGURED_MEDIA_BASES.any { url.startsWith("$it/") }

private fun sourceExtension(url: String): String {
    if (url.isEmpty()) return ""
    val path = try {
        java.net.URI(url).path ?: ""
    } catch (_: Exception) {
        url
    }
    val dot = path.lastIndexOf('.')
    return if (dot >= 0 && dot < path.length - 1) path.substring(dot + 1).lowercase() else ""
}

private fun shouldSkipProxy(sourceUrl: String): Boolean =
    SKIP_PROXY_EXTENSIONS.contains(sourceExtension(sourceUrl))

fun createImgproxyUrl(
    sourceUrl: String,
    widthPx: Int,
    heightPx: Int,
    resizeType: String = "fit",
    maxEdgePx: Int = MAX_PROXY_DIM,
    extendCanvas: Boolean = false
): String {
    if (sourceUrl.isEmpty()) return sourceUrl
    if (!sourceUrl.startsWith("https://cdn.mezon") && !sourceUrl.startsWith("https://cdn.komu") && !sourceUrl.startsWith("https://profile.mezon") && !isConfiguredMediaUrl(sourceUrl)) {
        return sourceUrl
    }
    val cap = maxEdgePx.coerceAtLeast(1).coerceAtMost(4096)
    val w = widthPx.coerceAtMost(cap)
    val h = heightPx.coerceAtMost(cap)
    val resizeFlags = if (extendCanvas) "0:1" else "1"
    val options = "rs:$resizeType:$w:$h:$resizeFlags/mb:$MAX_BYTES"
    return "$IMGPROXY_BASE_URL/$IMGPROXY_KEY/$options/plain/$sourceUrl@webp"
}

fun plainSourceUrlFromImgproxy(processedUrl: String): String? {
    val marker = "/plain/"
    val idx = processedUrl.indexOf(marker)
    if (idx < 0) return null
    var tail = processedUrl.substring(idx + marker.length)
    if (tail.endsWith("@webp")) {
        tail = tail.removeSuffix("@webp")
    }
    val decoded = try {
        URLDecoder.decode(tail, StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        tail
    }
    return decoded.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

fun absoluteResourceUrl(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    if (t.startsWith("http://") || t.startsWith("https://")) {
        if (t.startsWith("http://") && (t.contains("cdn.mezon") || t.contains("cdn.komu") || t.contains("profile.mezon"))) {
            return "https://${t.removePrefix("http://")}"
        }
        return t
    }
    if (t.startsWith("//")) return "https:$t"
    val base = BuildConfig.CLOUDFLARE_CDN_URL.trimEnd('/')
    return if (t.startsWith("/")) "$base$t" else "$base/$t"
}

/** Avatars and logos share a 100px canvas without upscaling small source images. */
fun iconImgproxyUrl(sourceUrl: String): String {
    val source = sourceUrl.trim()
    if (source.isEmpty()) return source
    if (isImgproxyUrl(source)) return normalizeAvatarImgproxyUrl(source)
    if (shouldSkipProxy(source)) return source
    return createImgproxyUrl(
        source, AVATAR_PROXY_SIZE_PX, AVATAR_PROXY_SIZE_PX, "fill-down",
        extendCanvas = true
    )
}

// Keep the display-size argument for existing callers; network renditions always use 100px.
@Suppress("UNUSED_PARAMETER")
fun avatarImgproxyUrl(sourceUrl: String, sizePx: Int): String = iconImgproxyUrl(sourceUrl)

/** The channel media grid shares a 200px thumbnail, independently of view size and density. */
fun channelMediaThumbnailImgproxyUrl(sourceUrl: String): String {
    val source = sourceUrl.trim()
    if (isImgproxyUrl(source)) {
        return resizeExistingImgproxyUrl(source, MEDIA_THUMBNAIL_SIZE_PX, "fill", extendCanvas = false)
    }
    return createImgproxyUrl(source, MEDIA_THUMBNAIL_SIZE_PX, MEDIA_THUMBNAIL_SIZE_PX, "fill")
}

fun isAnimatedImageUrl(url: String): Boolean {
    if (url.isEmpty()) return false
    val ext = sourceExtension(url)
    return ext == "gif" || ext == "webp"
}
