package com.mezon.mobile.util

import android.content.Context
import com.mezon.mobile.R

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

data class MentionData(
    val userId: String = "",
    val roleId: String = "",
    val startOffset: Int = 0,
    val endOffset: Int = 0
)

fun buildTextContentWithMentions(text: String, mentions: List<MentionData>): String {
    val escaped = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    if (mentions.isEmpty()) return "{\"t\":\"$escaped\"}"

    val mentionsJson = StringBuilder("[")
    mentions.forEachIndexed { i, m ->
        if (i > 0) mentionsJson.append(",")
        mentionsJson.append("{")
        if (m.userId.isNotBlank()) mentionsJson.append("\"user_id\":\"${m.userId}\",")
        if (m.roleId.isNotBlank()) mentionsJson.append("\"role_id\":\"${m.roleId}\",")
        mentionsJson.append("\"s\":${m.startOffset},\"e\":${m.endOffset}}")
    }
    mentionsJson.append("]")
    return "{\"t\":\"$escaped\",\"mentions\":$mentionsJson}"
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

fun convertTimestampToTimeAgo(context: Context, timestampSeconds: Long): String {
    if (timestampSeconds <= 0L) return ""
    val now = System.currentTimeMillis() / 1000
    val diff = (now - timestampSeconds).coerceAtLeast(0)

    if (diff < 60) return context.getString(R.string.common_time_ago_just_now)

    val years = diff / (60 * 60 * 24 * 365)
    if (years > 0) return context.getString(R.string.common_time_ago_years, years.toInt())

    val months = (diff % (60 * 60 * 24 * 365)) / (60 * 60 * 24 * 30)
    if (months > 0) return context.getString(R.string.common_time_ago_months, months.toInt())

    val days = (diff % (60 * 60 * 24 * 30)) / (60 * 60 * 24)
    if (days > 0) return context.getString(R.string.common_time_ago_days, days.toInt())

    val hours = (diff % (60 * 60 * 24)) / (60 * 60)
    if (hours > 0) return context.getString(R.string.common_time_ago_hours, hours.toInt())

    val minutes = (diff % (60 * 60)) / 60
    return context.getString(R.string.common_time_ago_minutes, minutes.toInt())
}
