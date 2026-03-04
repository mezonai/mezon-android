package ai.mezon.app.home.messages

import ai.mezon.app.util.parseContentPreview
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mezon.mezon.api.ChannelDescription

@Entity(tableName = "direct_messages")
data class DirectMessage(
    @PrimaryKey val channelId: Long,
    val type: Int,
    val label: String,
    val avatarUrl: String,
    val displayName: String,
    val lastMessageContent: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int,
    val isOnline: Boolean,
    val isMute: Boolean
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

    val lastMsgTimestamp = if (hasLastSentMessage()) {
        lastSentMessage.timestampSeconds.toLong()
    } else {
        createTimeSeconds.toLong()
    }

    return DirectMessage(
        channelId = channelId,
        type = type,
        label = channelLabel.ifEmpty { displayName },
        avatarUrl = avatarUrl,
        displayName = displayName,
        lastMessageContent = lastMsgContent,
        lastMessageTimestamp = lastMsgTimestamp,
        unreadCount = countMessUnread,
        isOnline = isOnline,
        isMute = isMute
    )
}

internal fun parseMessagePreview(content: String): String = parseContentPreview(content)
