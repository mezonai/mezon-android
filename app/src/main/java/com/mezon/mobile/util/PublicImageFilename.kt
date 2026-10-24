package com.mezon.mobile.util

internal fun publicImageUploadFilename(
    originalFilename: String?,
    fallbackFilename: String,
    timestampMillis: Long = System.currentTimeMillis(),
): String {
    if (originalFilename.isNullOrBlank()) return fallbackFilename
    val stem = originalFilename.substringBeforeLast('.', originalFilename)
    if (stem.isBlank()) return fallbackFilename
    val extension = fallbackFilename.substringAfterLast('.', "")

    return buildString {
        append(timestampMillis)
        append('_')
        stem.forEach { char ->
            append(if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '.') char else '_')
        }
        if (extension.isNotEmpty()) {
            append('.')
            append(extension)
        }
    }
}
