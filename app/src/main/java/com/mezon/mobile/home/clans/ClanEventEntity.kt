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
) {
    val interestedCount: Int get() = userIds.count { it != 0L }

    fun displayStatus(): Int {
        if (eventStatus != 0) return eventStatus
        val nowSec = System.currentTimeMillis() / 1000
        val delta = startTimeSeconds - nowSec
        if (delta <= 0) return ClanEventStatus.ONGOING
        if (delta <= TimeUnit.MINUTES.toSeconds(10)) return ClanEventStatus.UPCOMING
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
    isPrivate = isPrivate,
)

internal fun channelEventStatuses(events: List<ClanEventEntity>): Map<Long, Int> {
    val statuses = HashMap<Long, Int>()
    for (event in events) {
        if (event.channelId == 0L) continue
        when (event.eventStatus) {
            ClanEventStatus.ONGOING -> statuses[event.channelId] = ClanEventStatus.ONGOING
            ClanEventStatus.UPCOMING -> if (statuses[event.channelId] != ClanEventStatus.ONGOING) {
                statuses[event.channelId] = ClanEventStatus.UPCOMING
            }
        }
    }
    return statuses
}

internal data class ClanEventStatusUpdate(
    val found: Boolean,
    val changed: Boolean,
)

internal fun updateClanEventStatus(
    events: MutableList<ClanEventEntity>,
    eventId: Long,
    eventStatus: Int,
    startTimeSeconds: Int,
): ClanEventStatusUpdate {
    val index = events.indexOfFirst { it.id == eventId }
    if (index < 0) return ClanEventStatusUpdate(found = false, changed = false)

    val event = events[index]
    val updatedStartTime = if (eventStatus == ClanEventStatus.COMPLETED && startTimeSeconds != 0) {
        startTimeSeconds
    } else {
        event.startTimeSeconds
    }
    if (event.eventStatus == eventStatus && event.startTimeSeconds == updatedStartTime) {
        return ClanEventStatusUpdate(found = true, changed = false)
    }

    events[index] = event.copy(
        eventStatus = eventStatus,
        startTimeSeconds = updatedStartTime,
    )
    return ClanEventStatusUpdate(found = true, changed = true)
}
