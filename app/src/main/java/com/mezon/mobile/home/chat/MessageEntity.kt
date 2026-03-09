package com.mezon.mobile.home.chat

import androidx.room.Entity
import androidx.room.Index
import com.mezon.mezon.api.ChannelMessage
import com.mezon.mezon.api.MessageAttachmentList

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
    val isMe: Boolean = false,
    val messageType: Int = TYPE_TEXT,
    val attachmentUrl: String = "",
    val attachmentThumb: String = "",
    val attachmentWidth: Int = 0,
    val attachmentHeight: Int = 0,
    val attachmentFilename: String = "",
    val attachmentFiletype: String = "",
    val attachmentSize: Int = 0,
    val attachmentDuration: Int = 0
) {
    companion object {
        const val TYPE_TEXT = 0
        const val TYPE_PHOTO = 1
        const val TYPE_VIDEO = 2
        const val TYPE_GIF = 3
        const val TYPE_FILE = 4
    }

    val hasMedia: Boolean
        get() = messageType == TYPE_PHOTO || messageType == TYPE_VIDEO || messageType == TYPE_GIF
}

fun ChannelMessage.toMessageEntity(currentUserId: Long): MessageEntity {
    val attachment = parseFirstAttachment(attachments)
    val type = resolveMessageType(attachment)

    return MessageEntity(
        id = messageId,
        channelId = channelId,
        senderId = senderId,
        senderName = displayName.ifBlank { username },
        senderAvatar = avatar,
        content = content,
        timestampSeconds = createTimeSeconds.toLong(),
        code = code,
        isMe = senderId == currentUserId,
        messageType = type,
        attachmentUrl = attachment?.url.orEmpty(),
        attachmentThumb = attachment?.thumbnail.orEmpty(),
        attachmentWidth = attachment?.width ?: 0,
        attachmentHeight = attachment?.height ?: 0,
        attachmentFilename = attachment?.filename.orEmpty(),
        attachmentFiletype = attachment?.filetype.orEmpty(),
        attachmentSize = attachment?.size ?: 0,
        attachmentDuration = attachment?.duration ?: 0
    )
}

private data class ParsedAttachment(
    val url: String,
    val filename: String,
    val filetype: String,
    val width: Int,
    val height: Int,
    val thumbnail: String,
    val size: Int,
    val duration: Int
)

private fun parseFirstAttachment(bytes: com.google.protobuf.ByteString): ParsedAttachment? {
    if (bytes.isEmpty) return null
    return try {
        val list = MessageAttachmentList.parseFrom(bytes)
        if (list.attachmentsCount == 0) return null
        val a = list.getAttachments(0)
        ParsedAttachment(
            url = a.url,
            filename = a.filename,
            filetype = a.filetype,
            width = a.width,
            height = a.height,
            thumbnail = a.thumbnail,
            size = a.size,
            duration = a.duration
        )
    } catch (_: Exception) {
        null
    }
}

private fun resolveMessageType(attachment: ParsedAttachment?): Int {
    if (attachment == null || attachment.url.isEmpty()) return MessageEntity.TYPE_TEXT
    val ft = attachment.filetype.lowercase()
    return when {
        ft.startsWith("image/gif") -> MessageEntity.TYPE_GIF
        ft.startsWith("image/") -> MessageEntity.TYPE_PHOTO
        ft.startsWith("video/") -> MessageEntity.TYPE_VIDEO
        else -> MessageEntity.TYPE_FILE
    }
}
