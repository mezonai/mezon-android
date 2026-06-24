package com.mezon.mobile.home.chat.input

import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.chat.EmojiItem
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.clans.ClanRole
import java.text.Normalizer

object InputSuggestionsController {

    enum class Mode { NONE, MENTION, HASHTAG, EMOJI }

    data class TriggerState(
        val mode: Mode,
        val triggerPos: Int,
        val queryLen: Int,
        val keyword: String
    ) {
        companion object {
            val NONE = TriggerState(Mode.NONE, -1, 0, "")
        }
    }

    fun detect(text: CharSequence, cursor: Int): TriggerState {
        if (cursor <= 0 || cursor > text.length) return TriggerState.NONE

        for (a in (cursor - 1) downTo 0) {
            val ch = text[a]
            when (ch) {
                '@' -> {
                    if (a == 0 || isTriggerBoundary(text[a - 1])) {
                        val keyword = text.substring(a + 1, cursor)
                        return TriggerState(Mode.MENTION, a, keyword.length + 1, keyword)
                    }
                    return TriggerState.NONE
                }
                '#' -> {
                    if (a == 0 || isTriggerBoundary(text[a - 1])) {
                        val keyword = text.substring(a + 1, cursor)
                        if (keyword.any { it == ' ' || it == '\n' }) return TriggerState.NONE
                        return TriggerState(Mode.HASHTAG, a, keyword.length + 1, keyword)
                    }
                    return TriggerState.NONE
                }
                ':' -> {
                    if (a == 0 || isTriggerBoundary(text[a - 1])) {
                        val keyword = text.substring(a + 1, cursor)
                        if (keyword.isEmpty()) return TriggerState.NONE
                        if (keyword.any { it == ' ' || it == '\n' || it == ':' }) return TriggerState.NONE
                        return TriggerState(Mode.EMOJI, a, keyword.length + 1, keyword)
                    }
                    return TriggerState.NONE
                }
                '\n' -> return TriggerState.NONE
                ' ' -> { /* keep scanning for `@` with Infinity spaces allowed */ }
            }
        }
        return TriggerState.NONE
    }

    private fun isTriggerBoundary(ch: Char): Boolean =
        ch == ' ' || ch == '\n' || ch == '\t'

    fun removeDiacritics(input: String): String {
        if (input.isEmpty()) return input
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
        val sb = StringBuilder(normalized.length)
        for (ch in normalized) {
            val type = Character.getType(ch).toByte()
            if (type != Character.NON_SPACING_MARK) sb.append(ch)
        }
        return sb.toString().replace('đ', 'd').replace('Đ', 'D')
    }

    data class MentionContext(
        val members: List<ClanMember>,
        val roles: List<ClanRole>,
        val includeHere: Boolean,
        val includeRoles: Boolean
    )

    fun buildMentionItems(keyword: String, ctx: MentionContext): List<InputSuggestionItem> {
        val search = keyword.trim()
        val sLower = search.lowercase()
        val sNorm = removeDiacritics(sLower)

        data class Scored(val item: InputSuggestionItem, val score: Int, val length: Int, val label: String)
        val results = ArrayList<Scored>()

        if (ctx.includeHere) {
            val label = "here"
            val score = scoreText(label, "", sLower, sNorm)
            if (search.isEmpty() || score > 0) {
                results.add(Scored(InputSuggestionItem.Here, if (search.isEmpty()) 2000 else score, label.length, label))
            }
        }

        for (member in ctx.members) {
            val display = member.clanNick.ifBlank { member.displayName.ifBlank { member.username } }
            val score = if (search.isEmpty()) 1000
            else scoreText(display, member.username, sLower, sNorm)
            if (score > 0) {
                results.add(Scored(InputSuggestionItem.Member(member), score, display.length, display))
            }
        }

        if (ctx.includeRoles) {
            for (role in ctx.roles) {
                val score = if (search.isEmpty()) 1000
                else scoreText(role.title, "", sLower, sNorm)
                if (score > 0) {
                    results.add(Scored(InputSuggestionItem.Role(role), score, role.title.length, role.title))
                }
            }
        }

        results.sortWith(compareByDescending<Scored> { it.score }.thenBy { it.length }.thenBy { it.label })
        return results.map { it.item }
    }

    fun buildChannelItems(
        keyword: String,
        channels: List<ClanChannelEntity>
    ): List<InputSuggestionItem> {
        val sLower = keyword.trim().lowercase()
        val filtered = if (sLower.isEmpty()) {
            channels
        } else {
            channels.filter { it.channelLabel.lowercase().contains(sLower) }
        }
        return filtered.map {
            InputSuggestionItem.Channel(
                entity = it,
                subText = it.categoryName
            )
        }
    }

    fun buildEmojiItems(keyword: String, emojis: List<EmojiItem>): List<InputSuggestionItem> {
        val sLower = keyword.trim().lowercase()
        val filtered = emojis.asSequence()
            .filter { it.shortname.lowercase().contains(sLower) }
            .distinctBy { it.id }
            .take(20)
            .toList()
        return filtered.map { InputSuggestionItem.Emoji(it) }
    }

    private fun scoreText(display: String, username: String, sLower: String, sNorm: String): Int {
        if (sLower.isEmpty()) return 0
        val displayLower = display.lowercase()
        val usernameLower = username.lowercase()
        val displayNorm = removeDiacritics(displayLower)
        val usernameNorm = removeDiacritics(usernameLower)
        return when {
            displayLower == sLower || usernameLower == sLower -> 2000
            displayLower.startsWith(sLower) || usernameLower.startsWith(sLower) -> 1900
            displayLower.contains(sLower) || usernameLower.contains(sLower) -> 1500
            displayNorm == sNorm || usernameNorm == sNorm -> 1000
            displayNorm.startsWith(sNorm) || usernameNorm.startsWith(sNorm) -> 900
            displayNorm.contains(sNorm) || usernameNorm.contains(sNorm) -> 500
            else -> 0
        }
    }
}
