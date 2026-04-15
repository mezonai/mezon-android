package com.mezon.mobile.home.chat

import androidx.room.Entity
import androidx.room.Index
import com.mezon.mezon.api.ChannelMessage
import com.mezon.mezon.api.MessageAttachmentList
import com.mezon.mezon.api.MessageMentionList
import com.mezon.mezon.api.MessageReactionList
import com.mezon.mezon.api.MessageRefList
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import com.mezon.mobile.util.MENTION_HERE_USER_ID

data class AttachmentInfo(
    val url: String,
    val thumb: String,
    val width: Int,
    val height: Int,
    val filename: String,
    val filetype: String,
    val size: Int,
    val duration: Int
)

data class ReactionSender(val senderId: Long, val count: Int)

data class ReactionGroup(
    val emojiId: Long,
    val emoji: String,
    val senders: List<ReactionSender>,
    val totalCount: Int
)

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
    val isError: Boolean = false,
    val extraAttachmentsJson: String = "",
    val sendState: Int = SEND_STATE_SENT,
    val reactionsJson: String = ""
) {
    companion object {
        const val UNREAD_DIVIDER_ID = Long.MIN_VALUE

        const val SEND_STATE_SENT = 0
        const val SEND_STATE_SENDING = 1
        const val SEND_STATE_ERROR = 2

        const val TYPE_TEXT = 0
        const val TYPE_PHOTO = 1
        const val TYPE_VIDEO = 2
        const val TYPE_GIF = 3
        const val TYPE_FILE = 4

        const val CODE_CHAT = 0
        const val CODE_CHAT_UPDATE = 1
        const val CODE_CHAT_REMOVE = 2
        const val CODE_TYPING = 3
        const val CODE_FIRST_MESSAGE = 4
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

    val isUnreadDivider: Boolean
        get() = id == UNREAD_DIVIDER_ID

    val hasMedia: Boolean
        get() = messageType == TYPE_PHOTO || messageType == TYPE_VIDEO || messageType == TYPE_GIF

    val isWelcomeMessage: Boolean
        get() = code == CODE_FIRST_MESSAGE || (
            code == CODE_WELCOME &&
            senderId == 0L &&
            senderName.equals("system", ignoreCase = true) &&
            (content.isBlank() || content == "{}" || content.contains("\"t\":\"\"") || !content.contains("\"t\""))
        )

    val isSystemMessage: Boolean
        get() = isWelcomeMessage || code == CODE_WELCOME || code == CODE_CREATE_THREAD
                || code == CODE_CREATE_PIN || code == CODE_AUDIT_LOG || code == CODE_UPCOMING_EVENT

    val isNormalMessage: Boolean
        get() = code == CODE_CHAT || code == CODE_CHAT_UPDATE || code == CODE_MESSAGE_BUZZ
                || code == CODE_TOPIC || code == CODE_SEND_TOKEN || code == CODE_EPHEMERAL
                || code == CODE_SHARE_CONTACT || code == CODE_LOCATION

    val isRenderable: Boolean
        get() = isNormalMessage || isSystemMessage

    val isSending: Boolean
        get() = sendState == SEND_STATE_SENDING

    val isEdited: Boolean
        get() = updateTimeSeconds > 0 && updateTimeSeconds > timestampSeconds && !isError

    val isEphemeral: Boolean
        get() = code == CODE_EPHEMERAL || code == CODE_UPDATE_EPHEMERAL

    val isFileAttachment: Boolean
        get() = messageType == TYPE_FILE && attachmentUrl.isNotEmpty()

    val hasReactions: Boolean
        get() = reactionsJson.isNotEmpty() && reactionsJson != "[]"

    fun combineReactions(): List<ReactionGroup> {
        if (reactionsJson.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(reactionsJson)
            val grouped = LinkedHashMap<Long, MutableList<ReactionSender>>()
            val emojiNames = HashMap<Long, String>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val emojiId = obj.optLong("emoji_id", 0L)
                val count = obj.optInt("count", 0)
                if (emojiId == 0L || count < 1) continue
                emojiNames.putIfAbsent(emojiId, obj.optString("emoji", ""))
                val senderId = obj.optLong("sender_id", 0L)
                val senders = grouped.getOrPut(emojiId) { mutableListOf() }
                val existing = senders.indexOfFirst { it.senderId == senderId }
                if (existing >= 0) {
                    senders[existing] = ReactionSender(senderId, count)
                } else {
                    senders.add(ReactionSender(senderId, count))
                }
            }
            grouped.mapNotNull { (emojiId, senders) ->
                val total = senders.sumOf { it.count }
                if (total <= 0) return@mapNotNull null
                ReactionGroup(emojiId, emojiNames[emojiId].orEmpty(), senders, total)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun hasMention(userId: String): Boolean {
        if (content.isBlank()) return false
        return try {
            val obj = JSONObject(content)
            val mentions = obj.optJSONArray("mentions") ?: return false
            for (i in 0 until mentions.length()) {
                val item = mentions.getJSONObject(i)
                if (item.optString("user_id") == userId) return true
                if (item.optString("user_id") == "here") return true
                if (item.optString("user_id") == MENTION_HERE_USER_ID) return true
            }
            false
        } catch (_: Exception) { false }
    }

    val extraAttachments: List<AttachmentInfo>
        get() {
            if (extraAttachmentsJson.isEmpty()) return emptyList()
            return try {
                val arr = JSONArray(extraAttachmentsJson)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    AttachmentInfo(
                        url = obj.optString("url"),
                        thumb = obj.optString("thumb"),
                        width = obj.optInt("width"),
                        height = obj.optInt("height"),
                        filename = obj.optString("filename"),
                        filetype = obj.optString("filetype"),
                        size = obj.optInt("size"),
                        duration = obj.optInt("duration")
                    )
                }
            } catch (_: Exception) { emptyList() }
        }

    val allImageAttachments: List<AttachmentInfo>
        get() {
            val first = if (attachmentUrl.isNotEmpty() && hasMedia)
                listOf(AttachmentInfo(attachmentUrl, attachmentThumb, attachmentWidth, attachmentHeight,
                    attachmentFilename, attachmentFiletype, attachmentSize, attachmentDuration))
            else emptyList()
            return first + extraAttachments.filter {
                it.filetype.startsWith("image/") || it.filetype.startsWith("video/") ||
                    it.filetype.contains("gif", true) || it.url.contains("tenor.com", true)
            }
        }

    fun buildAttachmentJson(): String {
        if (attachmentUrl.isEmpty()) return ""
        val arr = JSONArray()
        val first = JSONObject()
        first.put("url", attachmentUrl)
        first.put("filename", attachmentFilename)
        first.put("filetype", attachmentFiletype)
        first.put("width", attachmentWidth)
        first.put("height", attachmentHeight)
        first.put("thumbnail", attachmentThumb)
        first.put("size", attachmentSize)
        first.put("duration", attachmentDuration)
        arr.put(first)
        for (extra in extraAttachments) {
            val obj = JSONObject()
            obj.put("url", extra.url)
            obj.put("filename", extra.filename)
            obj.put("filetype", extra.filetype)
            obj.put("width", extra.width)
            obj.put("height", extra.height)
            obj.put("thumbnail", extra.thumb)
            obj.put("size", extra.size)
            obj.put("duration", extra.duration)
            arr.put(obj)
        }
        return arr.toString()
    }
}

fun ChannelMessage.toMessageEntity(currentUserId: Long): MessageEntity {
    val allAttachments = parseAllAttachments(attachments)
    val firstAttachment = allAttachments.firstOrNull()
    val type = resolveMessageType(firstAttachment)
    val forwarded = content.contains("\"fwd\"") && content.contains("true")
    val withMentions = mergeMentionsIntoContent(content, mentions)
    val mergedContent = mergeReferencesIntoContent(withMentions, references)

    val extraJson = if (allAttachments.size > 1) {
        val arr = JSONArray()
        for (i in 1 until allAttachments.size) {
            val a = allAttachments[i]
            val obj = JSONObject()
            obj.put("url", a.url)
            obj.put("thumb", a.thumbnail)
            obj.put("width", a.width)
            obj.put("height", a.height)
            obj.put("filename", a.filename)
            obj.put("filetype", a.filetype)
            obj.put("size", a.size)
            obj.put("duration", a.duration)
            arr.put(obj)
        }
        arr.toString()
    } else ""

    val reactionsJson = parseReactionsToJson(reactions)

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
        attachmentUrl = firstAttachment?.url.orEmpty(),
        attachmentThumb = firstAttachment?.thumbnail.orEmpty(),
        attachmentWidth = firstAttachment?.width ?: 0,
        attachmentHeight = firstAttachment?.height ?: 0,
        attachmentFilename = firstAttachment?.filename.orEmpty(),
        attachmentFiletype = firstAttachment?.filetype.orEmpty(),
        attachmentSize = firstAttachment?.size ?: 0,
        attachmentDuration = firstAttachment?.duration ?: 0,
        updateTimeSeconds = updateTimeSeconds.toLong(),
        hideEditted = hideEditted,
        isForwarded = forwarded,
        extraAttachmentsJson = extraJson,
        reactionsJson = reactionsJson
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

private fun parseAllAttachments(bytes: com.google.protobuf.ByteString): List<ParsedAttachment> {
    if (bytes.isEmpty) return emptyList()
    return try {
        val list = MessageAttachmentList.parseFrom(bytes)
        if (list.attachmentsCount == 0) return emptyList()
        list.attachmentsList.map { a ->
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
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun mergeMentionsIntoContent(content: String, mentionsBytes: com.google.protobuf.ByteString): String {
    if (mentionsBytes.isEmpty) return content
    return try {
        val list = MessageMentionList.parseFrom(mentionsBytes)
        if (list.mentionsCount == 0) return content
        if (content.contains("\"mentions\"")) return content
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
        val lastBrace = content.lastIndexOf('}')
        if (lastBrace < 0) return content
        content.substring(0, lastBrace) + ",\"mentions\":" + arr.toString() + "}"
    } catch (_: Exception) {
        content
    }
}

private fun mergeReferencesIntoContent(content: String, referencesBytes: com.google.protobuf.ByteString): String {
    if (referencesBytes.isEmpty) return content
    return try {
        val list = MessageRefList.parseFrom(referencesBytes)
        if (list.refsCount == 0) return content
        if (content.contains("\"references\"")) return content
        val arr = JSONArray()
        for (ref in list.refsList) {
            val item = JSONObject()
            item.put("message_id", ref.messageId.toString())
            item.put("message_ref_id", ref.messageRefId.toString())
            item.put("ref_type", ref.refType)
            item.put("message_sender_id", ref.messageSenderId.toString())
            item.put("message_sender_username", ref.messageSenderUsername)
            item.put("mesages_sender_avatar", ref.messageSenderAvatar)
            item.put("message_sender_clan_nick", ref.messageSenderClanNick)
            item.put("message_sender_display_name", ref.messageSenderDisplayName)
            item.put("content", ref.content)
            item.put("has_attachment", ref.hasAttachment)
            arr.put(item)
        }
        val lastBrace = content.lastIndexOf('}')
        if (lastBrace < 0) return content
        content.substring(0, lastBrace) + ",\"references\":" + arr.toString() + "}"
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

private fun parseReactionsToJson(bytes: com.google.protobuf.ByteString): String {
    if (bytes.isEmpty) return ""
    return try {
        val list = MessageReactionList.parseFrom(bytes)
        if (list.reactionsList.isEmpty()) return ""
        val arr = JSONArray()
        for (r in list.reactionsList) {
            if (r.count < 1) continue
            val obj = JSONObject()
            obj.put("emoji_id", r.emojiId)
            obj.put("emoji", r.emoji)
            obj.put("sender_id", r.senderId)
            obj.put("count", r.count)
            arr.put(obj)
        }
        if (arr.length() == 0) "" else arr.toString()
    } catch (_: Exception) {
        ""
    }
}

fun applyReactionEvent(
    currentJson: String,
    emojiId: Long,
    emoji: String,
    senderId: Long,
    count: Int,
    actionRemove: Boolean
): String {
    val arr = if (currentJson.isNotEmpty()) {
        try { JSONArray(currentJson) } catch (_: Exception) { JSONArray() }
    } else JSONArray()

    var found = false
    for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        if (obj.optLong("emoji_id") == emojiId && obj.optLong("sender_id") == senderId) {
            if (actionRemove) {
                obj.put("count", 0)
            } else {
                obj.put("count", obj.optInt("count", 0) + 1)
            }
            found = true
            break
        }
    }
    if (!found && !actionRemove) {
        val obj = JSONObject()
        obj.put("emoji_id", emojiId)
        obj.put("emoji", emoji)
        obj.put("sender_id", senderId)
        obj.put("count", count.coerceAtLeast(1))
        arr.put(obj)
    }
    return arr.toString()
}
