package com.mezon.mobile.home.clans

data class ClanRole(
    val roleId: Long,
    val clanId: Long,
    val title: String,
    val color: Int,
    val iconUrl: String,
    val slug: String
)
