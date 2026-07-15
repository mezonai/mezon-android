package com.mezon.mobile.home.call

import android.content.Context
import com.mezon.mobile.R
import com.mezon.mezon.api.MessageAttachmentList
import com.mezon.mobile.home.chat.MessageEntity
import com.mezon.mobile.util.parseContentObject
import com.mezon.mobile.util.parseContentPreview
import com.google.protobuf.ByteString
import org.json.JSONObject

object CallLogMessageType {
    const val STARTCALL = 1
    const val TIMEOUTCALL = 2
    const val FINISHCALL = 3
    const val REJECTCALL = 4
    const val CANCELCALL = 5
}

data class ParsedCallLogMessage(
    val callLogType: Int,
    val isVideo: Boolean,
    val tText: String
)

fun parseCallLogMessage(content: String): ParsedCallLogMessage? {
    if (content.isBlank()) return null
    val trimmed = content.trimStart()
    if (!trimmed.startsWith("{")) return null
    return try {
        parseCallLogMessage(JSONObject(content))
    } catch (_: Exception) {
        null
    }
}

private fun parseCallLogMessage(root: JSONObject): ParsedCallLogMessage? {
    if (!root.has("callLog")) return legacyParseCallLog(root)
    val cl = root.optJSONObject("callLog") ?: return null
    val type = cl.optInt("callLogType", 0)
    if (type == 0) return null
    return ParsedCallLogMessage(
        callLogType = type,
        isVideo = cl.optBoolean("isVideo", false),
        tText = root.optString("t", "")
    )
}

private fun legacyParseCallLog(root: JSONObject): ParsedCallLogMessage? {
    val status = root.optString("callStatus", "")
    if (status.isEmpty()) return null
    val type = when (status) {
        "TIMEOUTCALL" -> CallLogMessageType.TIMEOUTCALL
        "REJECTCALL" -> CallLogMessageType.REJECTCALL
        "CANCELCALL" -> CallLogMessageType.CANCELCALL
        "ENDCALL", "FINISHCALL" -> CallLogMessageType.FINISHCALL
        "BUSYCALL", "MISSEDCALL" -> CallLogMessageType.REJECTCALL
        else -> return null
    }
    val durSec = root.optInt("duration", 0)
    val t = if (type == CallLogMessageType.FINISHCALL) formatFinishDurationFromSeconds(durSec) else ""
    return ParsedCallLogMessage(type, false, t)
}

fun buildCallLogJson(t: String, callLogType: Int, isVideo: Boolean): String {
    val te = t
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    return "{\"t\":\"$te\",\"callLog\":{\"isVideo\":$isVideo,\"callLogType\":$callLogType}}"
}

fun buildStartCallLine(username: String, isVideo: Boolean): String =
    "$username started a ${if (isVideo) "video" else "audio"} call"

fun formatFinishDurationMs(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).toInt().coerceAtLeast(0)
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return "$mins mins $secs secs"
}

fun formatFinishDurationFromSeconds(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val mins = s / 60
    val secs = s % 60
    return "$mins mins $secs secs"
}

fun dialogPreviewForCallLog(context: Context, parsed: ParsedCallLogMessage): String {
    return when (parsed.callLogType) {
        CallLogMessageType.FINISHCALL -> parsed.tText.ifBlank {
            context.getString(if (parsed.isVideo) R.string.message_call_log_video_call else R.string.message_call_log_audio_call)
        }
        else -> context.getString(if (parsed.isVideo) R.string.message_call_log_video_call else R.string.message_call_log_audio_call)
    }
}

fun messagePreviewForDialog(
    context: Context,
    content: String,
    attachments: ByteString = ByteString.EMPTY,
    code: Int = 0
): String {
    return messagePreviewForDialog(context, content, parseContentObject(content.trim()), attachments, code)
}

private fun messagePreviewForDialog(
    context: Context,
    content: String,
    contentObject: JSONObject?,
    attachments: ByteString = ByteString.EMPTY,
    code: Int = 0
): String {
    val base = parseContentPreview(content, contentObject)
    if (base.isNotBlank()) {
        if (base == "[Contact]") {
            return "[${context.getString(R.string.message_attachment_contact)}]"
        }
        return base
    }
    when (code) {
        MessageEntity.CODE_SHARE_CONTACT -> {
            return "[${context.getString(R.string.message_attachment_contact)}]"
        }
        MessageEntity.CODE_LOCATION -> {
            return "[${context.getString(R.string.message_attachment_location)}]"
        }
        MessageEntity.CODE_POLL -> {
            return "[${context.getString(R.string.message_attachment_poll)}]"
        }
    }
    if (!attachments.isEmpty) {
        val hasAttachments = runCatching {
            MessageAttachmentList.parseFrom(attachments).attachmentsCount > 0
        }.getOrDefault(false)
        if (hasAttachments) {
            return "[${context.getString(R.string.message_attachment_file)}]"
        }
    }
    val cl = contentObject?.let(::parseCallLogMessage) ?: return ""
    return dialogPreviewForCallLog(context, cl)
}

private val ATTACHMENT_ONLY_HEADER_KEYS = setOf("t", "mk", "ej", "hg")

fun shouldInferAttachmentOnlyHeaderPreview(
    content: String,
    messageId: Long,
    timestampSeconds: Long,
    senderId: Long
): Boolean {
    return shouldInferAttachmentOnlyHeaderPreview(
        content = content,
        contentObject = parseContentObject(content.trim()),
        messageId = messageId,
        timestampSeconds = timestampSeconds,
        senderId = senderId
    )
}

private fun shouldInferAttachmentOnlyHeaderPreview(
    content: String,
    contentObject: JSONObject?,
    messageId: Long,
    timestampSeconds: Long,
    senderId: Long
): Boolean {
    if (senderId == 0L || (messageId == 0L && timestampSeconds == 0L)) return false

    val trimmed = content.trim()
    if (trimmed.isEmpty() || trimmed == "{}") return true

    val obj = contentObject ?: return false
    val keys = obj.keys().asSequence().toSet()
    if (!ATTACHMENT_ONLY_HEADER_KEYS.containsAll(keys)) return false
    if (obj.optString("t", "").isNotBlank()) return false
    return true
}

fun messageHeaderPreviewForDialog(
    context: Context,
    content: String,
    messageId: Long,
    timestampSeconds: Long,
    senderId: Long
): String {
    val contentObject = parseContentObject(content.trim())
    val preview = messagePreviewForDialog(context, content, contentObject)
    if (preview.isNotBlank()) return preview

    val hasEmbed = contentObject?.let { it.has("embed") || it.has("embeds") } == true
    if (hasEmbed) {
        return "[${context.getString(R.string.message_attachment_embed)}]"
    }

    if (shouldInferAttachmentOnlyHeaderPreview(content, contentObject, messageId, timestampSeconds, senderId)) {
        return "[${context.getString(R.string.message_attachment_file)}]"
    }
    return ""
}
