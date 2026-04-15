package com.mezon.mobile.home.voice

data class VoiceInfo(
    val channelId: Long,
    val clanId: Long,
    val channelLabel: String,
    val roomName: String
)

data class VoiceStatus(
    val clanId: Long,
    val channelId: Long
)
