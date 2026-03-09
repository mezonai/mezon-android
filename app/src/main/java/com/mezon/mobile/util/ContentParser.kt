package com.mezon.mobile.util

private val CONTENT_REGEX = Regex("\"t\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

fun parseContentText(content: String): String {
    if (content.isBlank()) return ""
    return try {
        val match = CONTENT_REGEX.find(content)
        val text = match?.groupValues?.getOrNull(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?.trim()
        if (!text.isNullOrBlank()) return text
        if (content.contains("\"lk\"")) return "[link]"
        if (content.contains("\"embed\"")) return "[embed]"
        ""
    } catch (_: Exception) {
        content
    }
}

fun parseContentPreview(content: String): String {
    if (content.isBlank()) return ""
    return try {
        val match = CONTENT_REGEX.find(content)
        val text = match?.groupValues?.getOrNull(1)
            ?.replace("\\n", " ")
            ?.replace("\\\"", "\"")
            ?.trim()
        if (!text.isNullOrBlank()) return text
        if (content.contains("\"lk\"")) return "[link]"
        if (content.contains("\"attachments\"")) return "[file]"
        if (content.contains("\"embed\"")) return "[embed]"
        ""
    } catch (_: Exception) {
        content.take(100)
    }
}

fun buildTextContent(text: String): String {
    val escaped = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    return "{\"t\":\"$escaped\"}"
}

fun formatRelativeTime(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return ""
    val now = System.currentTimeMillis() / 1000
    val diff = now - epochSeconds
    return when {
        diff < 60 -> "now"
        diff < 3600 -> "${diff / 60}m"
        diff < 86400 -> "${diff / 3600}h"
        else -> {
            val h = epochSeconds / 3600 % 24
            val m = epochSeconds / 60 % 60
            "%02d:%02d".format(h, m)
        }
    }
}
