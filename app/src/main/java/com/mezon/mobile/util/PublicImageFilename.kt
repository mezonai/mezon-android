package com.mezon.mobile.util

import java.text.Normalizer

private val DIACRITICS_REGEX = Regex("\\p{Mn}+")

internal fun publicImageUploadFilename(
    originalFilename: String?,
    fallbackFilename: String,
    mimeType: String,
    timestampMillis: Long = System.currentTimeMillis(),
): String {
    val fallbackExtension = fallbackFilename.substringAfterLast('.', "")
    val extension = when (mimeType.substringBefore(';').trim().lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/avif" -> "avif"
        "image/tiff" -> "tiff"
        "image/bmp", "image/x-ms-bmp" -> "bmp"
        else -> fallbackExtension
    }
    val fallback = if (extension == fallbackExtension) fallbackFilename else {
        "${fallbackFilename.substringBeforeLast('.', fallbackFilename)}.$extension"
    }
    if (originalFilename.isNullOrBlank()) return fallback
    val stem = originalFilename.substringBeforeLast('.', originalFilename)
    val normalizedStem = Normalizer.normalize(stem, Normalizer.Form.NFD)
        .replace(DIACRITICS_REGEX, "")
        .replace('đ', 'd')
        .replace('Đ', 'D')
    if (normalizedStem.isBlank()) return fallback

    return buildString {
        append(timestampMillis)
        append('_')
        normalizedStem.forEach { char ->
            append(if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '.') char else '_')
        }
        if (extension.isNotEmpty()) {
            append('.')
            append(extension)
        }
    }
}
