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

object ClanEventRepeatType {
    const val DOES_NOT_REPEAT = 0
    const val WEEKLY_ON_DAY = 1
    const val EVERY_OTHER_DAY = 2
    const val MONTHLY = 3
    const val ANNUALLY = 4
    const val EVERY_WEEKDAY = 5
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
    val originalLogoUrl: String? = null,
    val editingEventId: Long = 0L,
    val editingChannelIdOld: Long = 0L,
)
