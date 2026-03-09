package com.mezon.mobile.home.clans

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mezon.mezon.api.ClanDesc

@Entity(tableName = "clans", indices = [Index("clanOrder")])
data class ClanEntity(
    @PrimaryKey val clanId: Long,
    val clanName: String,
    val logo: String,
    val banner: String,
    val badgeCount: Int,
    val isCommunity: Boolean,
    val hasUnread: Boolean,
    val clanOrder: Int
)

fun ClanDesc.toClanEntity(): ClanEntity = ClanEntity(
    clanId = clanId,
    clanName = clanName,
    logo = logo,
    banner = banner,
    badgeCount = badgeCount,
    isCommunity = isCommunity,
    hasUnread = hasUnreadMessage,
    clanOrder = clanOrder
)
