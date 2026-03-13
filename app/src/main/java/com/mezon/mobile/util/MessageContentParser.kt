package com.mezon.mobile.util

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
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

private const val CODE_BG_DARK = 0xFF2A2D31.toInt()
private const val CODE_BG_LIGHT = 0xFFE7E0EC.toInt()
private const val CODE_TEXT_DARK = 0xFFE06C75.toInt()
private const val CODE_TEXT_LIGHT = 0xFFD63384.toInt()

fun parseContentToSpannable(
    content: String,
    linkColor: Int,
    view: View? = null,
    mentionColors: MentionColors? = null,
    isDark: Boolean = true
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
    val linkColorSpan = linkColor
    val viewRef = view?.let { java.lang.ref.WeakReference(it) }

    for (el in elements) {
        if (el.s > last && last < text.length) {
            sb.append(text.substring(last, minOf(el.s, text.length)))
        }
        val segText = if (el.e <= text.length) text.substring(el.s, el.e) else ""
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
                        linkColorSpan to android.graphics.Color.TRANSPARENT
                }
                sb.setSpan(MentionSpan(el.user_id, el.role_id, textColor, bgColor), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            "h" -> {
                sb.append(segText)
                sb.setSpan(HashtagSpan(el.channelId, linkColorSpan), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            "e" -> {
                if (viewRef != null && el.emojiid != null) {
                    sb.append("\uFFFC")
                    sb.setSpan(EmojiSpan(el.emojiid, viewRef), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    sb.append(segText)
                    sb.setSpan(ForegroundColorSpan(linkColorSpan), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            "k" -> when (el.type) {
                "b" -> {
                    sb.append(segText)
                    sb.setSpan(StyleSpan(Typeface.BOLD), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "c", "s" -> {
                    val codeText = segText.removeSurrounding("`").trim()
                    sb.append(" $codeText ")
                    sb.setSpan(TypefaceSpan("monospace"), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(if (isDark) CODE_TEXT_DARK else CODE_TEXT_LIGHT), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(if (isDark) CODE_BG_DARK else CODE_BG_LIGHT), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "pre", "t" -> {
                    val codeText = segText.removeSurrounding("```").trim()
                    sb.append("\n$codeText\n")
                    sb.setSpan(TypefaceSpan("monospace"), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(0.9f), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(if (isDark) CODE_TEXT_DARK else CODE_TEXT_LIGHT), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(if (isDark) CODE_BG_DARK else CODE_BG_LIGHT), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "lk", "vk", "lk_yt", "lk_fb", "lk_tt" -> {
                    sb.append(segText)
                    sb.setSpan(LinkSpan(segText, linkColorSpan), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "lk_ogp" -> {
                    val url = extractOgpUrl(text, el.index)
                    sb.append(segText)
                    sb.setSpan(LinkSpan(url ?: segText, linkColorSpan), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> sb.append(segText)
            }
            else -> sb.append(segText)
        }
        last = el.e
    }
    if (last < text.length) {
        sb.append(text.substring(last))
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
