package com.mezon.mobile.home.clans.discover

import com.mezon.mezon.api.ClanDiscover

data class DiscoverClanItem(
    val clanId: Long,
    val inviteId: Long,
    val clanName: String,
    val clanLogo: String,
    val description: String,
    val banner: String,
    val about: String,
    val shortUrl: String,
    val totalMembers: Int,
    val verified: Boolean,
    val createTimeSeconds: Int
) {
    companion object {
        fun fromProto(c: ClanDiscover): DiscoverClanItem = DiscoverClanItem(
            clanId = c.clanId,
            inviteId = c.inviteId,
            clanName = c.clanName,
            clanLogo = c.clanLogo,
            description = c.description,
            banner = c.banner,
            about = c.about,
            shortUrl = c.shortUrl,
            totalMembers = c.totalMembers,
            verified = c.verified,
            createTimeSeconds = c.createTimeSeconds
        )
    }
}
