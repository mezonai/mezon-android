package com.mezon.mobile.util

import android.util.Log
import com.mezon.mobile.BuildConfig

private const val TAG = "ImageProxy"
private const val IMGPROXY_BASE_URL = BuildConfig.MEZON_IMGPROXY_BASE_URL
private const val IMGPROXY_KEY = BuildConfig.MEZON_IMGPROXY_KEY
private const val MAX_BYTES = 2_097_152
private const val MAX_PROXY_DIM = 1200

fun createImgproxyUrl(
    sourceUrl: String,
    widthPx: Int,
    heightPx: Int,
    resizeType: String = "fit"
): String {
    if (sourceUrl.isEmpty()) return sourceUrl
    if (!sourceUrl.startsWith("https://cdn.mezon") && !sourceUrl.startsWith("https://profile.mezon")) {
        return sourceUrl
    }
    val w = widthPx.coerceAtMost(MAX_PROXY_DIM)
    val h = heightPx.coerceAtMost(MAX_PROXY_DIM)
    val options = "rs:$resizeType:$w:$h:1/mb:$MAX_BYTES"
    return "$IMGPROXY_BASE_URL/$IMGPROXY_KEY/$options/plain/$sourceUrl@webp"
}

private val AVATAR_BUCKETS_PX = intArrayOf(64, 96, 144, 192, 256)

fun avatarImgproxyUrl(sourceUrl: String, sizePx: Int): String {
    if (sourceUrl.isEmpty()) return sourceUrl
    val bucket = AVATAR_BUCKETS_PX.firstOrNull { it >= sizePx } ?: AVATAR_BUCKETS_PX.last()
    return createImgproxyUrl(sourceUrl, bucket, bucket, "fill")
}
