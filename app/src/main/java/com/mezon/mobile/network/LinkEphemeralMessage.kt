package com.mezon.mobile.network

import com.google.protobuf.ByteString
import com.mezon.mobile.home.chat.mergeChannelContentMentionsAndRefs
import com.mezon.mezon.api.ChannelMessage
import com.mezon.mezon.api.MessageAttachmentList
import com.mezon.mezon.api.MessageMentionList
import com.mezon.mezon.api.MessageRefList
import com.mezon.mezon.rtapi.ChannelMessageSend
import com.mezon.mezon.rtapi.EphemeralMessageSend

fun ChannelMessageSend.toApiChannelMessage(): ChannelMessage {
    val mentionsBytes = if (mentionsCount == 0) {
        ByteString.EMPTY
    } else {
        ByteString.copyFrom(
            MessageMentionList.newBuilder().addAllMentions(mentionsList).build().toByteArray()
        )
    }
    val attachmentsBytes = if (attachmentsCount == 0) {
        ByteString.EMPTY
    } else {
        ByteString.copyFrom(
            MessageAttachmentList.newBuilder().addAllAttachments(attachmentsList).build().toByteArray()
        )
    }
    val referencesBytes = if (referencesCount == 0) {
        ByteString.EMPTY
    } else {
        ByteString.copyFrom(
            MessageRefList.newBuilder().addAllRefs(referencesList).build().toByteArray()
        )
    }
    val mergedContent = mergeChannelContentMentionsAndRefs(content, mentionsBytes, referencesBytes)
    val nowSec = (System.currentTimeMillis() / 1000L).toInt()
    return ChannelMessage.newBuilder()
        .setClanId(clanId)
        .setChannelId(channelId)
        .setMessageId(id)
        .setCode(code)
        .setSenderId(0L)
        .setContent(mergedContent)
        .setAvatar(avatar)
        .setMentions(mentionsBytes)
        .setAttachments(attachmentsBytes)
        .setReferences(referencesBytes)
        .setCreateTimeSeconds(nowSec)
        .setUpdateTimeSeconds(0)
        .setMode(mode)
        .setHideEditted(true)
        .setIsPublic(isPublic)
        .setTopicId(topicId)
        .build()
}

fun EphemeralMessageSend.toApiChannelMessage(): ChannelMessage? {
    if (!hasMessage()) return null
    return message.toApiChannelMessage()
}
