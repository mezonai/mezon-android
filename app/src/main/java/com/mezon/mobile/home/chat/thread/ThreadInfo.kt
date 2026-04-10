package com.mezon.mobile.home.chat.thread

import com.mezon.mezon.api.ChannelDescription
import com.mezon.mobile.util.parseContentPreview

data class ThreadInfo(
    val channelId: Long,
    val clanId: Long,
    val parentId: Long,
    val channelLabel: String,
    val active: Int,
    val isPrivate: Boolean,
    val lastSenderId: Long,
    val lastMessageContent: String,
    val lastMessageTs: Long
)

fun ChannelDescription.toThreadInfo(): ThreadInfo {
    val msgContent = if (hasLastSentMessage()) {
        parseContentPreview(lastSentMessage.content)
    } else ""
    val senderId = if (hasLastSentMessage()) lastSentMessage.senderId else 0L
    val msgTs = if (hasLastSentMessage()) lastSentMessage.timestampSeconds.toLong() else 0L
    return ThreadInfo(
        channelId = channelId,
        clanId = clanId,
        parentId = parentId,
        channelLabel = channelLabel,
        active = active,
        isPrivate = channelPrivate != 0,
        lastSenderId = senderId,
        lastMessageContent = msgContent,
        lastMessageTs = msgTs
    )
}
