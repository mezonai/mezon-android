package com.mezon.mobile.home.messages

import com.mezon.mobile.util.parseContentPreview
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mezon.mezon.api.ChannelDescription
import com.mezon.mobile.home.extractLastSeenMessageId
import com.mezon.mobile.home.extractLastSeenMessageTs
import com.mezon.mobile.home.extractLastSentMessageId
import com.mezon.mobile.home.extractLastSentMessageTs

@Entity(
    tableName = "direct_messages",
    indices = [Index(value = ["lastSentMessageTs"])]
)
data class DirectMessage(
    @PrimaryKey val channelId: Long,
    val type: Int,
    val label: String,
    val avatarUrl: String,
    val displayName: String,
    val lastMessageContent: String,
    val unreadCount: Int,
    val isOnline: Boolean,
    val isMute: Boolean,
    val otherUserId: Long = 0L,
    val lastSeenMessageId: Long = 0L,
    val lastSentMessageId: Long = 0L,
    val lastSeenMessageTs: Long = 0L,
    val lastSentMessageTs: Long = 0L
)


fun ChannelDescription.toDirectMessage(currentUserId: Long): DirectMessage {
    val otherIndex = userIdsList.indexOfFirst { it != currentUserId }
        .takeIf { it >= 0 }
        ?: 0 

    val displayName = displayNamesList.getOrElse(otherIndex) {
        usernamesList.getOrElse(otherIndex) { channelLabel }
    }
    val avatarUrl = if (channelAvatar.isNotEmpty()) {
        channelAvatar
    } else {
        avatarsList.getOrElse(otherIndex) { "" }
    }
    val isOnline = onlinesList.getOrElse(otherIndex) { false }

    val lastMsgContent = if (hasLastSentMessage()) {
        parseContentPreview(lastSentMessage.content)
    } else ""

    val otherUserId = userIdsList.getOrElse(otherIndex) { 0L }

    return DirectMessage(
        channelId = channelId,
        type = type,
        label = channelLabel.ifEmpty { displayName },
        avatarUrl = avatarUrl,
        displayName = displayName,
        lastMessageContent = lastMsgContent,
        unreadCount = countMessUnread,
        isOnline = isOnline,
        isMute = isMute,
        otherUserId = otherUserId,
        lastSeenMessageId = extractLastSeenMessageId(),
        lastSentMessageId = extractLastSentMessageId(),
        lastSeenMessageTs = extractLastSeenMessageTs(),
        lastSentMessageTs = extractLastSentMessageTs()
    )
}

