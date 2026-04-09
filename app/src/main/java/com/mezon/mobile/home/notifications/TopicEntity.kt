package com.mezon.mobile.home.notifications

data class TopicEntity(
    val id: String,
    val clanId: Long,
    val channelId: Long,
    val topicContentRaw: String,
    val senderId: Long,
    val senderName: String,
    val senderAvatar: String,
    val createTimeSeconds: Long,
    val messageId: Long,
    val lastSentMessageContentRaw: String,
    val lastSentMessageSenderId: Long,
    val lastSentMessageTimestampSeconds: Long
)