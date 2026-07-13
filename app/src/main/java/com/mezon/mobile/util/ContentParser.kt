package com.mezon.mobile.util

import android.content.Context
import com.mezon.mobile.R
import com.mezon.mobile.util.SHARE_CONTACT_KEY
import org.json.JSONArray
import org.json.JSONObject

private val CONTENT_REGEX = Regex("\"t\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

private fun parseContentObject(raw: String): JSONObject? {
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

private fun extractContentText(trimmed: String, preview: Boolean): String {
    parseContentObject(trimmed)?.let { return textFromContentObject(it, preview) }
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

    return if (obj.has("embed") || obj.has("embeds")) TopicOriginalPreviewToken.ATTACHMENT else ""
}

private fun hasAttachmentsPayload(obj: JSONObject): Boolean =
    obj.optBoolean("has_attachment", false) ||
        obj.optBoolean("hasAttachment", false) ||
        obj.optBoolean("attachment", false) ||
        (obj.optJSONArray("attachments")?.length() ?: 0) > 0 ||
        (obj.optJSONArray("a")?.length() ?: 0) > 0 ||
        hasNonEmptyPayloadValue(obj.opt("attachments")) ||
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

    val inserts = HashMap<Int, StringBuilder>()
    obj.optJSONArray("mk")?.let { arr ->
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val s = j.optInt("s", -1)
            val e = j.optInt("e", -1)
            if (s < 0 || e < s || e > cleanText.length) continue
            val type = j.optString("type", "")
            val (open, close) = when (type) {
                "c" -> "`" to "`"
                "b" -> "**" to "**"
                "pre", "t" -> "```" to "```"
                else -> continue
            }
            inserts.getOrPut(s) { StringBuilder() }.append(open)
            inserts.getOrPut(e) { StringBuilder() }.append(close)
        }
    }

    val rawStart = IntArray(cleanText.length + 1)
    val sb = StringBuilder()
    var rawPos = 0
    for (cleanPos in 0..cleanText.length) {
        inserts[cleanPos]?.let {
            sb.append(it)
            rawPos += it.length
        }
        rawStart[cleanPos] = rawPos
        if (cleanPos < cleanText.length) {
            sb.append(cleanText[cleanPos])
            rawPos++
        }
    }

    fun rangeToRaw(s: Int, e: Int): Pair<Int, Int> {
        val cs = s.coerceIn(0, cleanText.length)
        val ce = e.coerceIn(cs, cleanText.length)
        val rawS = rawStart[cs]
        val rawE = if (ce > 0) rawStart[ce - 1] + 1 else rawS
        return rawS to rawE
    }

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

    return RestoredInputContent(sb.toString(), mentions, hashtags, emojis)
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

fun parseMarkdownAndStrip(rawText: String): MarkdownParseResult {
    if (rawText.isBlank()) return MarkdownParseResult(rawText, emptyList(), emptyList())
    val markers = mutableListOf<MarkdownMarker>()
    val removals = mutableListOf<IntRange>()
    val cleaned = StringBuilder()
    var i = 0
    var shift = 0

    while (i < rawText.length) {

        if (i + 2 < rawText.length &&
            rawText[i] == '`' && rawText[i + 1] == '`' && rawText[i + 2] == '`'
        ) {
            var j = i + 3
            while (j + 2 < rawText.length) {
                if (rawText[j] == '`' && rawText[j + 1] == '`' && rawText[j + 2] == '`') break
                j++
            }
            if (j + 2 < rawText.length &&
                rawText[j] == '`' && rawText[j + 1] == '`' && rawText[j + 2] == '`'
            ) {
                val content = rawText.substring(i + 3, j)
                if (content.isNotEmpty()) {
                    val cleanContent = stripBoldFromContent(content, i + 3, removals)
                    val s = i - shift
                    markers.add(MarkdownMarker(MARKDOWN_PRE, s, s + cleanContent.length))
                    cleaned.append(cleanContent)
                    removals.add(i..i + 2)
                    removals.add(j..j + 2)
                    shift += 6 + (content.length - cleanContent.length)
                    i = j + 3
                    continue
                }
            }
        }

        if (rawText.startsWith("http://", i) || rawText.startsWith("https://", i)) {
            val linkStart = i
            val schemeLen = if (rawText.startsWith("https://", i)) 8 else 7
            var j = i + schemeLen
            while (j < rawText.length && rawText[j] != ' ' && rawText[j] != '\n'
                && rawText[j] != '\r' && rawText[j] != '\t'
            ) j++
            var linkEnd = j
            while (linkEnd > linkStart + schemeLen && rawText[linkEnd - 1] in TRAILING_PUNCTUATION) linkEnd--
            if (linkEnd > linkStart + schemeLen) {
                val s = linkStart - shift
                markers.add(MarkdownMarker(MARKDOWN_LINK, s, s + (linkEnd - linkStart)))
                cleaned.append(rawText, linkStart, linkEnd)
                i = linkEnd
                continue
            }
        }

        if (rawText[i] == '`' &&
            !(i + 2 < rawText.length && rawText[i + 1] == '`' && rawText[i + 2] == '`')
        ) {
            var j = i + 1
            while (j < rawText.length && rawText[j] != '`') j++
            if (j < rawText.length && rawText[j] == '`') {
                var allow = true
                if (j + 2 < rawText.length && rawText[j + 1] == '`' && rawText[j + 2] == '`') {
                    var k = j + 3
                    var hasClosingTriple = false
                    while (k + 2 < rawText.length) {
                        if (rawText[k] == '`' && rawText[k + 1] == '`' && rawText[k + 2] == '`') {
                            hasClosingTriple = true
                            break
                        }
                        k++
                    }
                    if (hasClosingTriple) allow = false
                }
                val content = rawText.substring(i + 1, j)
                if (allow && !content.contains("``") && content.trim().isNotEmpty()) {
                    val cleanContent = stripBoldFromContent(content, i + 1, removals)
                    val s = i - shift
                    markers.add(MarkdownMarker(MARKDOWN_CODE, s, s + cleanContent.length))
                    cleaned.append(cleanContent)
                    removals.add(i..i)
                    removals.add(j..j)
                    shift += 2 + (content.length - cleanContent.length)
                    i = j + 1
                    continue
                }
            }
        }

        if (i + 1 < rawText.length && rawText[i] == '*' && rawText[i + 1] == '*') {
            val closeIdx = rawText.indexOf("**", i + 2)
            if (closeIdx >= 0) {
                val content = rawText.substring(i + 2, closeIdx)
                if (content.trim().isNotEmpty()) {
                    val s = i - shift
                    markers.add(MarkdownMarker(MARKDOWN_BOLD, s, s + content.length))
                    cleaned.append(content)
                    removals.add(i..i + 1)
                    removals.add(closeIdx..closeIdx + 1)
                    shift += 4
                    i = closeIdx + 2
                    continue
                }
            }
        }

        cleaned.append(rawText[i])
        i++
    }

    removals.sortBy { it.first }
    return MarkdownParseResult(cleaned.toString(), markers, removals)
}

private fun stripBoldFromContent(
    content: String,
    originalBase: Int,
    removals: MutableList<IntRange>
): String {
    val sb = StringBuilder()
    var ci = 0
    while (ci < content.length) {
        if (ci + 1 < content.length && content[ci] == '*' && content[ci + 1] == '*') {
            val closeIdx = content.indexOf("**", ci + 2)
            if (closeIdx >= 0 && content.substring(ci + 2, closeIdx).trim().isNotEmpty()) {
                removals.add((originalBase + ci)..(originalBase + ci + 1))
                removals.add((originalBase + closeIdx)..(originalBase + closeIdx + 1))
                sb.append(content, ci + 2, closeIdx)
                ci = closeIdx + 2
                continue
            }
        }
        sb.append(content[ci])
        ci++
    }
    return sb.toString()
}

private val TRAILING_PUNCTUATION = charArrayOf(',', '.', '!', '?', ';', ':')

const val MARKDOWN_PRE = "pre"
const val MARKDOWN_CODE = "c"
const val MARKDOWN_BOLD = "b"
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
