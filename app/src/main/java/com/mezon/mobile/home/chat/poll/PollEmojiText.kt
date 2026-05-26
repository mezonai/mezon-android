package com.mezon.mobile.home.chat.poll

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.View
import com.mezon.mobile.home.chat.EmojiSpan
import java.lang.ref.WeakReference

/** Matches web `POLL_EMOJI_REGEX` / `renderPollTextWithEmoji`. */
val POLL_EMOJI_REGEX = Regex("""\[e:([^\]]+)\]""")

sealed class PollAnswerPart {
    data class Text(val value: String) : PollAnswerPart()
    data class Emoji(val id: String) : PollAnswerPart()
}

fun parsePollAnswerDisplay(raw: String): List<PollAnswerPart> {
    if (raw.isEmpty()) return emptyList()
    val parts = mutableListOf<PollAnswerPart>()
    var lastIndex = 0
    for (match in POLL_EMOJI_REGEX.findAll(raw)) {
        val start = match.range.first
        if (start > lastIndex) {
            parts.add(PollAnswerPart.Text(raw.substring(lastIndex, start)))
        }
        parts.add(PollAnswerPart.Emoji(match.groupValues[1]))
        lastIndex = match.range.last + 1
    }
    if (lastIndex < raw.length) {
        parts.add(PollAnswerPart.Text(raw.substring(lastIndex)))
    }
    return parts
}

/** Plain text only (e.g. poll question — web has no question emoji UI). */
fun pollAnswerPlainText(raw: String): String =
    parsePollAnswerDisplay(raw)
        .mapNotNull { (it as? PollAnswerPart.Text)?.value }
        .joinToString("")
        .trim()

/**
 * Builds inline label with [EmojiSpan] for poll answer rows / detail list.
 * @param hostView view to invalidate when emoji bitmap loads ([ChatMessageCell]).
 */
fun buildPollAnswerSpannable(raw: String, hostView: View): CharSequence {
    val parts = parsePollAnswerDisplay(raw)
    if (parts.isEmpty()) return ""
    val sb = SpannableStringBuilder()
    val viewRef = WeakReference(hostView)
    for (part in parts) {
        when (part) {
            is PollAnswerPart.Text -> sb.append(part.value)
            is PollAnswerPart.Emoji -> {
                val start = sb.length
                sb.append("\u200B")
                sb.setSpan(
                    EmojiSpan(part.id, viewRef),
                    start,
                    start + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }
    return sb
}
