package com.mezon.mobile.home.chat

import androidx.room.Entity
import androidx.room.Index
import com.mezon.mezon.api.ChannelMessage
import com.mezon.mezon.api.MessageAttachmentList
import com.mezon.mezon.api.MessageMentionList
import org.json.JSONArray
import org.json.JSONObject

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
    val attachmentDuration: Int = 0,
    val updateTimeSeconds: Long = 0L,
    val hideEditted: Boolean = true,
    val isForwarded: Boolean = false,
    val isError: Boolean = false
) {
    companion object {
        const val TYPE_TEXT = 0
        const val TYPE_PHOTO = 1
        const val TYPE_VIDEO = 2
        const val TYPE_GIF = 3
        const val TYPE_FILE = 4

        const val CODE_CHAT = 0
        const val CODE_CHAT_UPDATE = 1
        const val CODE_CHAT_REMOVE = 2
        const val CODE_TYPING = 3
        const val CODE_INDICATOR = 4
        const val CODE_WELCOME = 5
        const val CODE_CREATE_THREAD = 6
        const val CODE_CREATE_PIN = 7
        const val CODE_MESSAGE_BUZZ = 8
        const val CODE_TOPIC = 9
        const val CODE_AUDIT_LOG = 10
        const val CODE_SEND_TOKEN = 11
        const val CODE_EPHEMERAL = 12
        const val CODE_UPCOMING_EVENT = 13
        const val CODE_UPDATE_EPHEMERAL = 14
        const val CODE_DELETE_EPHEMERAL = 15
        const val CODE_SHARE_CONTACT = 16
        const val CODE_LOCATION = 17
    }

    val hasMedia: Boolean
        get() = messageType == TYPE_PHOTO || messageType == TYPE_VIDEO || messageType == TYPE_GIF

    val isSystemMessage: Boolean
        get() = code == CODE_WELCOME || code == CODE_CREATE_THREAD || code == CODE_CREATE_PIN
                || code == CODE_AUDIT_LOG || code == CODE_UPCOMING_EVENT

    val isNormalMessage: Boolean
        get() = code == CODE_CHAT || code == CODE_CHAT_UPDATE || code == CODE_MESSAGE_BUZZ
                || code == CODE_TOPIC || code == CODE_SEND_TOKEN || code == CODE_EPHEMERAL
                || code == CODE_SHARE_CONTACT || code == CODE_LOCATION

    val isRenderable: Boolean
        get() = isNormalMessage || isSystemMessage

    val isEdited: Boolean
        get() = updateTimeSeconds > 0 && updateTimeSeconds > timestampSeconds && !isError

    val isEphemeral: Boolean
        get() = code == CODE_EPHEMERAL || code == CODE_UPDATE_EPHEMERAL

    val isFileAttachment: Boolean
        get() = messageType == TYPE_FILE && attachmentUrl.isNotEmpty()
}

fun ChannelMessage.toMessageEntity(currentUserId: Long): MessageEntity {
    val attachment = parseFirstAttachment(attachments)
    val type = resolveMessageType(attachment)
    val forwarded = content.contains("\"fwd\"") && content.contains("true")
    val mergedContent = mergeMentionsIntoContent(content, mentions)

    return MessageEntity(
        id = messageId,
        channelId = channelId,
        senderId = senderId,
        senderName = displayName.ifBlank { username },
        senderAvatar = avatar,
        content = mergedContent,
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
        attachmentDuration = attachment?.duration ?: 0,
        updateTimeSeconds = updateTimeSeconds.toLong(),
        hideEditted = hideEditted,
        isForwarded = forwarded
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

private fun mergeMentionsIntoContent(content: String, mentionsBytes: com.google.protobuf.ByteString): String {
    if (mentionsBytes.isEmpty) return content
    return try {
        val list = MessageMentionList.parseFrom(mentionsBytes)
        if (list.mentionsCount == 0) return content
        val obj = try { JSONObject(content) } catch (_: Exception) { return content }
        if (obj.has("mentions")) return content
        val arr = JSONArray()
        for (m in list.mentionsList) {
            val item = JSONObject()
            item.put("s", m.s)
            item.put("e", m.e)
            if (m.userId != 0L) item.put("user_id", m.userId.toString())
            if (m.roleId != 0L) item.put("role_id", m.roleId.toString())
            if (m.username.isNotEmpty()) item.put("username", m.username)
            arr.put(item)
        }
        obj.put("mentions", arr)
        obj.toString()
    } catch (_: Exception) {
        content
    }
}

private fun resolveMessageType(attachment: ParsedAttachment?): Int {
    if (attachment == null || attachment.url.isEmpty()) return MessageEntity.TYPE_TEXT
    val ft = attachment.filetype.lowercase()
    val url = attachment.url.lowercase()
    return when {
        ft.startsWith("image/gif") -> MessageEntity.TYPE_GIF
        ft == "sticker" -> MessageEntity.TYPE_GIF
        url.contains("tenor.com") -> MessageEntity.TYPE_GIF
        url.contains("/stickers/") -> MessageEntity.TYPE_GIF
        ft.startsWith("image/") -> MessageEntity.TYPE_PHOTO
        ft.startsWith("video/") -> MessageEntity.TYPE_VIDEO
        else -> MessageEntity.TYPE_FILE
    }
}
