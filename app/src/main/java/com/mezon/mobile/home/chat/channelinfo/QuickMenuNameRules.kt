package com.mezon.mobile.home.chat.channelinfo

import com.mezon.mezon.api.QuickMenuAccess

object QuickMenuNameRules {
    const val MAX_NAME_CODE_POINTS = 64
    const val MAX_ACTION_MSG_BYTES = 512

    private val allowedCharacterTypes: Set<Int> = setOf(
        Character.UPPERCASE_LETTER,
        Character.LOWERCASE_LETTER,
        Character.TITLECASE_LETTER,
        Character.MODIFIER_LETTER,
        Character.OTHER_LETTER,
        Character.DECIMAL_DIGIT_NUMBER,
        Character.LETTER_NUMBER,
        Character.OTHER_NUMBER,
        Character.OTHER_SYMBOL,
    ).map { it.toInt() }.toSet()

    private val allowedPunctuation: Set<Int> = setOf('_'.code, ' '.code, '-'.code, '.'.code, '+'.code)

    private val emojiRanges: List<IntRange> = listOf(
        0x1F600..0x1F64F,
        0x1F300..0x1F5FF,
        0x1F680..0x1F6FF,
        0x1F700..0x1F77F,
        0x1F780..0x1F7FF,
        0x1F800..0x1F8FF,
        0x1F900..0x1F9FF,
        0x1FA00..0x1FA6F,
        0x1FA70..0x1FAFF,
    )

    fun isValidName(name: String): Boolean {
        val points = name.codePoints().toArray()
        if (points.isEmpty() || points.size > MAX_NAME_CODE_POINTS) return false
        if (points[0] == '_'.code || points[0] == '-'.code) return false
        return points.all(::isAllowedNameCodePoint)
    }

    fun isValidActionMsg(actionMsg: String): Boolean =
        actionMsg.isNotEmpty() && actionMsg.toByteArray(Charsets.UTF_8).size <= MAX_ACTION_MSG_BYTES

    fun nameExists(name: String, items: List<QuickMenuAccess>, excludingId: Long?): Boolean =
        items.any { it.menuName == name && (excludingId == null || it.id != excludingId) }

    private fun isAllowedNameCodePoint(codePoint: Int): Boolean {
        if (emojiRanges.any { codePoint in it }) return true
        if (codePoint in allowedPunctuation) return true
        return Character.getType(codePoint) in allowedCharacterTypes
    }
}
