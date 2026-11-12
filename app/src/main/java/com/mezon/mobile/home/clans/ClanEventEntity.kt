package com.mezon.mobile.home.clans

import com.mezon.mezon.api.EventManagement
import com.mezon.mobile.util.DateTimeUtil
import java.util.Calendar
import java.util.concurrent.TimeUnit

data class ClanEventEntity(
    val id: Long,
    val title: String,
    val logo: String,
    val description: String,
    val clanId: Long,
    val channelVoiceId: Long,
    val channelId: Long,
    val address: String,
    val startTimeSeconds: Int,
    val endTimeSeconds: Int,
    val creatorId: Long,
    val userIds: List<Long>,
    val maxPermission: Int,
    val eventStatus: Int,
    val repeatType: Int,
    val isPrivate: Boolean,
    val externalLink: String,
) {
    val interestedCount: Int get() = userIds.count { it != 0L }

    fun contentForCopy(): String = if (description.isBlank()) title else "$title\n$description"

    fun shareUrl(baseUrl: String): String? {
        val base = baseUrl.trimEnd('/')
        if (isPrivate) {
            return externalLink.takeIf { it.isNotBlank() }?.let { "$base/${it.trimStart('/')}" }
        }
        val linkedChannelId = channelVoiceId.takeIf { it != 0L } ?: channelId
        if (linkedChannelId == 0L) return null
        return "$base/chat/clans/$clanId/channels/$linkedChannelId"
    }

    internal val effectiveEndTimeMillis: Long?
        get() = when {
            endTimeSeconds != 0 -> endTimeSeconds.toLong() * 1_000
            startTimeSeconds > 0 -> startTimeSeconds.toLong() * 1_000 + TimeUnit.HOURS.toMillis(2)
            else -> null
        }

    fun displayStatus(nowMillis: Long = System.currentTimeMillis()): Int {
        if (startTimeSeconds == 0) return eventStatus
        val start = startTimeSeconds.toLong() * 1_000
        val end = effectiveEndTimeMillis ?: return eventStatus
        if (nowMillis >= start && nowMillis < end) return ClanEventStatus.ONGOING
        if (start > nowMillis && start - nowMillis <= TimeUnit.MINUTES.toMillis(10)) return ClanEventStatus.UPCOMING
        return ClanEventStatus.CREATED
    }

    fun isStartToday(): Boolean {
        val today = Calendar.getInstance()
        val startDay = Calendar.getInstance().apply {
            timeInMillis = DateTimeUtil.epochToMillis(startTimeSeconds.toLong())
        }
        return today.get(Calendar.DAY_OF_YEAR) == startDay.get(Calendar.DAY_OF_YEAR) &&
            today.get(Calendar.YEAR) == startDay.get(Calendar.YEAR)
    }

    fun minutesUntilStart(): Int {
        val nowSec = System.currentTimeMillis() / 1000
        val delta = startTimeSeconds - nowSec
        if (delta <= 0 || delta > TimeUnit.MINUTES.toSeconds(10)) return 0
        return ((delta + 59) / 60).toInt()
    }

    fun isInterested(userId: Long): Boolean = userId != 0L && userIds.contains(userId)

    fun isOfflineEvent(): Boolean = address.isNotBlank()

    fun interestedUserIds(): List<Long> = userIds.filter { it != 0L }
}

fun EventManagement.toClanEventEntity(): ClanEventEntity = ClanEventEntity(
    id = id,
    title = title,
    logo = logo,
    description = description,
    clanId = clanId,
    channelVoiceId = channelVoiceId,
    channelId = channelId,
    address = address,
    startTimeSeconds = startTimeSeconds,
    endTimeSeconds = endTimeSeconds,
    creatorId = creatorId,
    userIds = userIdsList,
    maxPermission = maxPermission,
    eventStatus = eventStatus,
    repeatType = repeatType,
    isPrivate = isExternal,
    externalLink = meetRoom.externalLink,
)
