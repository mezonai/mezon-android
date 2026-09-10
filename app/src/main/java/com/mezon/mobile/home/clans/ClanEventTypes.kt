package com.mezon.mobile.home.clans

object ClanEventOption {
    const val SPEAKER = 1
    const val LOCATION = 2
    const val PRIVATE = 3
}

object ClanEventStatus {
    const val CREATED = 0
    const val UPCOMING = 1
    const val ONGOING = 2
    const val COMPLETED = 3
}

object ClanEventAction {
    const val CREATED = 1
    const val UPDATE = 2
    const val DELETE = 3
    const val INTERESTED = 4
    const val UNINTERESTED = 5
}

object ClanEventRepeatType {
    const val DOES_NOT_REPEAT = 1
    const val WEEKLY_ON_DAY = 2
    const val EVERY_OTHER_DAY = 3
    const val MONTHLY = 4
    const val ANNUALLY = 5
    const val EVERY_WEEKDAY = 6
}

data class CreateEventDraft(
    val option: Int = 0,
    val channelVoiceId: Long = 0L,
    val address: String = "",
    val channelId: Long = 0L,
    val isPrivate: Boolean = false,
    val title: String = "",
    val description: String = "",
    val startTimeSeconds: Int = 0,
    val endTimeSeconds: Int = 0,
    val repeatType: Int = ClanEventRepeatType.DOES_NOT_REPEAT,
    val logoUrl: String = "",
    val editingEventId: Long = 0L,
) {
    fun hasChangesFrom(event: ClanEventEntity): Boolean =
        title != event.title || description != event.description || logoUrl != event.logo ||
            channelVoiceId != event.channelVoiceId || address != event.address ||
            channelId != event.channelId || repeatType != event.repeatType ||
            startTimeSeconds != event.startTimeSeconds || endTimeSeconds != event.endTimeSeconds
}
