package com.mezon.mobile.util

import android.content.Context
import com.mezon.mobile.R
import com.mezon.mobile.util.SHARE_CONTACT_KEY
import org.json.JSONArray
import org.json.JSONObject

private val CONTENT_REGEX = Regex("\"t\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

internal fun parseContentObject(raw: String): JSONObject? {
    if (raw.isEmpty() || raw == "[]" || !raw.startsWith("{")) return null
    return try {
        JSONObject(raw)
    } catch (_: Exception) {
        try {
            JSONObject(raw.replace("\n", "\\n").replace("\r", "\\r"))
        } catch (_: Exception) {
            null
        }
    }
}

private fun textFromContentObject(obj: JSONObject, preview: Boolean): String {
    val t = obj.optString("t", "").trim()
    if (t.isNotBlank()) {
        return if (preview) t.replace("\n", " ").take(200) else t
    }
    val embedPreview = parseEmbedPreview(obj)
    if (embedPreview.isNotBlank()) {
        return if (preview) embedPreview.replace("\n", " ").take(200) else embedPreview
    }
    if (obj.has("lk")) return "[link]"
    if (obj.has("attachments")) return "[file]"
    return ""
}

private fun extractTopLevelTextFromRegex(trimmed: String): String {
    val match = CONTENT_REGEX.find(trimmed) ?: return ""
    return match.groupValues.getOrNull(1).orEmpty().trim()
}

private fun extractContentText(
    trimmed: String,
    preview: Boolean,
    contentObject: JSONObject? = parseContentObject(trimmed)
): String {
    contentObject?.let { return textFromContentObject(it, preview) }
    if (trimmed.startsWith("{")) {
        val fromRegex = extractTopLevelTextFromRegex(trimmed)
        if (fromRegex.isNotBlank()) {
            return if (preview) fromRegex.replace("\n", " ").take(200) else fromRegex
        }
        return ""
    }
    val match = CONTENT_REGEX.find(trimmed)
    val fromRegex = match?.groupValues?.getOrNull(1)?.trim()
    if (!fromRegex.isNullOrBlank()) {
        return if (preview) fromRegex.replace("\n", " ").take(200) else fromRegex
    }
    if (isStructuralJsonPayload(trimmed)) return ""
    return if (preview) trimmed.replace("\n", " ").take(200) else trimmed
}

private fun isStructuralJsonPayload(trimmed: String): Boolean {
    if (!trimmed.startsWith("[")) return false
    return try {
        JSONArray(trimmed).length() >= 0
    } catch (_: Exception) {
        false
    }
}

private fun parseEmbedPreview(obj: JSONObject): String {
    val embedArr = obj.optJSONArray("embed") ?: return ""
    if (embedArr.length() == 0) return ""
    val embed = embedArr.optJSONObject(0) ?: return ""
    val title = embed.optString("title", "").trim()
    if (title.isNotBlank()) return title
    val description = embed.optString("description", "").trim()
    if (description.isNotBlank()) return description
    val fields = embed.optJSONArray("fields") ?: return ""
    if (fields.length() == 0) return ""
    val firstVal = fields.optJSONObject(0)?.optString("value", "")?.trim().orEmpty()
    if (firstVal == SHARE_CONTACT_KEY) return "[Contact]"
    return ""
}

private val THREAD_INFO_REGEX = Regex("\\(([^,]+),\\s*([^)]+)\\)")

data class ParsedThreadInfo(val label: String, val channelId: Long)

fun parseThreadInfoFromPlainText(text: String): ParsedThreadInfo? {
    val match = THREAD_INFO_REGEX.find(text) ?: return null
    val label = match.groupValues[1].trim()
    val id = match.groupValues[2].trim().toLongOrNull() ?: return null
    if (label.isEmpty()) return null
    return ParsedThreadInfo(label, id)
}

fun isEmbedOrComponentsPayload(content: String): Boolean =
    content.contains("\"embed\"") || content.contains("\"components\"")

private val REFERENCE_REF_ID_REGEX = Regex("\"message_ref_id\"\\s*:\\s*\"?(\\d+)\"?")

fun firstReferenceMessageId(content: String): Long {
    if (!content.contains("\"references\"")) return 0L
    return REFERENCE_REF_ID_REGEX.find(content)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
}

fun messageHasExplicitTextBody(content: String): Boolean {
    if (content.isBlank()) return false
    val trimmed = content.trim()
    if (!trimmed.startsWith("{")) return true
    parseContentObject(trimmed)?.let { return it.optString("t").isNotBlank() }
    return extractTopLevelTextFromRegex(trimmed).isNotBlank()
}

fun parseContentText(content: String): String {
    if (content.isBlank()) return ""
    return extractContentText(content.trim(), preview = false)
}

fun parseContentPreview(content: String): String {
    if (content.isBlank()) return ""
    return extractContentText(content.trim(), preview = true)
}

internal fun parseContentPreview(content: String, contentObject: JSONObject?): String {
    if (content.isBlank()) return ""
    return extractContentText(content.trim(), preview = true, contentObject)
}

object TopicOriginalPreviewToken {
    const val ATTACHMENT = "__topic_original_attachment__"
    const val CONTACT = "__topic_original_contact__"
    const val INTERACTIVE_MESSAGE = "__topic_original_interactive_message__"
}

fun parseTopicOriginalMessagePreview(content: String): String {
    val trimmed = content.trim()
    if (trimmed.isBlank()) return TopicOriginalPreviewToken.ATTACHMENT

    val obj = parseContentObject(trimmed)
    if (obj == null) {
        if (isStructuralJsonPayload(trimmed)) return TopicOriginalPreviewToken.ATTACHMENT
        return trimmed.replace("\n", " ").take(200)
    }

    if (isShareContactPayload(obj)) return TopicOriginalPreviewToken.CONTACT
    if (hasAttachmentsPayload(obj)) return TopicOriginalPreviewToken.ATTACHMENT

    val text = obj.optString("t", obj.optString("text", "")).trim()
    val link = extractLinkValue(obj)
    if (hasInteractivePayload(obj, includeRichEmbedOnly = text.isBlank() && link.isBlank())) {
        return TopicOriginalPreviewToken.INTERACTIVE_MESSAGE
    }
    if (text.isNotBlank()) return text.replace("\n", " ").take(200)
    if (link.isNotBlank()) return link.replace("\n", " ").take(200)

    val embedPreview = firstTopicEmbedPreview(obj)
    if (embedPreview.isNotBlank()) {
        return embedPreview.replace("\n", " ").take(200)
    }

    return TopicOriginalPreviewToken.ATTACHMENT
}

private fun hasAttachmentsPayload(obj: JSONObject): Boolean =
    obj.optBoolean("has_attachment", false) ||
        obj.optBoolean("hasAttachment", false) ||
        obj.optBoolean("attachment", false) ||
        hasNonEmptyPayloadValue(obj.opt("attachments")) ||
        hasNonEmptyPayloadValue(obj.opt("a")) ||
        hasNonEmptyPayloadValue(obj.opt("attachment")) ||
        hasNonEmptyPayloadValue(obj.opt("files")) ||
        hasNonEmptyPayloadValue(obj.opt("file"))

private fun hasNonEmptyPayloadValue(value: Any?): Boolean =
    when (value) {
        null, JSONObject.NULL -> false
        is JSONArray -> value.length() > 0
        is JSONObject -> value.length() > 0
        is Boolean -> value
        is String -> value.trim().isNotEmpty()
        else -> true
    }

private fun isShareContactPayload(obj: JSONObject): Boolean {
    for (embed in embedObjects(obj)) {
        val fields = embed.optJSONArray("fields") ?: continue
        for (j in 0 until fields.length()) {
            val field = fields.optJSONObject(j) ?: continue
            val name = field.optString("name", "").trim().lowercase()
            val value = field.optString("value", "").trim().lowercase()
            if ((name == "key" && (value == SHARE_CONTACT_KEY || value == "share_contact_key")) ||
                value == SHARE_CONTACT_KEY ||
                value == "share_contact_key"
            ) return true
        }
    }
    return false
}

private fun hasInteractivePayload(obj: JSONObject, includeRichEmbedOnly: Boolean): Boolean {
    if ((obj.optJSONArray("components")?.length() ?: 0) > 0) return true
    for (embed in embedObjects(obj)) {
        val fields = embed.optJSONArray("fields") ?: continue
        if (fields.length() > 0) return true
    }
    if (!includeRichEmbedOnly) return false
    return embedObjects(obj).any { hasRichIntegrationEmbedPayload(it) }
}

private fun hasRichIntegrationEmbedPayload(embed: JSONObject): Boolean {
    return hasNonEmptyPayloadValue(embed.opt("author")) ||
        hasNonEmptyPayloadValue(embed.opt("footer")) ||
        hasNonEmptyPayloadValue(embed.opt("image")) ||
        hasNonEmptyPayloadValue(embed.opt("thumbnail")) ||
        hasNonEmptyPayloadValue(embed.opt("video"))
}

private fun firstTopicEmbedPreview(obj: JSONObject): String {
    for (embed in embedObjects(obj)) {
        val title = embed.optString("title", "").trim()
        if (title.isNotBlank()) return title
        val description = embed.optString("description", "").trim()
        if (description.isNotBlank()) return description
        val url = embed.optString("url", "").trim()
        if (url.isNotBlank()) return url
    }
    return ""
}

private fun embedObjects(obj: JSONObject): List<JSONObject> =
    jsonObjectsFromValue(obj.opt("embed")).ifEmpty { jsonObjectsFromValue(obj.opt("embeds")) }

private fun jsonObjectsFromValue(value: Any?): List<JSONObject> =
    when (value) {
        is JSONObject -> listOf(value)
        is JSONArray -> {
            val result = mutableListOf<JSONObject>()
            for (i in 0 until value.length()) {
                value.optJSONObject(i)?.let(result::add)
            }
            result
        }
        else -> emptyList()
    }

private fun extractLinkValue(obj: JSONObject): String {
    val lk = obj.opt("lk") ?: return ""
    return when (lk) {
        is String -> lk.trim()
        is JSONObject -> lk.optString("url", lk.optString("href", "")).trim()
        is JSONArray -> {
            for (i in 0 until lk.length()) {
                val item = lk.opt(i)
                val value = when (item) {
                    is String -> item.trim()
                    is JSONObject -> item.optString("url", item.optString("href", "")).trim()
                    else -> ""
                }
                if (value.isNotBlank()) return value
            }
            ""
        }
        else -> ""
    }
}

const val MENTION_HERE_USER_ID = "1775731111020111321"

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
    val display: String = "",
    val startOffset: Int = 0,
    val endOffset: Int = 0
)

fun mergePendingMentionsIntoContent(baseContent: String, mentions: List<MentionData>?): String {
    if (mentions.isNullOrEmpty()) return baseContent
    if (baseContent.contains("\"mentions\"")) return baseContent
    return try {
        val arr = JSONArray()
        for (m in mentions) {
            val item = JSONObject()
            item.put("s", m.startOffset)
            item.put("e", m.endOffset)
            if (m.userId.isNotBlank()) item.put("user_id", m.userId)
            if (m.roleId.isNotBlank()) item.put("role_id", m.roleId)
            if (m.display.isNotBlank()) item.put("username", m.display)
            arr.put(item)
        }
        val lastBrace = baseContent.lastIndexOf('}')
        if (lastBrace < 0) return baseContent
        baseContent.substring(0, lastBrace) + ",\"mentions\":" + arr.toString() + "}"
    } catch (_: Exception) {
        baseContent
    }
}

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

private val MSG_CAL_TL = object : ThreadLocal<java.util.Calendar>() {
    override fun initialValue(): java.util.Calendar = java.util.Calendar.getInstance()
}
private val NOW_CAL_TL = object : ThreadLocal<java.util.Calendar>() {
    override fun initialValue(): java.util.Calendar = java.util.Calendar.getInstance()
}

private fun StringBuilder.append2Digits(v: Int) {
    if (v < 10) append('0')
    append(v)
}

fun formatRelativeTime(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return ""
    val msgCal = MSG_CAL_TL.get()!!.apply { timeInMillis = epochSeconds * 1000L }
    val nowCal = NOW_CAL_TL.get()!!.apply { timeInMillis = System.currentTimeMillis() }

    val msgYear = msgCal.get(java.util.Calendar.YEAR)
    val msgDayOfYear = msgCal.get(java.util.Calendar.DAY_OF_YEAR)
    val nowYear = nowCal.get(java.util.Calendar.YEAR)
    val nowDayOfYear = nowCal.get(java.util.Calendar.DAY_OF_YEAR)
    val hour = msgCal.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = msgCal.get(java.util.Calendar.MINUTE)

    val isToday = msgYear == nowYear && msgDayOfYear == nowDayOfYear
    if (isToday) {
        val sb = StringBuilder(15).append("Today at ")
        sb.append2Digits(hour); sb.append(':'); sb.append2Digits(minute)
        return sb.toString()
    }

    val isYesterday = msgYear == nowYear && msgDayOfYear == nowDayOfYear - 1 ||
        (nowDayOfYear == 1 && msgYear == nowYear - 1 &&
            msgDayOfYear == msgCal.getActualMaximum(java.util.Calendar.DAY_OF_YEAR))
    if (isYesterday) {
        val sb = StringBuilder(19).append("Yesterday at ")
        sb.append2Digits(hour); sb.append(':'); sb.append2Digits(minute)
        return sb.toString()
    }

    val dd = msgCal.get(java.util.Calendar.DAY_OF_MONTH)
    val mm = msgCal.get(java.util.Calendar.MONTH) + 1
    val sb = StringBuilder(18)
    sb.append2Digits(dd); sb.append('/')
    sb.append2Digits(mm); sb.append('/')
    sb.append(msgYear); sb.append(", ")
    sb.append2Digits(hour); sb.append(':'); sb.append2Digits(minute)
    return sb.toString()
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

data class EmojiMarker(val emojiId: String, val startIndex: Int, val endIndex: Int)

data class MarkdownMarker(val type: String, val s: Int, val e: Int)

data class OgpMarker(
    val s: Int,
    val e: Int,
    val index: Int,
    val title: String,
    val description: String,
    val image: String,
    val url: String
)

data class HashtagData(
    val channelId: String,
    val startOffset: Int,
    val endOffset: Int,
    val clanId: String = ""
)

data class RestoredInputContent(
    val rawText: String,
    val mentions: List<MentionData>,
    val hashtags: List<HashtagData>,
    val emojis: Map<String, String>
)

fun restoreInputFromContent(content: String): RestoredInputContent {
    var cleanText = parseContentText(content)
    if (content.isBlank() || cleanText.isBlank()) {
        return RestoredInputContent(cleanText, emptyList(), emptyList(), emptyMap())
    }
    val obj = try { JSONObject(content) } catch (_: Exception) {
        return RestoredInputContent(cleanText, emptyList(), emptyList(), emptyMap())
    }

    if (cleanText == "[Contact]" && obj.optString("t", "").trim().isEmpty()) {
        cleanText = ""
    }

    val protectedRanges = listOf("mentions", "hg", "ej").flatMap { key ->
        contentRanges(obj.optJSONArray(key), cleanText.length)
    }
    val source = buildMarkdownEditSource(cleanText, obj.optJSONArray("mk"), protectedRanges)

    fun rangeToRaw(s: Int, e: Int): Pair<Int, Int> = source.rawStart(s) to source.rawEnd(e)

    val mentions = mutableListOf<MentionData>()
    obj.optJSONArray("mentions")?.let { arr ->
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val s = j.optInt("s", -1)
            val e = j.optInt("e", -1)
            if (s < 0 || e <= s || e > cleanText.length) continue
            val userId = j.optString("user_id", "")
            val roleId = j.optString("role_id", "")
            val display = cleanText.substring(s, e)
            val (rs, re) = rangeToRaw(s, e)
            mentions.add(
                MentionData(
                    userId = userId,
                    roleId = roleId,
                    display = display,
                    startOffset = rs,
                    endOffset = re
                )
            )
        }
    }

    val hashtags = mutableListOf<HashtagData>()
    obj.optJSONArray("hg")?.let { arr ->
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val s = j.optInt("s", -1)
            val e = j.optInt("e", -1)
            if (s < 0 || e <= s || e > cleanText.length) continue
            val channelId = j.optString("channelId", "")
            val clanId = j.optString("clanId", "")
            val (rs, re) = rangeToRaw(s, e)
            hashtags.add(
                HashtagData(
                    channelId = channelId,
                    startOffset = rs,
                    endOffset = re,
                    clanId = clanId
                )
            )
        }
    }

    val emojis = HashMap<String, String>()
    obj.optJSONArray("ej")?.let { arr ->
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val s = j.optInt("s", -1)
            val e = j.optInt("e", -1)
            if (s < 0 || e <= s || e > cleanText.length) continue
            val emojiId = j.optString("emojiid", "")
            if (emojiId.isEmpty()) continue
            val shortname = cleanText.substring(s, e)
            emojis[shortname] = emojiId
        }
    }

    return RestoredInputContent(source.text, mentions, hashtags, emojis)
}

private fun contentRanges(arr: JSONArray?, textLength: Int): List<IntRange> {
    if (arr == null) return emptyList()
    val out = ArrayList<IntRange>(arr.length())
    for (i in 0 until arr.length()) {
        val j = arr.optJSONObject(i) ?: continue
        val s = j.optInt("s", -1)
        val e = j.optInt("e", -1)
        if (s < 0 || e <= s || e > textLength) continue
        out.add(s until e)
    }
    return out
}

private class MarkdownEditSource(val text: String, private val insertions: List<Pair<Int, Int>>) {
    fun rawStart(cleanOffset: Int): Int =
        cleanOffset + insertions.sumOf { (at, length) -> if (at <= cleanOffset) length else 0 }

    fun rawEnd(cleanOffset: Int): Int =
        cleanOffset + insertions.sumOf { (at, length) -> if (at < cleanOffset) length else 0 }
}

private data class MarkdownFormatKey(val type: String, val start: Int, val end: Int)

private class MarkdownEditMarker(val start: Int, val end: Int, val marker: String, val rank: Int)

private class StyledEditRange(val start: Int, val end: Int) {
    var bold = false
    var italic = false
    var strike = false

    fun keys(): List<MarkdownFormatKey> = buildList {
        if (bold) add(MarkdownFormatKey(MARKDOWN_BOLD, start, end))
        if (italic) add(MarkdownFormatKey(MARKDOWN_ITALIC, start, end))
        if (strike) add(MarkdownFormatKey(MARKDOWN_STRIKE, start, end))
    }

    fun keeping(allowed: Set<MarkdownFormatKey>): StyledEditRange? {
        val kept = StyledEditRange(start, end)
        kept.bold = bold && MarkdownFormatKey(MARKDOWN_BOLD, start, end) in allowed
        kept.italic = italic && MarkdownFormatKey(MARKDOWN_ITALIC, start, end) in allowed
        kept.strike = strike && MarkdownFormatKey(MARKDOWN_STRIKE, start, end) in allowed
        return if (kept.bold || kept.italic || kept.strike) kept else null
    }

    fun markers(italicMarker: String): List<MarkdownEditMarker> = buildList {
        if (strike) add(MarkdownEditMarker(start, end, "~~", 5))
        when {
            bold && italic -> add(MarkdownEditMarker(start, end, "***", 4))
            bold -> add(MarkdownEditMarker(start, end, "**", 3))
            italic -> add(MarkdownEditMarker(start, end, italicMarker, 2))
        }
    }
}

private val EDIT_FORMAT_TYPES = setOf(MARKDOWN_BOLD, MARKDOWN_ITALIC, MARKDOWN_STRIKE, MARKDOWN_CODE, MARKDOWN_PRE)

private fun MutableList<StyledEditRange>.rangeAt(start: Int, end: Int): StyledEditRange =
    firstOrNull { it.start == start && it.end == end } ?: StyledEditRange(start, end).also { add(it) }

private fun buildMarkdownEditSource(
    text: String,
    mk: JSONArray?,
    protectedRanges: List<IntRange>
): MarkdownEditSource {
    val codeMarkers = mutableListOf<MarkdownEditMarker>()
    val codeKeys = mutableSetOf<MarkdownFormatKey>()
    val styled = mutableListOf<StyledEditRange>()
    if (mk != null) {
        for (i in 0 until mk.length()) {
            val j = mk.optJSONObject(i) ?: continue
            val s = j.optInt("s", -1)
            val e = j.optInt("e", -1)
            if (s < 0 || e <= s || e > text.length) continue
            when (j.optString("type", "")) {
                MARKDOWN_CODE, "s" -> {
                    codeMarkers.add(MarkdownEditMarker(s, e, "`", 0))
                    codeKeys.add(MarkdownFormatKey(MARKDOWN_CODE, s, e))
                }
                MARKDOWN_PRE, "t" -> {
                    codeMarkers.add(MarkdownEditMarker(s, e, "```", 1))
                    codeKeys.add(MarkdownFormatKey(MARKDOWN_PRE, s, e))
                }
                MARKDOWN_BOLD -> styled.rangeAt(s, e).bold = true
                MARKDOWN_ITALIC -> styled.rangeAt(s, e).italic = true
                MARKDOWN_STRIKE -> styled.rangeAt(s, e).strike = true
            }
        }
    }

    val codeOnly = assembleMarkdownEditSource(text, codeMarkers)
    var candidates: List<StyledEditRange> = styled
    repeat(2) {
        if (candidates.isEmpty()) return codeOnly
        val candidateKeys = candidates.flatMap { it.keys() }
        val expected = codeKeys + candidateKeys
        val reproduced = mutableSetOf<MarkdownFormatKey>()
        for (italicMarker in listOf("*", "_")) {
            val source = assembleMarkdownEditSource(
                text,
                codeMarkers + candidates.flatMap { it.markers(italicMarker) }
            )
            val parsed = parseMarkdownAndStrip(
                source.text,
                protectedRanges.map { source.rawStart(it.first) until source.rawEnd(it.last + 1) }
            )
            if (parsed.cleanedText != text) continue
            val formats = parsed.markers
                .filter { it.type in EDIT_FORMAT_TYPES }
                .mapTo(mutableSetOf<MarkdownFormatKey>()) { MarkdownFormatKey(it.type, it.s, it.e) }
            if (formats == expected) return source
            reproduced += formats
        }
        val reduced = candidates.mapNotNull { it.keeping(reproduced) }
        if (reduced.flatMap { it.keys() } == candidateKeys) return codeOnly
        candidates = reduced
    }
    return codeOnly
}

private fun assembleMarkdownEditSource(text: String, markers: List<MarkdownEditMarker>): MarkdownEditSource {
    val sorted = markers.sortedWith(compareBy({ it.start }, { -it.end }, { -it.rank }))
    val points = sortedSetOf(0, text.length).apply {
        for (m in sorted) {
            add(m.start)
            add(m.end)
        }
    }.toList()
    val out = StringBuilder(text.length + sorted.size * 6)
    val insertions = mutableListOf<Pair<Int, Int>>()
    for ((index, cursor) in points.withIndex()) {
        for (m in sorted.asReversed()) {
            if (m.end != cursor) continue
            out.append(m.marker)
            insertions.add(cursor to m.marker.length)
        }
        if (index + 1 >= points.size) break
        for (m in sorted) {
            if (m.start != cursor) continue
            out.append(m.marker)
            insertions.add(cursor to m.marker.length)
        }
        out.append(text, cursor, points[index + 1])
    }
    return MarkdownEditSource(out.toString(), insertions)
}

fun parseMentionsFromContent(content: String): List<MentionData> {
    if (content.isBlank()) return emptyList()
    val cleanText = parseContentText(content)
    if (cleanText.isBlank()) return emptyList()
    val obj = parseContentObject(content.trim()) ?: return emptyList()
    val mentions = mutableListOf<MentionData>()
    obj.optJSONArray("mentions")?.let { arr ->
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val s = j.optInt("s", -1)
            val e = j.optInt("e", -1)
            if (s < 0 || e <= s || e > cleanText.length) continue
            val userId = j.optString("user_id", "")
            val roleId = j.optString("role_id", "")
            val display = cleanText.substring(s, e)
            mentions.add(
                MentionData(
                    userId = userId,
                    roleId = roleId,
                    display = display,
                    startOffset = s,
                    endOffset = e,
                )
            )
        }
    }
    return mentions
}

fun remapMentionsForEdit(oldContent: String, newText: String, existing: List<MentionData>): List<MentionData> {
    if (existing.isEmpty()) return emptyList()
    val oldText = parseContentText(oldContent)
    if (newText == oldText) return existing
    return existing.mapNotNull { m ->
        val needle = m.display
        if (needle.isEmpty()) return@mapNotNull null
        val searchFrom = (m.startOffset - needle.length).coerceAtLeast(0)
        val idx = newText.indexOf(needle, searchFrom)
        if (idx < 0) return@mapNotNull null
        m.copy(startOffset = idx, endOffset = idx + needle.length)
    }
}

class MarkdownParseResult(
    val cleanedText: String,
    val markers: List<MarkdownMarker>,
    private val removals: List<IntRange>
) {
    fun adjustOffset(originalOffset: Int): Int {
        var shift = 0
        for (range in removals) {
            if (range.first >= originalOffset) break
            shift += minOf(range.last + 1, originalOffset) - range.first
        }
        return originalOffset - shift
    }
}

fun parseMarkdownAndStrip(
    rawText: String,
    protectedRanges: List<IntRange> = emptyList(),
    protectedTokens: Collection<String> = emptyList()
): MarkdownParseResult {
    if (rawText.isBlank()) return MarkdownParseResult(rawText, emptyList(), emptyList())
    val n = rawText.length
    val spans = scanMarkdownStructure(rawText)

    val protectedEnd = IntArray(n)
    fun protect(start: Int, end: Int) {
        val lo = start.coerceAtLeast(0)
        val hi = end.coerceAtMost(n)
        for (k in lo until hi) {
            if (protectedEnd[k] < hi) protectedEnd[k] = hi
        }
    }
    for (span in spans) protect(span.start, span.end)
    for (range in protectedRanges) protect(range.first, range.last + 1)
    for (token in protectedTokens) {
        if (token.isEmpty()) continue
        var from = 0
        while (true) {
            val idx = rawText.indexOf(token, from)
            if (idx < 0) break
            protect(idx, idx + token.length)
            from = idx + token.length
        }
    }

    var lineStart = 0
    while (lineStart < n) {
        val newline = rawText.indexOf('\n', lineStart)
        val lineEnd = if (newline < 0) n else newline
        if (lineEnd > lineStart && lineEnd - lineStart <= MAX_STYLED_LINE_LENGTH) {
            scanMarkdownStyles(rawText, lineStart, lineEnd, protectedEnd, 0, spans)
        }
        lineStart = lineEnd + 1
    }

    val removed = BooleanArray(n)
    for (span in spans) {
        if (span.marker == 0) continue
        for (k in span.start until span.start + span.marker) removed[k] = true
        for (k in span.end - span.marker until span.end) removed[k] = true
    }

    val cleaned = StringBuilder(n)
    val cleanedOffset = IntArray(n + 1)
    val removals = mutableListOf<IntRange>()
    var removedRunStart = -1
    for (k in 0 until n) {
        cleanedOffset[k] = cleaned.length
        if (removed[k]) {
            if (removedRunStart < 0) removedRunStart = k
            continue
        }
        if (removedRunStart >= 0) {
            removals.add(removedRunStart until k)
            removedRunStart = -1
        }
        cleaned.append(rawText[k])
    }
    if (removedRunStart >= 0) removals.add(removedRunStart until n)
    cleanedOffset[n] = cleaned.length

    val markers = spans
        .map { MarkdownMarker(it.type, cleanedOffset[it.start], cleanedOffset[it.end]) }
        .sortedBy { it.s }
    return MarkdownParseResult(cleaned.toString(), markers, removals)
}

private class MarkdownSpan(val type: String, val start: Int, val end: Int, val marker: Int)

private const val MAX_STYLED_LINE_LENGTH = 10_000
private const val MAX_STYLE_DEPTH = 16

private fun isTripleBacktick(text: String, k: Int): Boolean =
    k >= 0 && k + 2 < text.length && text[k] == '`' && text[k + 1] == '`' && text[k + 2] == '`'

private fun scanMarkdownStructure(text: String): MutableList<MarkdownSpan> {
    val out = mutableListOf<MarkdownSpan>()
    var i = 0
    while (i < text.length) {
        val hit = codeBlockHit(text, i) ?: linkHit(text, i) ?: inlineCodeHit(text, i)
        if (hit != null) {
            out.add(hit.first)
            i = hit.second
            continue
        }
        i++
    }
    return out
}

private fun codeBlockHit(text: String, i: Int): Pair<MarkdownSpan, Int>? {
    if (!isTripleBacktick(text, i)) return null
    var j = i + 3
    while (j + 2 < text.length && !isTripleBacktick(text, j)) j++
    if (!isTripleBacktick(text, j) || j == i + 3) return null
    return MarkdownSpan(MARKDOWN_PRE, i, j + 3, 3) to j + 3
}

private fun linkHit(text: String, i: Int): Pair<MarkdownSpan, Int>? {
    val schemeLen = when {
        text.startsWith("https://", i) -> 8
        text.startsWith("http://", i) -> 7
        else -> return null
    }
    var j = i + schemeLen
    while (j < text.length && text[j] != ' ' && text[j] != '\n' && text[j] != '\r' && text[j] != '\t') j++
    if (i >= 2 && text[i - 2] == ']' && text[i - 1] == '(') {
        val close = text.indexOf(')', i + schemeLen)
        if (close in (i + schemeLen) until j) j = close
    }
    var linkEnd = j
    while (linkEnd > i + schemeLen && text[linkEnd - 1] in TRAILING_PUNCTUATION) linkEnd--
    if (linkEnd <= i + schemeLen) return null
    return MarkdownSpan(MARKDOWN_LINK, i, linkEnd, 0) to linkEnd
}

private fun inlineCodeHit(text: String, i: Int): Pair<MarkdownSpan, Int>? {
    if (text[i] != '`' || isTripleBacktick(text, i)) return null
    var j = i + 1
    while (j < text.length && text[j] != '`') j++
    if (j >= text.length) return null
    if (isTripleBacktick(text, j)) {
        var k = j + 3
        while (k + 2 < text.length) {
            if (isTripleBacktick(text, k)) return null
            k++
        }
    }
    if (text.substring(i + 1, j).isBlank()) return null
    return MarkdownSpan(MARKDOWN_CODE, i, j + 1, 1) to j + 1
}

private fun isWordCodePoint(codePoint: Int): Boolean {
    if (Character.isLetterOrDigit(codePoint)) return true
    return when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }
}

private fun isWordCharBefore(text: String, pos: Int): Boolean =
    pos > 0 && pos <= text.length && isWordCodePoint(Character.codePointBefore(text, pos))

private fun isWordCharAt(text: String, pos: Int): Boolean =
    pos >= 0 && pos < text.length && isWordCodePoint(Character.codePointAt(text, pos))

private fun styleRunEnd(text: String, pos: Int, end: Int, protectedEnd: IntArray): Int {
    val ch = text[pos]
    var k = pos
    while (k < end && text[k] == ch && protectedEnd[k] == 0) k++
    return k
}

private fun isValidStyleMarker(ch: Char, length: Int): Boolean = when (ch) {
    '*' -> length in 1..3
    '_' -> length == 1
    '~' -> length == 2
    else -> false
}

private fun needsWordBoundary(ch: Char, length: Int): Boolean =
    length == 1 && (ch == '*' || ch == '_')

private fun canOpenStyle(text: String, pos: Int, runEnd: Int, end: Int): Boolean {
    val ch = text[pos]
    val length = runEnd - pos
    if (!isValidStyleMarker(ch, length)) return false
    if (runEnd >= end || text[runEnd].isWhitespace()) return false
    return !(needsWordBoundary(ch, length) && isWordCharBefore(text, pos))
}

private fun canCloseStyle(text: String, openEnd: Int, close: Int, closeEnd: Int): Boolean {
    if (close <= openEnd || text[close - 1].isWhitespace()) return false
    if ((openEnd until close).all { text[it].isWhitespace() }) return false
    return !(needsWordBoundary(text[close], closeEnd - close) && isWordCharAt(text, closeEnd))
}

private fun mayCloseStyle(text: String, pos: Int, runEnd: Int): Boolean {
    if (pos <= 0 || text[pos - 1].isWhitespace()) return false
    return !(needsWordBoundary(text[pos], runEnd - pos) && isWordCharAt(text, runEnd))
}

private fun findStyleClose(
    text: String,
    open: Int,
    length: Int,
    end: Int,
    protectedEnd: IntArray,
    depth: Int
): Int? {
    if (depth >= MAX_STYLE_DEPTH) return null
    val ch = text[open]
    val openEnd = open + length
    var j = openEnd
    while (j < end) {
        if (protectedEnd[j] != 0) {
            j = minOf(protectedEnd[j], end)
            continue
        }
        if (text[j] != ch) {
            j++
            continue
        }
        val runEnd = styleRunEnd(text, j, end, protectedEnd)
        val runLength = runEnd - j
        if (runLength == length) {
            if (canCloseStyle(text, openEnd, j, runEnd)) return j
            if (canOpenStyle(text, j, runEnd, end)) return null
            j = runEnd
        } else if (ch == '*') {
            if (canOpenStyle(text, j, runEnd, end)) {
                val nestedClose = findStyleClose(text, j, runLength, end, protectedEnd, depth + 1) ?: return null
                j = nestedClose + runLength
                continue
            }
            if (mayCloseStyle(text, j, runEnd)) return null
            j = runEnd
        } else {
            j = runEnd
        }
    }
    return null
}

private fun scanMarkdownStyles(
    text: String,
    start: Int,
    end: Int,
    protectedEnd: IntArray,
    depth: Int,
    out: MutableList<MarkdownSpan>
) {
    if (depth >= MAX_STYLE_DEPTH) return
    var i = start
    while (i < end) {
        if (protectedEnd[i] != 0) {
            i = minOf(protectedEnd[i], end)
            continue
        }
        val ch = text[i]
        if (ch != '*' && ch != '_' && ch != '~') {
            i++
            continue
        }
        val runEnd = styleRunEnd(text, i, end, protectedEnd)
        val length = runEnd - i
        val close = if (canOpenStyle(text, i, runEnd, end)) {
            findStyleClose(text, i, length, end, protectedEnd, depth)
        } else {
            null
        }
        if (close == null) {
            i = runEnd
            continue
        }
        val matchEnd = close + length
        when {
            ch == '*' && length == 3 -> {
                out.add(MarkdownSpan(MARKDOWN_BOLD, i, matchEnd, 3))
                out.add(MarkdownSpan(MARKDOWN_ITALIC, i, matchEnd, 3))
            }
            ch == '*' && length == 2 -> out.add(MarkdownSpan(MARKDOWN_BOLD, i, matchEnd, 2))
            ch == '~' -> out.add(MarkdownSpan(MARKDOWN_STRIKE, i, matchEnd, 2))
            else -> out.add(MarkdownSpan(MARKDOWN_ITALIC, i, matchEnd, 1))
        }
        scanMarkdownStyles(text, runEnd, close, protectedEnd, depth + 1, out)
        i = matchEnd
    }
}

private val TRAILING_PUNCTUATION = charArrayOf(',', '.', '!', '?', ';', ':', '*', '_', '~')

const val MARKDOWN_PRE = "pre"
const val MARKDOWN_CODE = "c"
const val MARKDOWN_BOLD = "b"
const val MARKDOWN_ITALIC = "i"
const val MARKDOWN_STRIKE = "st"
const val MARKDOWN_LINK = "lk"

fun buildTextContentWithEmojis(
    text: String,
    mentions: List<MentionData>?,
    emojis: List<EmojiMarker>?,
    markdowns: List<MarkdownMarker>? = null,
    hashtags: List<HashtagData>? = null,
    ogp: OgpMarker? = null
): String {
    val escaped = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    val parts = mutableListOf("\"t\":\"$escaped\"")
    if (!mentions.isNullOrEmpty()) {
        val mentionsJson = StringBuilder("[")
        mentions.forEachIndexed { i, m ->
            if (i > 0) mentionsJson.append(",")
            mentionsJson.append("{")
            if (m.userId.isNotBlank()) mentionsJson.append("\"user_id\":\"${m.userId}\",")
            if (m.roleId.isNotBlank()) mentionsJson.append("\"role_id\":\"${m.roleId}\",")
            if (m.display.isNotBlank()) mentionsJson.append("\"display\":\"${m.display}\",")
            mentionsJson.append("\"s\":${m.startOffset},\"e\":${m.endOffset}}")
        }
        mentionsJson.append("]")
        parts.add("\"mentions\":$mentionsJson")
    }
    if (!emojis.isNullOrEmpty()) {
        val ejJson = emojis.joinToString(",") {
            "{\"emojiid\":\"${it.emojiId}\",\"s\":${it.startIndex},\"e\":${it.endIndex}}"
        }
        parts.add("\"ej\":[$ejJson]")
    }
    if (!markdowns.isNullOrEmpty() || ogp != null) {
        val mkEntries = ArrayList<String>((markdowns?.size ?: 0) + 1)
        markdowns?.forEach {
            mkEntries.add("{\"type\":\"${it.type}\",\"s\":${it.s},\"e\":${it.e}}")
        }
        ogp?.let {
            val escapedTitle = it.title
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
            val escapedDescription = it.description
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
            val escapedImage = it.image
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
            val escapedUrl = it.url
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
            mkEntries.add(
                "{\"type\":\"lk_ogp\",\"s\":${it.s},\"e\":${it.e},\"index\":${it.index}," +
                    "\"title\":\"$escapedTitle\",\"description\":\"$escapedDescription\",\"image\":\"$escapedImage\",\"url\":\"$escapedUrl\"}"
            )
        }
        parts.add("\"mk\":[${mkEntries.joinToString(",")}]")
    }
    if (!hashtags.isNullOrEmpty()) {
        val hgJson = hashtags.joinToString(",") {
            val sb = StringBuilder("{\"s\":${it.startOffset},\"e\":${it.endOffset},\"channelId\":\"${it.channelId}\"")
            if (it.clanId.isNotBlank() && it.clanId != "0") sb.append(",\"clanId\":\"${it.clanId}\"")
            sb.append("}")
            sb.toString()
        }
        parts.add("\"hg\":[$hgJson]")
    }
    return "{${parts.joinToString(",")}}"
}
