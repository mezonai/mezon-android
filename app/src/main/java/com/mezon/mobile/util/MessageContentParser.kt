package com.mezon.mobile.util

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.CodeFenceSpan
import com.mezon.mobile.home.chat.EmojiSpan
import com.mezon.mobile.home.chat.HashtagSpan
import com.mezon.mobile.home.chat.LinkSpan
import com.mezon.mobile.home.chat.MentionSpan
import org.json.JSONArray
import org.json.JSONObject

private val BASE_IMG: String
    get() = com.mezon.mobile.BuildConfig.MEZON_BASE_IMG_URL
private const val EMOJI_SIZE_DP = 20

fun isRawMessage(content: String): Boolean {
    if (content.isBlank()) return true
    return try {
        val obj = JSONObject(content)
        val t = obj.optString("t", "")
        if (t.isBlank()) return true
        if (obj.has("embed")) return false
        val mentions = obj.optJSONArray("mentions")
        if (mentions != null && mentions.length() > 0) return false
        val hg = obj.optJSONArray("hg")
        if (hg != null && hg.length() > 0) return false
        val ej = obj.optJSONArray("ej")
        if (ej != null && ej.length() > 0) return false
        val mk = obj.optJSONArray("mk")
        mk == null || mk.length() == 0
    } catch (_: Exception) {
        true
    }
}

data class MentionColors(
    val userText: Int,
    val userBg: Int,
    val roleText: Int,
    val roleBg: Int
)

data class ContentElement(
    val kind: String,
    val s: Int,
    val e: Int,
    val user_id: String? = null,
    val role_id: String? = null,
    val emojiid: String? = null,
    val channelId: String? = null,
    val type: String? = null,
    val title: String? = null,
    val description: String? = null,
    val image: String? = null,
    val index: Int? = null
)

private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.+)$")
private val HEADING_LINE_ANYWHERE = Regex("(?m)^#{1,6}\\s+\\S")

fun hasHeadingLine(text: String): Boolean {
    if (text.isEmpty() || !text.contains('#')) return false
    return HEADING_LINE_ANYWHERE.containsMatchIn(text)
}

fun buildPlainTextWithHeadings(text: String, theme: ThemeColors): CharSequence {
    if (!hasHeadingLine(text)) return text
    val sb = SpannableStringBuilder()
    applyPlainTextWithHeadings(sb, text, theme)
    return sb
}

fun applyHeadingSpans(sb: SpannableStringBuilder, start: Int, end: Int, level: Int) {
    val sizeFactor = when (level) {
        1 -> 2.14f
        2 -> 1.86f
        3 -> 1.57f
        4 -> 1.29f
        5 -> 1.14f
        else -> 1.0f
    }
    sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    sb.setSpan(RelativeSizeSpan(sizeFactor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

fun applyPlainTextWithHeadings(sb: SpannableStringBuilder, text: String, theme: ThemeColors) {
    val lines = text.split("\n")
    lines.forEachIndexed { idx, line ->
        val spanStart = sb.length
        val headingMatch = HEADING_REGEX.matchEntire(line)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val headingText = headingMatch.groupValues[2].trim()
            sb.append(headingText)
            applyHeadingSpans(sb, spanStart, sb.length, level)
        } else {
            sb.append(line)
        }
        if (idx < lines.size - 1) sb.append("\n")
    }
}

fun parseContentToSpannable(
    content: String,
    linkColor: Int,
    view: View? = null,
    mentionColors: MentionColors? = null,
    theme: ThemeColors
): SpannableStringBuilder {
    val sb = SpannableStringBuilder()
    val text = parseContentText(content)
    if (text.isBlank()) return sb

    val elements = mutableListOf<ContentElement>()
    try {
        val obj = JSONObject(content)
        parseArray(obj, "mentions") { j -> ContentElement("m", j.optInt("s"), j.optInt("e"), j.optString("user_id").takeIf { it.isNotEmpty() }, j.optString("role_id").takeIf { it.isNotEmpty() }) }
            .let { elements.addAll(it) }
        parseArray(obj, "hg") { j -> ContentElement("h", j.optInt("s"), j.optInt("e"), channelId = j.optString("channelId").takeIf { it.isNotEmpty() }) }
            .let { elements.addAll(it) }
        parseArray(obj, "ej") { j -> ContentElement("e", j.optInt("s"), j.optInt("e"), emojiid = j.optString("emojiid").takeIf { it.isNotEmpty() }) }
            .let { elements.addAll(it) }
        parseArray(obj, "mk") { j ->
            ContentElement(
                "k",
                j.optInt("s"),
                j.optInt("e"),
                type = j.optString("type").takeIf { it.isNotEmpty() },
                title = j.optString("title").takeIf { it.isNotEmpty() },
                description = j.optString("description").takeIf { it.isNotEmpty() },
                image = j.optString("image").takeIf { it.isNotEmpty() },
                index = if (j.has("index")) j.optInt("index") else null
            )
        }.let { elements.addAll(it) }
    } catch (_: Exception) {
    }

    elements.sortBy { it.s }
    var last = 0
    val viewRef = view?.let { java.lang.ref.WeakReference(it) }

    for (el in elements) {
        if (el.s > last && last < text.length) {
            val plainText = text.substring(last, minOf(el.s, text.length))
            applyPlainTextWithHeadings(sb, plainText, theme)
        }
        val clampedS = el.s.coerceIn(0, text.length)
        val clampedE = el.e.coerceIn(clampedS, text.length)
        val segText = if (clampedE > clampedS) text.substring(clampedS, clampedE) else ""
        val spanStart = sb.length
        when (el.kind) {
            "m" -> {
                sb.append(segText)
                val (textColor, bgColor) = when {
                    mentionColors != null && el.role_id != null && el.role_id != "0" ->
                        mentionColors.roleText to mentionColors.roleBg
                    mentionColors != null ->
                        mentionColors.userText to mentionColors.userBg
                    else ->
                        linkColor to android.graphics.Color.TRANSPARENT
                }
                sb.setSpan(MentionSpan(el.user_id, el.role_id, textColor, bgColor), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            "h" -> {
                sb.append(segText)
                sb.setSpan(HashtagSpan(el.channelId, linkColor), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(BackgroundColorSpan(theme.midnightBlue), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            "e" -> {
                if (viewRef != null && el.emojiid != null) {
                    sb.append("\uFFFC")
                    sb.setSpan(EmojiSpan(el.emojiid, viewRef), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    sb.append(segText)
                    sb.setSpan(ForegroundColorSpan(linkColor), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            "k" -> when (el.type) {
                "b" -> {
                    sb.append(segText)
                    sb.setSpan(StyleSpan(Typeface.BOLD), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "i" -> {
                    sb.append(segText)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "s" -> {
                    sb.append(segText)
                    sb.setSpan(StrikethroughSpan(), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "c" -> {
                    val codeText = segText.removeSurrounding("`").trim()
                    sb.append(" $codeText ")
                    sb.setSpan(TypefaceSpan("monospace"), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(theme.codeInlineText), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(theme.codeInlineBg), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "pre", "t" -> {
                    val stripped = segText
                        .removeSurrounding("```")
                        .replace(Regex("^\\n+|\\n+$"), "")
                        .trim()
                    if (sb.isNotEmpty() && sb.last() != '\n') sb.append("\n")
                    val fenceStart = sb.length
                    sb.append(" \n")
                    sb.append(stripped)
                    sb.append("\n ")
                    val fenceEnd = sb.length
                    sb.append("\n")
                    sb.setSpan(TypefaceSpan("monospace"), fenceStart, fenceEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(0.875f), fenceStart, fenceEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(theme.codeInlineText), fenceStart, fenceEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(
                        CodeFenceSpan(theme.codeFenceBg),
                        fenceStart, fenceEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                "lk", "vk", "lk_yt", "lk_fb", "lk_tt" -> {
                    sb.append(segText)
                    sb.setSpan(LinkSpan(segText, linkColor), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "lk_ogp" -> {
                    val url = extractOgpUrl(text, el.index)
                    sb.append(segText)
                    sb.setSpan(LinkSpan(url ?: segText, linkColor), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> sb.append(segText)
            }
            else -> sb.append(segText)
        }
        last = el.e
    }
    if (last < text.length) {
        applyPlainTextWithHeadings(sb, text.substring(last), theme)
    }
    return sb
}

private fun extractOgpUrl(text: String, index: Int?): String? {
    if (index == null || index < 0 || index >= text.length) return null
    val spaceIdx = text.indexOf(' ', index)
    return if (spaceIdx < 0) text.substring(index) else text.substring(index, spaceIdx)
}

data class OgpData(
    val title: String,
    val description: String,
    val image: String,
    val url: String
)

fun parseOgpData(content: String): OgpData? {
    val text = parseContentText(content)
    if (text.isBlank()) return null
    return try {
        val obj = org.json.JSONObject(content)
        val mk = obj.optJSONArray("mk") ?: return null
        for (i in 0 until mk.length()) {
            val j = mk.optJSONObject(i) ?: continue
            if (j.optString("type") != "lk_ogp") continue
            val index = if (j.has("index")) j.optInt("index") else -1
            val url = extractOgpUrl(text, if (index >= 0) index else null) ?: continue
            if (isGoogleMapLink(url)) continue
            val title = j.optString("title").takeIf { it.isNotEmpty() } ?: continue
            val description = j.optString("description").takeIf { it.isNotEmpty() } ?: continue
            val image = j.optString("image").takeIf { it.isNotEmpty() } ?: continue
            return OgpData(title, description, image, url)
        }
        null
    } catch (_: Exception) {
        null
    }
}

private fun isGoogleMapLink(url: String): Boolean {
    return try {
        val host = java.net.URL(url).host.lowercase()
        host.contains("google.com") || host == "goo.gl" || host == "maps.app.goo.gl"
    } catch (_: Exception) {
        false
    }
}

private fun parseArray(obj: JSONObject, key: String, map: (JSONObject) -> ContentElement): List<ContentElement> {
    val arr = obj.optJSONArray(key) ?: return emptyList()
    val list = mutableListOf<ContentElement>()
    for (i in 0 until arr.length()) {
        val j = arr.optJSONObject(i) ?: continue
        list.add(map(j))
    }
    return list
}

fun getEmojiUrl(emojiId: String): String? {
    if (emojiId.isBlank()) return null
    val sourceUrl = "$BASE_IMG/emojis/$emojiId.webp"
    return createImgproxyUrl(sourceUrl, EMOJI_SIZE_DP * 4, EMOJI_SIZE_DP * 4, "fit")
}

fun resolveStickerSourceUrl(stickerId: String, src: String): String {
    return if (src.isNotBlank()) src
    else if (stickerId.isNotBlank()) "$BASE_IMG/stickers/$stickerId.webp"
    else ""
}

fun getStickerImageUrl(stickerId: String, src: String): String? {
    val sourceUrl = resolveStickerSourceUrl(stickerId, src)
    if (sourceUrl.isBlank()) return null
    return sourceUrl
}

data class EmbedField(
    val name: String,
    val value: String,
    val inline: Boolean
)

data class EmbedData(
    val color: Int,
    val title: String,
    val url: String,
    val authorName: String,
    val authorIconUrl: String,
    val description: String,
    val fields: List<EmbedField>,
    val imageUrl: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val thumbnailUrl: String,
    val footerText: String,
    val footerIconUrl: String,
    val timestamp: String
)

fun parseEmbedData(content: String): EmbedData? {
    return try {
        val obj = JSONObject(content)
        val embeds = obj.optJSONArray("embed") ?: return null
        if (embeds.length() == 0) return null
        val embed = embeds.optJSONObject(0) ?: return null

        val colorStr = embed.optString("color", "")
        val color = if (colorStr.isNotEmpty()) {
            try { android.graphics.Color.parseColor(colorStr) } catch (_: Exception) { 0 }
        } else 0

        val title = embed.optString("title", "")
        val url = embed.optString("url", "")

        val authorObj = embed.optJSONObject("author")
        val authorName = authorObj?.optString("name", "") ?: ""
        val authorIconUrl = authorObj?.optString("icon_url", "") ?: ""

        val description = embed.optString("description", "")

        val fields = mutableListOf<EmbedField>()
        val fieldsArr = embed.optJSONArray("fields")
        if (fieldsArr != null) {
            for (i in 0 until fieldsArr.length()) {
                val f = fieldsArr.optJSONObject(i) ?: continue
                fields.add(EmbedField(
                    name = f.optString("name", ""),
                    value = f.optString("value", ""),
                    inline = f.optBoolean("inline", false)
                ))
            }
        }

        val imageObj = embed.optJSONObject("image")
        val imageUrl = imageObj?.optString("url", "") ?: ""
        val imageWidth = imageObj?.optInt("width", 0) ?: 0
        val imageHeight = imageObj?.optInt("height", 0) ?: 0

        val thumbnailObj = embed.optJSONObject("thumbnail")
        val thumbnailUrl = thumbnailObj?.optString("url", "") ?: ""

        val footerObj = embed.optJSONObject("footer")
        val footerText = footerObj?.optString("text", "") ?: ""
        val footerIconUrl = footerObj?.optString("icon_url", "") ?: ""

        val timestamp = embed.optString("timestamp", "")

        if (title.isEmpty() && description.isEmpty() && fields.isEmpty() && imageUrl.isEmpty()) return null

        EmbedData(color, title, url, authorName, authorIconUrl, description, fields,
            imageUrl, imageWidth, imageHeight, thumbnailUrl, footerText, footerIconUrl, timestamp)
    } catch (_: Exception) {
        null
    }
}
