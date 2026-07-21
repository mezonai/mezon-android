package com.mezon.mobile.util

import com.mezon.mobile.BuildConfig
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private const val IMGPROXY_BASE_URL = BuildConfig.MEZON_IMGPROXY_BASE_URL
private const val IMGPROXY_KEY = BuildConfig.MEZON_IMGPROXY_KEY
private const val MAX_BYTES = 2_097_152
private const val MAX_PROXY_DIM = 1200

fun createImgproxyUrl(
    sourceUrl: String,
    widthPx: Int,
    heightPx: Int,
    resizeType: String = "fit",
    maxEdgePx: Int = MAX_PROXY_DIM
): String {
    if (sourceUrl.isEmpty()) return sourceUrl
    if (!sourceUrl.startsWith("https://cdn.mezon") && !sourceUrl.startsWith("https://cdn.komu") && !sourceUrl.startsWith("https://profile.mezon")) {
        return sourceUrl
    }
    val cap = maxEdgePx.coerceAtLeast(1).coerceAtMost(4096)
    val w = widthPx.coerceAtMost(cap)
    val h = heightPx.coerceAtMost(cap)
    val options = "rs:$resizeType:$w:$h:1/mb:$MAX_BYTES"
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

private val AVATAR_BUCKETS_PX = intArrayOf(192, 256)

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
    val base = BuildConfig.MEZON_BASE_IMG_URL.trimEnd('/')
    return if (t.startsWith("/")) "$base$t" else "$base/$t"
}

fun avatarImgproxyUrl(sourceUrl: String, sizePx: Int): String {
    if (sourceUrl.isEmpty()) return sourceUrl
    val bucket = AVATAR_BUCKETS_PX.firstOrNull { it >= sizePx } ?: AVATAR_BUCKETS_PX.last()
    return createImgproxyUrl(sourceUrl, bucket, bucket, "fill")
}
