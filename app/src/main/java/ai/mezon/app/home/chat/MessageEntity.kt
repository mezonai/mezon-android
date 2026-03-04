package ai.mezon.app.home.chat

import androidx.room.Entity
import androidx.room.Index
import com.mezon.mezon.api.ChannelMessage

@Entity(
    tableName = "messages",
    primaryKeys = ["channelId", "id"],
    indices = [Index(value = ["channelId", "timestampSeconds"])]
)
data class MessageEntity(
    val id: Long,
    val channelId: Long,
    val senderId: Long,
    val senderName: String,
    val senderAvatar: String,
    val content: String,
    val timestampSeconds: Long,
    val code: Int,
    val isMe: Boolean = false
)

fun ChannelMessage.toMessageEntity(currentUserId: Long): MessageEntity = MessageEntity(
    id = messageId,
    channelId = channelId,
    senderId = senderId,
    senderName = displayName.ifBlank { username },
    senderAvatar = avatar,
    content = content,
    timestampSeconds = createTimeSeconds.toLong(),
    code = code,
    isMe = senderId == currentUserId
)
