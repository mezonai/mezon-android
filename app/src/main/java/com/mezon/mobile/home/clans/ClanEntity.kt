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

fun ClanDesc.mergeOnto(existing: ClanEntity): ClanEntity {
    val p = toClanEntity()
    return p.copy(
        clanId = existing.clanId,
        clanName = p.clanName.ifEmpty { existing.clanName },
        logo = p.logo,
        banner = p.banner,
        badgeCount = existing.badgeCount,
        hasUnread = existing.hasUnread,
        clanOrder = existing.clanOrder,
        creatorId = if (p.creatorId != 0L) p.creatorId else existing.creatorId,
        welcomeChannelId = if (p.welcomeChannelId != 0L) p.welcomeChannelId else existing.welcomeChannelId,
    )
}

internal fun ClanDesc.mergeOverviewUpdateOnto(
    existing: ClanEntity,
    requestedName: String,
    requestedBanner: String?,
    requestedPreventAnonymous: Boolean,
    requestedWelcomeChannelId: Long?,
    requestedIsOnboarding: Boolean?,
): ClanEntity {
    val merged = if (clanId == 0L) {
        existing.copy(
            clanName = requestedName,
            preventAnonymous = requestedPreventAnonymous,
            welcomeChannelId = requestedWelcomeChannelId ?: existing.welcomeChannelId,
            isOnboarding = requestedIsOnboarding ?: existing.isOnboarding,
        )
    } else {
        mergeOnto(existing)
    }
    return if (requestedBanner != null) merged.copy(banner = requestedBanner) else merged
}
