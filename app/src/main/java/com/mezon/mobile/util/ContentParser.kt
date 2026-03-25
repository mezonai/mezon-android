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
    val msgCal = java.util.Calendar.getInstance().apply {
        timeInMillis = epochSeconds * 1000L
    }
    val nowCal = java.util.Calendar.getInstance()

    val hhmm = "%02d:%02d".format(msgCal.get(java.util.Calendar.HOUR_OF_DAY), msgCal.get(java.util.Calendar.MINUTE))

    val isToday = msgCal.get(java.util.Calendar.YEAR) == nowCal.get(java.util.Calendar.YEAR) &&
        msgCal.get(java.util.Calendar.DAY_OF_YEAR) == nowCal.get(java.util.Calendar.DAY_OF_YEAR)

    if (isToday) return "Today at $hhmm"

    val yesterdayCal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = msgCal.get(java.util.Calendar.YEAR) == yesterdayCal.get(java.util.Calendar.YEAR) &&
        msgCal.get(java.util.Calendar.DAY_OF_YEAR) == yesterdayCal.get(java.util.Calendar.DAY_OF_YEAR)

    if (isYesterday) return "Yesterday at $hhmm"

    val dd = "%02d".format(msgCal.get(java.util.Calendar.DAY_OF_MONTH))
    val mm = "%02d".format(msgCal.get(java.util.Calendar.MONTH) + 1)
    val yyyy = msgCal.get(java.util.Calendar.YEAR)
    return "$dd/$mm/$yyyy, $hhmm"
}
