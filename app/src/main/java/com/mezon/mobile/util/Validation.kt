package com.mezon.mobile.util

import java.util.regex.Pattern

const val CLAN_OVERVIEW_NAME_MAX_LENGTH = 64
private val CLAN_OVERVIEW_NAME_PATTERN = Pattern.compile(
    "^(?![_\\-\\s])[\\p{L}\\p{N}_\\-\\s]{1,${CLAN_OVERVIEW_NAME_MAX_LENGTH}}\$",
)

fun isClanNameValid(name: String): Boolean =
    CLAN_OVERVIEW_NAME_PATTERN.matcher(name.trim()).matches()

const val CLAN_EMOJI_NAME_MIN_LENGTH = 3
const val CLAN_EMOJI_NAME_MAX_LENGTH = 24
private val CLAN_EMOJI_NAME_PATTERN = Pattern.compile(
    "^[a-zA-Z0-9_-]+$",
)

fun isClanEmojiNameValid(name: String): Boolean =
    CLAN_EMOJI_NAME_PATTERN.matcher(name.trim()).matches()