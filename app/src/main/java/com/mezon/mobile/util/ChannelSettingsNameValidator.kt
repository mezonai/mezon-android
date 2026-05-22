package com.mezon.mobile.util

object ChannelSettingsNameValidator {

    const val TYPE_CHANNEL = 2
    const val TYPE_THREAD = 3
    const val TOPIC_MAX_LENGTH = 1024

    fun isValidName(trimmed: String): Boolean = CreateChannelNameValidator.isValid(trimmed)
}
