package com.mezon.mobile.util

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.chat.CodeFenceSpan
import com.mezon.mobile.home.chat.EmojiSpan
import com.mezon.mobile.home.chat.HashtagSpan
import com.mezon.mobile.home.chat.LinkSpan
import com.mezon.mobile.home.chat.MentionSpan
import com.mezon.mobile.home.chat.SystemMessageMentionSpan
import com.mezon.mobile.home.chat.SystemMessagePlainTextView
import com.mezon.mobile.home.clans.ChannelItemCell
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.ui.cells.ColoredImageSpan
import com.mezon.mobile.R
import com.mezon.mobile.ui.cells.MezonIcon
import dagger.hilt.android.EntryPointAccessors
import org.json.JSONArray
import org.json.JSONObject

private val BASE_IMG: String
    get() = com.mezon.mobile.BuildConfig.MEZON_BASE_IMG_URL
private const val EMOJI_SIZE_DP = 20

private val VALID_LINK_INVITE_REGEX =
    Regex("""https?://(?:www\.)?(?:mezon\.ai|gw\.mezon\.ai)/invite/[0-9]+""", RegexOption.IGNORE_CASE)
private val INVITE_ID_FROM_TEXT =
    Regex("""https?://(?:www\.)?(?:mezon\.ai|gw\.mezon\.ai)/invite/([0-9]+)""", RegexOption.IGNORE_CASE)

private val AT_HERE_SYSTEM_REGEX =
    Regex("(?<!\\w)@here(?!\\w)", RegexOption.IGNORE_CASE)

fun contentHasNonEmptyLinkArray(content: String): Boolean {
    return try {
        val obj = JSONObject(content)
        val lk = obj.optJSONArray("lk")
        if (lk != null && lk.length() > 0) return true
        val mk = obj.optJSONArray("mk") ?: return false
        for (i in 0 until mk.length()) {
            val item = mk.optJSONObject(i) ?: continue
            if (item.optString("type", "") == "lk") return true
        }
        false
    } catch (_: Exception) {
        false
    }
}

fun parseInviteLinkMessageId(content: String): Long? {
    if (!contentHasNonEmptyLinkArray(content)) return null
    val t = parseContentText(content)
    if (!VALID_LINK_INVITE_REGEX.containsMatchIn(t)) return null
    return INVITE_ID_FROM_TEXT.find(t)?.groupValues?.getOrNull(1)?.toLongOrNull()
}

private fun SpannableStringBuilder.setExclusiveSpan(what: Any, start: Int, end: Int) {
    if (end > start) setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

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
    val clanId: String? = null,
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
    sb.setExclusiveSpan(StyleSpan(Typeface.BOLD), start, end)
    sb.setExclusiveSpan(RelativeSizeSpan(sizeFactor), start, end)
}

fun applyPlainTextWithHeadings(sb: SpannableStringBuilder, text: String, theme: ThemeColors) {
    val lines = text.split("\n")
    lines.forEachIndexed { idx, line ->
        val spanStart = sb.length
        val headingMatch = HEADING_REGEX.matchEntire(line)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val headingText = headingMatch.groupValues[2].trim()
            if (headingText.isNotEmpty()) {
                sb.append(headingText)
                applyHeadingSpans(sb, spanStart, sb.length, level)
            } else {
                sb.append(line)
            }
        } else {
            sb.append(line)
        }
        if (idx < lines.size - 1) sb.append("\n")
    }
}

private fun stripMarkdownFenceLanguage(body: String): String {
    val trimmed = body.trim('\n')
    val lines = trimmed.split("\n")
    if (lines.size >= 2) {
        val first = lines.first()
        if (first.isNotEmpty() && first.matches(FENCE_LANGUAGE_REGEX)) {
            return lines.drop(1).joinToString("\n").trim('\n')
        }
    }
    return trimmed
}

private val FENCE_LANGUAGE_REGEX = Regex("^[a-zA-Z0-9+#.-]{0,24}$")
private val CODE_BLOCK_EDGE_REGEX = Regex("^\\n+|\\n+$")
private val MULTILINE_FENCE_REGEX = Regex("```([\\s\\S]*?)```")
private val EMBED_INLINE_CODE_REGEX = Regex("`([^`\n]+)`")
private val EMBED_INLINE_BOLD_REGEX = Regex("\\*\\*([^*\n]+)\\*\\*")
private val EMBED_INLINE_UNDERLINE_REGEX = Regex("__(?!_)([^_\n]+)__")
private val EMBED_INLINE_STRIKE_REGEX = Regex("~~([^~\n]+)~~")
private val EMBED_INLINE_ITALIC_ASTERISK_REGEX = Regex("(?<!\\*)\\*([^*\n]+)\\*(?!\\*)")
private val EMBED_INLINE_ITALIC_UNDERSCORE_REGEX = Regex("(?<!_)_([^_\n]+)_(?!_)")

private fun appendCodeFenceBlock(sb: SpannableStringBuilder, innerRaw: String, theme: ThemeColors) {
    val inner = stripMarkdownFenceLanguage(innerRaw)
    val start = sb.length
    sb.append('\n')
    sb.append(inner)
    sb.append('\n')
    val end = sb.length
    if (end > start) {
        sb.setExclusiveSpan(TypefaceSpan("monospace"), start, end)
        sb.setExclusiveSpan(RelativeSizeSpan(0.875f), start, end)
        sb.setExclusiveSpan(ForegroundColorSpan(theme.codeInlineText), start, end)
        sb.setExclusiveSpan(CodeFenceSpan(theme.codeFenceBg), start, end)
    }
}

private fun appendEmbedPlainMultiline(sb: SpannableStringBuilder, plain: String, theme: ThemeColors) {
    if (plain.isEmpty()) return
    val lines = plain.split("\n")
    lines.forEachIndexed { idx, line ->
        val trimmedForHeading = line.trimEnd()
        val headingMatch = HEADING_REGEX.matchEntire(trimmedForHeading)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val headingText = headingMatch.groupValues[2].trim()
            if (headingText.isNotEmpty()) {
                val lineSb = SpannableStringBuilder(headingText)
                applyEmbedInlineMarkdown(lineSb, theme)
                val spanStart = sb.length
                sb.append(lineSb)
                applyHeadingSpans(sb, spanStart, sb.length, level)
            } else {
                val lineSb = SpannableStringBuilder(line)
                applyEmbedInlineMarkdown(lineSb, theme)
                sb.append(lineSb)
            }
        } else {
            val lineSb = SpannableStringBuilder(line)
            applyEmbedInlineMarkdown(lineSb, theme)
            sb.append(lineSb)
        }
        if (idx < lines.size - 1) sb.append("\n")
    }
}

fun appendRichMarkdownWithFences(sb: SpannableStringBuilder, raw: String, theme: ThemeColors) {
    if (raw.isEmpty()) return
    val normalized = raw
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("\\r\\n", "\n")
        .replace("\\r", "\n")
        .replace("\\n", "\n")
    var pos = 0
    for (m in MULTILINE_FENCE_REGEX.findAll(normalized)) {
        if (m.range.first > pos) {
            appendEmbedPlainMultiline(sb, normalized.substring(pos, m.range.first), theme)
        }
        appendCodeFenceBlock(sb, m.groupValues[1], theme)
        pos = m.range.last + 1
    }
    if (pos < normalized.length) {
        appendEmbedPlainMultiline(sb, normalized.substring(pos), theme)
    }
}

fun formatEmbedRichText(raw: String, theme: ThemeColors): CharSequence {
    if (raw.isEmpty()) return ""
    val sb = SpannableStringBuilder()
    appendRichMarkdownWithFences(sb, raw, theme)
    applyAutoDetectedHttpLinks(sb, theme.blurple)
    return sb
}

private fun stripDelimiterAndSpan(
    sb: SpannableStringBuilder,
    regex: Regex,
    apply: (SpannableStringBuilder, Int, Int) -> Unit,
) {
    for (m in regex.findAll(sb).toList().asReversed()) {
        val inner = m.groupValues[1]
        if (inner.isEmpty()) continue
        val start = m.range.first
        val endEx = m.range.last + 1
        sb.replace(start, endEx, inner)
        apply(sb, start, start + inner.length)
    }
}

private fun applyEmbedInlineMarkdown(sb: SpannableStringBuilder, theme: ThemeColors) {
    stripDelimiterAndSpan(sb, EMBED_INLINE_CODE_REGEX) { b, a, e ->
        b.setExclusiveSpan(TypefaceSpan("monospace"), a, e)
        b.setExclusiveSpan(ForegroundColorSpan(theme.codeInlineText), a, e)
        b.setExclusiveSpan(BackgroundColorSpan(theme.codeInlineBg), a, e)
    }
    stripDelimiterAndSpan(sb, EMBED_INLINE_BOLD_REGEX) { b, a, e ->
        b.setExclusiveSpan(StyleSpan(Typeface.BOLD), a, e)
    }
    stripDelimiterAndSpan(sb, EMBED_INLINE_UNDERLINE_REGEX) { b, a, e ->
        b.setExclusiveSpan(UnderlineSpan(), a, e)
    }
    stripDelimiterAndSpan(sb, EMBED_INLINE_STRIKE_REGEX) { b, a, e ->
        b.setExclusiveSpan(StrikethroughSpan(), a, e)
    }
    stripDelimiterAndSpan(sb, EMBED_INLINE_ITALIC_ASTERISK_REGEX) { b, a, e ->
        b.setExclusiveSpan(StyleSpan(Typeface.ITALIC), a, e)
    }
    stripDelimiterAndSpan(sb, EMBED_INLINE_ITALIC_UNDERSCORE_REGEX) { b, a, e ->
        b.setExclusiveSpan(StyleSpan(Typeface.ITALIC), a, e)
    }
}

fun parseContentToSpannable(
    content: String,
    linkColor: Int,
    view: View? = null,
    mentionColors: MentionColors? = null,
    theme: ThemeColors,
    systemPlainHost: SystemMessagePlainTextView? = null,
    systemMentionGate: ((userId: String?, roleId: String?, segmentText: String) -> Boolean)? = null
): SpannableStringBuilder {
    val sb = SpannableStringBuilder()
    val text = parseContentText(content)
    if (text.isBlank()) return sb

    val elements = mutableListOf<ContentElement>()
    try {
        val obj = JSONObject(content)
        parseArray(obj, "mentions") { j -> ContentElement("m", j.optInt("s"), j.optInt("e"), j.optString("user_id").takeIf { it.isNotEmpty() }, j.optString("role_id").takeIf { it.isNotEmpty() }) }
            .let { elements.addAll(it) }
        parseArray(obj, "hg") { j ->
            ContentElement(
                "h",
                j.optInt("s"),
                j.optInt("e"),
                channelId = j.optString("channelId").takeIf { it.isNotEmpty() },
                clanId = j.optString("clanId").takeIf { it.isNotEmpty() }
            )
        }.let { elements.addAll(it) }
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

    if (systemPlainHost != null) {
        mergeSyntheticAtHereForSystemPlain(text, elements)
    }

    elements.sortBy { it.s }
    var last = 0
    val viewRef = view?.let { java.lang.ref.WeakReference(it) }
    val richPlainMarkdown = isEmbedOrComponentsPayload(content)

    for (el in elements) {
        var nextLast = el.e
        val clampedS = el.s.coerceIn(0, text.length)
        val clampedE = el.e.coerceIn(clampedS, text.length)
        val segText = if (clampedE > clampedS) text.substring(clampedS, clampedE) else ""

        var schemeMergeLen = 0
        if (el.kind == "k" && el.type in MK_TYPES_URL_WITH_OPTIONAL_SCHEME && segText.isNotEmpty() &&
            !segText.startsWith("http://", ignoreCase = true) &&
            !segText.startsWith("https://", ignoreCase = true)
        ) {
            schemeMergeLen = urlSchemeLenBeforeHost(text, clampedS)
        }

        if (el.s > last && last < text.length) {
            val plainEnd = (minOf(el.s, text.length) - schemeMergeLen).coerceIn(last, text.length)
            if (plainEnd > last) {
                val plain = text.substring(last, plainEnd)
                if (richPlainMarkdown) {
                    appendRichMarkdownWithFences(sb, plain, theme)
                } else {
                    applyPlainTextWithHeadings(sb, plain, theme)
                }
            }
        }
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
                val useSystemMentions = systemPlainHost != null
                val interactive = systemMentionGate?.invoke(el.user_id, el.role_id, segText) == true
                if (useSystemMentions && interactive) {
                    sb.setExclusiveSpan(
                        SystemMessageMentionSpan(el.user_id, el.role_id, textColor, bgColor),
                        spanStart,
                        sb.length
                    )
                    sb.setExclusiveSpan(StyleSpan(Typeface.BOLD), spanStart, sb.length)
                } else if (useSystemMentions) {
                    sb.setExclusiveSpan(ForegroundColorSpan(linkColor), spanStart, sb.length)
                } else {
                    sb.setExclusiveSpan(MentionSpan(el.user_id, el.role_id, textColor, bgColor), spanStart, sb.length)
                    sb.setExclusiveSpan(StyleSpan(Typeface.BOLD), spanStart, sb.length)
                }
            }
            "h" -> {
                appendHashtagPill(
                    sb,
                    spanStart,
                    segText = segText,
                    channelId = el.channelId,
                    clanId = el.clanId,
                    view = view,
                    linkColor = linkColor,
                    mentionColors = mentionColors,
                    theme = theme,
                    labelOverride = null,
                    preResolvedEntity = null
                )
            }
            "e" -> {
                if (viewRef != null && el.emojiid != null) {
                    sb.append("\uFFFC")
                    sb.setExclusiveSpan(EmojiSpan(el.emojiid, viewRef), spanStart, sb.length)
                } else {
                    sb.append(segText)
                    sb.setExclusiveSpan(ForegroundColorSpan(linkColor), spanStart, sb.length)
                }
            }
            "k" -> when (el.type) {
                "b" -> {
                    sb.append(segText)
                    sb.setExclusiveSpan(StyleSpan(Typeface.BOLD), spanStart, sb.length)
                }
                "i" -> {
                    sb.append(segText)
                    sb.setExclusiveSpan(StyleSpan(Typeface.ITALIC), spanStart, sb.length)
                }
                "s" -> {
                    sb.append(segText)
                    sb.setExclusiveSpan(StrikethroughSpan(), spanStart, sb.length)
                }
                "c" -> {
                    val codeText = segText.removeSurrounding("`").trim()
                    sb.append(" $codeText ")
                    sb.setExclusiveSpan(TypefaceSpan("monospace"), spanStart, sb.length)
                    sb.setExclusiveSpan(ForegroundColorSpan(theme.codeInlineText), spanStart, sb.length)
                    sb.setExclusiveSpan(BackgroundColorSpan(theme.codeInlineBg), spanStart, sb.length)
                }
                "pre", "t" -> {
                    val stripped = segText
                        .removeSurrounding("```")
                        .replace(CODE_BLOCK_EDGE_REGEX, "")
                        .trim()
                    if (sb.isNotEmpty() && sb.last() != '\n') sb.append("\n")
                    val fenceStart = sb.length
                    sb.append('\n')
                    sb.append(stripped)
                    sb.append('\n')
                    val fenceEnd = sb.length
                    sb.append("\n")
                    sb.setExclusiveSpan(TypefaceSpan("monospace"), fenceStart, fenceEnd)
                    sb.setExclusiveSpan(RelativeSizeSpan(0.875f), fenceStart, fenceEnd)
                    sb.setExclusiveSpan(ForegroundColorSpan(theme.codeInlineText), fenceStart, fenceEnd)
                    sb.setExclusiveSpan(CodeFenceSpan(theme.codeFenceBg), fenceStart, fenceEnd)
                }
                "lk", "vk", "lk_yt", "lk_fb", "lk_tt" -> {
                    val linkStartInText = (clampedS - schemeMergeLen).coerceAtLeast(0)
                    val linkEndExclusive = extendHttpUrlRangeExclusive(text, linkStartInText, clampedE)
                    nextLast = maxOf(el.e, linkEndExclusive)
                    val linkSeg = text.substring(linkStartInText, linkEndExclusive)
                    val channelLink = parseMezonChannelLink(linkSeg)
                    val resolved = channelLink?.let { link ->
                        view?.context?.let { ctx ->
                            resolveChannelEntity(ctx, link.channelId, link.clanId)
                        }
                    }
                    if (resolved != null) {
                        val label = resolved.channelLabel.ifBlank { "channel" }
                        appendHashtagPill(
                            sb,
                            spanStart,
                            segText = "#$label",
                            channelId = resolved.channelId.toString(),
                            clanId = resolved.clanId.toString(),
                            view = view,
                            linkColor = linkColor,
                            mentionColors = mentionColors,
                            theme = theme,
                            labelOverride = label,
                            preResolvedEntity = resolved
                        )
                    } else {
                        sb.append(linkSeg)
                        sb.setExclusiveSpan(LinkSpan(linkSeg, linkColor), spanStart, sb.length)
                    }
                }
                "lk_ogp" -> {
                    val linkStartInText = (clampedS - schemeMergeLen).coerceAtLeast(0)
                    val linkEndExclusive = extendHttpUrlRangeExclusive(text, linkStartInText, clampedE)
                    nextLast = maxOf(el.e, linkEndExclusive)
                    val linkSeg = text.substring(linkStartInText, linkEndExclusive)
                    val url = extractOgpUrl(text, el.index)
                    sb.append(linkSeg)
                    sb.setExclusiveSpan(LinkSpan(url ?: linkSeg, linkColor), spanStart, sb.length)
                }
                else -> sb.append(segText)
            }
            else -> sb.append(segText)
        }
        last = nextLast
    }
    if (last < text.length) {
        val tail = text.substring(last)
        if (richPlainMarkdown) {
            appendRichMarkdownWithFences(sb, tail, theme)
        } else {
            applyPlainTextWithHeadings(sb, tail, theme)
        }
    }
    if (richPlainMarkdown) {
        applyAutoDetectedHttpLinks(sb, linkColor)
    }
    return sb
}

private fun trimAutoLinkUrl(url: String): String {
    var u = url
    while (u.isNotEmpty() && u.last() in ".,;:!?)]}\\\"") {
        u = u.dropLast(1)
    }
    return u
}

private fun httpSchemeIndex(s: String, from: Int): Int {
    val a = s.indexOf("http://", from, ignoreCase = true)
    val b = s.indexOf("https://", from, ignoreCase = true)
    return when {
        a < 0 -> b
        b < 0 -> a
        else -> minOf(a, b)
    }
}

private fun shouldSkipAutoLinkRegion(sb: Spannable, start: Int, end: Int): Boolean {
    if (end <= start) return true
    for (span in sb.getSpans(start, end, Any::class.java)) {
        when (span) {
            is MentionSpan, is SystemMessageMentionSpan, is HashtagSpan -> return true
            is CodeFenceSpan -> return true
        }
        if (span is ClickableSpan && span !is LinkSpan) return true
    }
    return false
}

private fun applyAutoDetectedHttpLinks(sb: SpannableStringBuilder, linkColor: Int) {
    if (sb.isEmpty()) return
    val full = sb.toString()
    var from = 0
    while (from < full.length) {
        val h = httpSchemeIndex(full, from)
        if (h < 0) break
        var e = h
        while (e < full.length) {
            val c = full[e]
            if (c == '\n' || c == '\r' || c.isWhitespace()) break
            e++
        }
        val url = trimAutoLinkUrl(full.substring(h, e))
        val minLen = "https://".length
        from = if (url.length <= minLen || (!url.startsWith("http://", ignoreCase = true) && !url.startsWith(
                "https://",
                ignoreCase = true
            ))
        ) {
            h + 1
        } else {
            val endEx = h + url.length
            val hasFullMkLink = sb.getSpans(h, endEx, LinkSpan::class.java).any { span ->
                sb.getSpanStart(span) == h && sb.getSpanEnd(span) == endEx
            }
            if (!hasFullMkLink && !shouldSkipAutoLinkRegion(sb, h, endEx)) {
                for (span in sb.getSpans(h, endEx, LinkSpan::class.java)) {
                    val ss = sb.getSpanStart(span)
                    val se = sb.getSpanEnd(span)
                    if (h <= ss && se <= endEx) sb.removeSpan(span)
                }
                sb.setExclusiveSpan(LinkSpan(url, linkColor), h, endEx)
            }
            endEx
        }
    }
}

private fun mergeSyntheticAtHereForSystemPlain(text: String, elements: MutableList<ContentElement>) {
    val occupied = elements.map { it.s to it.e }.toMutableList()
    for (match in AT_HERE_SYSTEM_REGEX.findAll(text)) {
        val s = match.range.first
        val eExclusive = match.range.last + 1
        val overlaps = occupied.any { (os, oe) -> s < oe && os < eExclusive }
        if (overlaps) continue
        elements.add(
            ContentElement(
                "m",
                s,
                eExclusive,
                user_id = MENTION_HERE_USER_ID,
                role_id = null
            )
        )
        occupied.add(s to eExclusive)
    }
}

@Volatile
private var cachedFragmentEntryPoint: FragmentEntryPoint? = null

private fun obtainEntryPoint(context: Context): FragmentEntryPoint? {
    cachedFragmentEntryPoint?.let { return it }
    return try {
        val ep = EntryPointAccessors.fromApplication(
            context.applicationContext,
            FragmentEntryPoint::class.java
        )
        cachedFragmentEntryPoint = ep
        ep
    } catch (_: Exception) {
        null
    }
}

private fun resolveChannelEntity(
    context: Context,
    channelIdStr: String?,
    clanIdStr: String?
): ClanChannelEntity? {
    val cid = channelIdStr?.toLongOrNull() ?: return null
    if (cid == 0L) return null
    val clanId = clanIdStr?.toLongOrNull() ?: 0L
    val entryPoint = obtainEntryPoint(context) ?: return null
    val searchCtl = entryPoint.searchController()
    val entity = entryPoint.channelController().findChannelById(cid, clanId)
        ?: searchCtl.findChannelById(cid)
    if (entity == null && !searchCtl.hasChannels()) {
        searchCtl.loadChannels()
    }
    return entity
}

private fun resolveHashtagIcon(entity: ClanChannelEntity): MezonIcon {
    val type = if (entity.isThread) CHANNEL_TYPE_THREAD else entity.type
    return ChannelItemCell.resolveChannelIcon(type, entity.isPrivate)
}

data class MezonChannelLink(val clanId: String, val channelId: String)

private val MEZON_CHANNEL_URL_REGEX =
    Regex("""/chat/clans/(\d+)/channels/(\d+)(?!/canvas/)""")

fun parseMezonChannelLink(url: String): MezonChannelLink? {
    if (url.isBlank()) return null
    if (!url.contains("/chat/clans/")) return null
    if (url.contains("/canvas/")) return null
    val match = MEZON_CHANNEL_URL_REGEX.find(url) ?: return null
    val clanId = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
    val channelId = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return null
    return MezonChannelLink(clanId, channelId)
}

private val MK_TYPES_EXTERNAL_URL = setOf("lk", "vk", "lk_yt", "lk_fb", "lk_tt")
private val MK_TYPES_URL_WITH_OPTIONAL_SCHEME =
    MK_TYPES_EXTERNAL_URL + setOf("lk_ogp")

private fun urlSchemeLenBeforeHost(text: String, hostRangeStart: Int): Int {
    if (hostRangeStart < 7) return 0
    return when {
        hostRangeStart >= 8 &&
            text.regionMatches(hostRangeStart - 8, "https://", 0, 8, ignoreCase = true) -> 8
        text.regionMatches(hostRangeStart - 7, "http://", 0, 7, ignoreCase = true) -> 7
        else -> 0
    }
}

private fun isLikelyUrlContinuationChar(c: Char): Boolean = when (c) {
    in 'a'..'z', in 'A'..'Z', in '0'..'9' -> true
    '-', '_', '.', '~', '/', '?', '#', '%', '+', '=', '&', ':', '@', '!', '$', '\'', '(', ')', '*', ',', ';', '[', ']' -> true
    else -> false
}

private fun extendHttpUrlRangeExclusive(text: String, urlStart: Int, endExclusive: Int): Int {
    if (urlStart < 0 || urlStart >= text.length) return endExclusive
    val https = text.regionMatches(urlStart, "https://", 0, 8, ignoreCase = true)
    val http = !https && text.regionMatches(urlStart, "http://", 0, 7, ignoreCase = true)
    if (!https && !http) return endExclusive
    var e = maxOf(endExclusive, urlStart).coerceAtMost(text.length)
    while (e < text.length) {
        val c = text[e]
        if (c == '\n' || c == '\r' || c.isWhitespace()) break
        if (!isLikelyUrlContinuationChar(c)) break
        e++
    }
    while (e > urlStart && text[e - 1] in ".,;:!?)]}\\\"") {
        e--
    }
    return e
}

private fun appendHashtagPill(
    sb: SpannableStringBuilder,
    spanStart: Int,
    segText: String,
    channelId: String?,
    clanId: String?,
    view: View?,
    linkColor: Int,
    mentionColors: MentionColors?,
    theme: ThemeColors,
    labelOverride: String?,
    preResolvedEntity: ClanChannelEntity?
) {
    val hashtagColor = mentionColors?.userText ?: linkColor
    val hashtagBg = theme.midnightBlue
    val ctx = view?.context
    val entity = preResolvedEntity ?: ctx?.let { resolveChannelEntity(it, channelId, clanId) }
    val iconDrawable = if (ctx != null && entity != null) {
        resolveHashtagIcon(entity).getDrawable(ctx)
    } else null
    if (iconDrawable != null) {
        val labelText = labelOverride
            ?: if (segText.startsWith("#")) segText.substring(1) else segText
        sb.append("\u200B")
        val iconSpanEnd = sb.length
        val span = ColoredImageSpan(iconDrawable, ColoredImageSpan.ALIGN_CENTER)
        span.setSize(LayoutHelper.dp(14f))
        span.overrideColor = hashtagColor
        span.backgroundColor = hashtagBg
        sb.setExclusiveSpan(span, spanStart, iconSpanEnd)
        sb.append(" ")
        sb.append(labelText)
    } else {
        sb.append(labelOverride?.let { "#$it" } ?: segText)
    }
    sb.setExclusiveSpan(HashtagSpan(channelId, hashtagColor), spanStart, sb.length)
    sb.setExclusiveSpan(StyleSpan(Typeface.BOLD), spanStart, sb.length)
    sb.setExclusiveSpan(BackgroundColorSpan(hashtagBg), spanStart, sb.length)
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

fun getEmojiDirectUrl(emojiId: String): String? {
    if (emojiId.isBlank()) return null
    return "$BASE_IMG/emojis/$emojiId.webp"
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

private const val MESSAGE_COMPONENT_TYPE_SELECT = 2
private const val MESSAGE_COMPONENT_TYPE_INPUT = 3
private const val MESSAGE_COMPONENT_TYPE_RADIO = 5
private const val MESSAGE_COMPONENT_TYPE_ANIMATION = 6

private fun extractSelectValueSelectedStrings(comp: JSONObject): List<String> {
    val out = mutableListOf<String>()
    for (key in listOf("valueSelected", "value_selected")) {
        if (!comp.has(key) || comp.isNull(key)) continue
        when (val raw = comp.get(key)) {
            is String -> if (raw.isNotEmpty()) out.add(raw)
            is JSONObject -> raw.optString("value", "").takeIf { it.isNotEmpty() }?.let { out.add(it) }
            is JSONArray -> {
                for (j in 0 until raw.length()) {
                    when (val el = raw.opt(j)) {
                        is JSONObject -> el.optString("value", "").takeIf { it.isNotEmpty() }?.let { out.add(it) }
                        is String -> if (el.isNotEmpty()) out.add(el)
                        else -> {}
                    }
                }
            }
            else -> {}
        }
        if (out.isNotEmpty()) return out.distinct()
        out.clear()
    }
    return emptyList()
}

data class EmbedField(
    val name: String,
    val value: String,
    val inline: Boolean,
    val interactive: EmbedFieldInteractive? = null,
)

data class EmbedImageRef(val url: String, val width: Int = 0, val height: Int = 0)

data class EmbedData(
    val color: Int,
    val title: String,
    val url: String,
    val authorName: String,
    val authorIconUrl: String,
    val description: String,
    val fields: List<EmbedField>,
    val images: List<EmbedImageRef>,
    val thumbnailUrl: String,
    val footerText: String,
    val footerIconUrl: String,
    val timestamp: String
)

private const val MESSAGE_COMPONENT_TYPE_BUTTON = 1

enum class EmbedButtonStyle(val value: Int) {
    PRIMARY(1),
    SECONDARY(2),
    SUCCESS(3),
    DANGER(4),
    LINK(5);

    companion object {
        fun fromInt(v: Int) = entries.find { it.value == v } ?: PRIMARY
    }
}

data class EmbedComponentButton(
    val componentId: String,
    val label: String,
    val url: String?,
    val style: EmbedButtonStyle,
    val disabled: Boolean,
)

data class EmbedActionRow(val buttons: List<EmbedComponentButton>)

data class EmbedPayload(
    val embeds: List<EmbedData>,
    val actionRows: List<EmbedActionRow>,
)

private val EMPTY_EMBED_PAYLOAD = EmbedPayload(emptyList(), emptyList())

fun parseEmbedPayload(content: String): EmbedPayload {
    if (content.isBlank()) return EMPTY_EMBED_PAYLOAD
    return try {
        val obj = JSONObject(content)
        val embeds = parseEmbedDataListFromObject(obj)
        val rows = parseEmbedActionRowsFromObject(obj)
        if (embeds.isEmpty() && rows.isEmpty()) EMPTY_EMBED_PAYLOAD
        else EmbedPayload(embeds, rows)
    } catch (_: Exception) {
        EMPTY_EMBED_PAYLOAD
    }
}

private fun parseEmbedActionRowsFromObject(obj: JSONObject): List<EmbedActionRow> {
    val rows = obj.optJSONArray("components") ?: return emptyList()
    val out = ArrayList<EmbedActionRow>(rows.length())
    for (r in 0 until rows.length()) {
        val rowObj = rows.optJSONObject(r) ?: continue
        val comps = rowObj.optJSONArray("components") ?: continue
        val buttons = ArrayList<EmbedComponentButton>(comps.length())
        for (c in 0 until comps.length()) {
            val comp = comps.optJSONObject(c) ?: continue
            if (comp.optInt("type", -1) != MESSAGE_COMPONENT_TYPE_BUTTON) continue
            val id = comp.optString("id", "")
            if (id.isEmpty()) continue
            val inner = comp.optJSONObject("component") ?: continue
            val label = inner.optString("label", "")
            if (label.isEmpty()) continue
            val url = inner.optString("url", "").takeIf { it.isNotEmpty() }
            val style = EmbedButtonStyle.fromInt(inner.optInt("style", EmbedButtonStyle.PRIMARY.value))
            val disabled = inner.optBoolean("disable", false) ||
                inner.optBoolean("disabled", false) ||
                inner.optBoolean("isDisabled", false)
            buttons.add(EmbedComponentButton(id, label, url, style, disabled))
        }
        if (buttons.isNotEmpty()) out.add(EmbedActionRow(buttons))
    }
    return out
}

fun parseEmbedActionRows(content: String): List<EmbedActionRow> =
    parseEmbedPayload(content).actionRows

private fun parseDiscordEmbedColor(embed: JSONObject, colorStr: String): Int {
    if (colorStr.isNotEmpty()) {
        if (colorStr.startsWith("#")) {
            try {
                return Color.parseColor(colorStr)
            } catch (_: Exception) {}
        } else {
            try {
                return opaqueRgb(colorStr.toDouble().toInt())
            } catch (_: Exception) {}
        }
    }
    if (!embed.has("color") || embed.isNull("color")) return 0
    return try {
        opaqueRgb(embed.getDouble("color").toInt())
    } catch (_: Exception) {
        0
    }
}

private fun opaqueRgb(raw: Int): Int = (raw and 0x00FFFFFF) or 0xFF000000.toInt()

private fun parseEmbedDataListFromObject(obj: JSONObject): List<EmbedData> {
    val embeds = obj.optJSONArray("embed") ?: return emptyList()
    val out = ArrayList<EmbedData>(embeds.length())
    for (i in 0 until embeds.length()) {
        val embed = embeds.optJSONObject(i) ?: continue
        parseEmbedJsonObject(embed)?.let { out.add(it) }
    }
    return out
}

fun parseEmbedDataList(content: String): List<EmbedData> =
    parseEmbedPayload(content).embeds

fun parseEmbedData(content: String): EmbedData? = parseEmbedDataList(content).firstOrNull()

private fun parseEmbedImages(embed: JSONObject): List<EmbedImageRef> {
    if (!embed.has("image") || embed.isNull("image")) return emptyList()
    return try {
        when (val raw = embed.get("image")) {
            is String ->
                raw.trim().takeIf { it.isNotEmpty() }
                    ?.let { listOf(EmbedImageRef(it, 0, 0)) } ?: emptyList()
            is JSONObject -> {
                val u = raw.optString("url", "").trim()
                if (u.isEmpty()) emptyList()
                else listOf(EmbedImageRef(u, raw.optInt("width", 0), raw.optInt("height", 0)))
            }
            is JSONArray -> {
                val list = mutableListOf<EmbedImageRef>()
                for (i in 0 until raw.length()) {
                    when (val item = raw.opt(i)) {
                        is JSONObject ->
                            item.optString("url", "").trim().takeIf { it.isNotEmpty() }
                                ?.let { list.add(EmbedImageRef(it, item.optInt("width", 0), item.optInt("height", 0))) }
                        is String ->
                            item.trim().takeIf { it.isNotEmpty() }?.let { list.add(EmbedImageRef(it, 0, 0)) }
                        else -> {
                            val s = item?.toString()?.trim().orEmpty()
                            if (s.isNotEmpty() && s != "null") list.add(EmbedImageRef(s, 0, 0))
                        }
                    }
                }
                list
            }
            else -> emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun parseEmbedJsonObject(embed: JSONObject): EmbedData? {
    return try {
        val colorStr = embed.optString("color", "")
        val color = parseDiscordEmbedColor(embed, colorStr)

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
                val interactive = f.optJSONObject("inputs")?.let { inputsObj ->
                    val type = inputsObj.optInt("type", -1)
                    val idRaw = inputsObj.optString("id", "").trim()
                    val idResolved = idRaw.ifEmpty {
                        "__anon_embed_${embed.hashCode()}_f$i"
                    }
                    if (type != MESSAGE_COMPONENT_TYPE_ANIMATION && idRaw.isEmpty()) return@let null
                    when (type) {
                        MESSAGE_COMPONENT_TYPE_SELECT -> {
                            val comp = inputsObj.optJSONObject("component") ?: JSONObject()
                            val opts = mutableListOf<EmbedSelectOptionSpec>()
                            val optionsArr = comp.optJSONArray("options")
                            if (optionsArr != null) {
                                for (oi in 0 until optionsArr.length()) {
                                    val o = optionsArr.optJSONObject(oi) ?: continue
                                    opts.add(
                                        EmbedSelectOptionSpec(
                                            label = o.optString("label", ""),
                                            value = o.optString("value", ""),
                                            defaultSelected = o.optBoolean("default", false),
                                        ),
                                    )
                                }
                            }
                            val minO = comp.optInt("min_options", 0)
                            val hasMaxKey = comp.has("max_options") && !comp.isNull("max_options")
                            val maxO = if (hasMaxKey) comp.optInt("max_options", 1).coerceAtLeast(1) else 1
                            val isMulti = (minO > 1) || (hasMaxKey && maxO >= 2)
                            val initial = mutableListOf<String>()
                            for (o in opts) {
                                if (o.defaultSelected && o.value.isNotEmpty()) initial.add(o.value)
                            }
                            for (vv in extractSelectValueSelectedStrings(comp)) {
                                if (vv.isNotEmpty() && vv !in initial) initial.add(vv)
                            }
                            val disabled = comp.optBoolean("disabled", false) ||
                                comp.optBoolean("disable", false)
                            EmbedFieldInteractive.Select(
                                componentId = idResolved,
                                input = EmbedSelectSpec(
                                    options = opts,
                                    isMulti = isMulti,
                                    minPick = minO,
                                    maxPick = maxO,
                                    disabled = disabled,
                                    initialSelection = initial.distinct(),
                                ),
                            )
                        }
                        MESSAGE_COMPONENT_TYPE_INPUT -> {
                            val comp = inputsObj.optJSONObject("component") ?: JSONObject()
                            val defaultStr = when {
                                comp.has("defaultValue") && !comp.isNull("defaultValue") ->
                                    comp.opt("defaultValue")?.toString() ?: ""
                                comp.has("default_value") && !comp.isNull("default_value") ->
                                    comp.opt("default_value")?.toString() ?: ""
                                else -> ""
                            }
                            val inputTypeName = comp.optString("type", "").lowercase()
                            val isTextarea = comp.optBoolean("textarea", false) ||
                                inputTypeName == "textarea" ||
                                comp.optBoolean("multiline", false)
                            EmbedFieldInteractive.Input(
                                componentId = idResolved,
                                input = EmbedInputComponentSpec(
                                    placeholder = comp.optString("placeholder", ""),
                                    defaultValue = defaultStr,
                                    textarea = isTextarea,
                                    disabled = comp.optBoolean("disabled", false) ||
                                        comp.optBoolean("disable", false),
                                    numberInput = inputTypeName == "number",
                                    dateInput = inputTypeName == "date" ||
                                        inputTypeName == "datetime" ||
                                        inputTypeName == "datetime-local",
                                    required = comp.optBoolean("required", false),
                                ),
                            )
                        }
                        MESSAGE_COMPONENT_TYPE_RADIO -> {
                            val compArr = inputsObj.optJSONArray("component") ?: return@let null
                            val ropts = mutableListOf<EmbedRadioOptionSpec>()
                            for (ri in 0 until compArr.length()) {
                                val o = compArr.optJSONObject(ri) ?: continue
                                val edArr = o.optJSONArray("extraData")
                                val edList = if (edArr != null) {
                                    List(edArr.length()) { ei -> edArr.optString(ei, "") }
                                        .filter { it.isNotEmpty() }
                                } else emptyList()
                                ropts.add(
                                    EmbedRadioOptionSpec(
                                        label = o.optString("label", ""),
                                        description = o.optString("description", ""),
                                        value = o.optString("value", ""),
                                        disabled = o.optBoolean("disabled", false) ||
                                            o.optBoolean("disable", false),
                                        groupName = o.optString("name", ""),
                                        extraData = edList,
                                    ),
                                )
                            }
                            if (ropts.isEmpty()) return@let null
                            val multi = ropts.size > 1 &&
                                ropts[0].groupName != ropts.getOrNull(1)?.groupName
                            val maxOpt = if (inputsObj.has("max_options") && !inputsObj.isNull("max_options")) {
                                inputsObj.optInt("max_options", 0).takeIf { it > 0 }
                            } else null
                            val disabled = inputsObj.optBoolean("disabled", false) ||
                                inputsObj.optBoolean("disable", false)
                            EmbedFieldInteractive.Radio(
                                componentId = idResolved,
                                input = EmbedRadioSpec(
                                    options = ropts,
                                    multi = multi,
                                    maxOptions = maxOpt,
                                    disabled = disabled,
                                ),
                            )
                        }
                        MESSAGE_COMPONENT_TYPE_ANIMATION -> {
                            val comp = inputsObj.optJSONObject("component") ?: JSONObject()
                            val urlImage = comp.optString("url_image", "")
                                .ifEmpty { comp.optString("urlImage", "") }
                            val urlPosition = comp.optString("url_position", "")
                                .ifEmpty { comp.optString("urlPosition", "") }
                            val poolArr = comp.optJSONArray("pool") ?: JSONArray()
                            val pool = mutableListOf<List<String>>()
                            for (pi in 0 until poolArr.length()) {
                                val lane = poolArr.optJSONArray(pi) ?: continue
                                val keys = mutableListOf<String>()
                                for (lj in 0 until lane.length()) {
                                    if (lane.isNull(lj)) continue
                                    val keyStr = lane.get(lj).toString().trim()
                                    if (keyStr.isNotEmpty() && keyStr != "null") keys.add(keyStr)
                                }
                                if (keys.isNotEmpty()) pool.add(keys)
                            }
                            if (urlImage.isEmpty() || urlPosition.isEmpty() || pool.isEmpty()) return@let null
                            val durationSec = comp.optDouble("duration", 2.0).toFloat()
                                .coerceAtLeast(0.1f)
                            val repeat = if (comp.has("repeat") && !comp.isNull("repeat")) {
                                try {
                                    when (val r = comp.get("repeat")) {
                                        is Number -> r.toInt()
                                        is String -> r.toDoubleOrNull()?.toInt()
                                        else -> null
                                    }
                                } catch (_: Exception) {
                                    null
                                }
                            } else null
                            val vertical = comp.optBoolean("vertical", false)
                            val isStaticResult = comp.optInt("isResult", 0) != 0
                            EmbedFieldInteractive.Animation(
                                componentId = idResolved,
                                input = EmbedAnimationSpec(
                                    urlImage = urlImage,
                                    urlPosition = urlPosition,
                                    pool = pool,
                                    durationSec = durationSec,
                                    repeat = repeat,
                                    vertical = vertical,
                                    isStaticResult = isStaticResult,
                                ),
                            )
                        }
                        else -> null
                    }
                }
                fields.add(EmbedField(
                    name = f.optString("name", ""),
                    value = f.optString("value", ""),
                    inline = f.optBoolean("inline", false),
                    interactive = interactive,
                ))
            }
        }

        val images = parseEmbedImages(embed)

        val thumbnailObj = embed.optJSONObject("thumbnail")
        val thumbnailUrl = thumbnailObj?.optString("url", "") ?: ""

        val footerObj = embed.optJSONObject("footer")
        val footerText = footerObj?.optString("text", "") ?: ""
        val footerIconUrl = footerObj?.optString("icon_url", "") ?: ""

        val timestamp = embed.optString("timestamp", "")

        val hasFieldContent = fields.any {
            it.name.isNotEmpty() || it.value.isNotEmpty() || it.interactive != null
        }
        if (title.isEmpty() && description.isEmpty() && !hasFieldContent && images.isEmpty() && thumbnailUrl.isEmpty()) return null

        EmbedData(color, title, url, authorName, authorIconUrl, description, fields,
            images, thumbnailUrl, footerText, footerIconUrl, timestamp)
    } catch (_: Exception) {
        null
    }
}
