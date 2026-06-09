package com.mezon.mobile.network

data class LinkInvitePreview(
    val clanName: String,
    val channelLabel: String,
    val logoUrl: String,
    val memberCount: Int = 0,
)
