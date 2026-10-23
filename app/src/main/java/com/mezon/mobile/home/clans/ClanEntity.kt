package com.mezon.mobile.home.clans

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mezon.mezon.api.ClanDesc
import com.mezon.mezon.rtapi.ClanUpdatedEvent

@Entity(tableName = "clans", indices = [Index("clanOrder")])
data class ClanEntity(
    @PrimaryKey val clanId: Long,
    val clanName: String,
    val logo: String,
    val banner: String,
    val badgeCount: Int,
    val isCommunity: Boolean,
    val hasUnread: Boolean,
    val clanOrder: Int,
    val creatorId: Long = 0L,
    val preventAnonymous: Boolean = false,
    val welcomeChannelId: Long = 0L,
    val isOnboarding: Boolean = false,
)

fun ClanDesc.toClanEntity(): ClanEntity = ClanEntity(
    clanId = clanId,
    clanName = clanName,
    logo = logo,
    banner = banner,
    badgeCount = badgeCount,
    isCommunity = isCommunity,
    hasUnread = hasUnreadMessage,
    clanOrder = clanOrder,
    creatorId = creatorId,
    preventAnonymous = preventAnonymous,
    welcomeChannelId = welcomeChannelId,
    isOnboarding = isOnboarding,
)

internal fun ClanUpdatedEvent.mergeOnto(existing: ClanEntity): ClanEntity = existing.copy(
    clanName = clanName.ifEmpty { existing.clanName },
    logo = logo.ifEmpty { existing.logo },
    banner = banner.ifEmpty { existing.banner },
    isCommunity = isCommunity || existing.isCommunity,
    preventAnonymous = preventAnonymous || existing.preventAnonymous,
    welcomeChannelId = welcomeChannelId.takeIf { it != 0L } ?: existing.welcomeChannelId,
    isOnboarding = isOnboarding,
)

internal fun ClanUpdatedEvent.needsPolicyRefresh(existing: ClanEntity): Boolean =
    (!preventAnonymous && existing.preventAnonymous) || (!isCommunity && existing.isCommunity)
