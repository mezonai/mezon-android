package com.mezon.mobile.home.chat

internal object CreateThreadSeedStash {

    var pendingSeedMessage: MessageEntity? = null

    fun takeSeedMessage(expectedId: Long): MessageEntity? {
        val msg = pendingSeedMessage
        pendingSeedMessage = null
        return msg?.takeIf { it.id == expectedId }
    }
}
