package com.mezon.mobile.home

data class CachedChannel(
    val channelId: Long,
    val clanId: Long,
    val type: Int,
    val label: String,
    val isPrivate: Boolean = false
)
