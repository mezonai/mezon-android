package com.mezon.mobile.notification

import com.mezon.mobile.MainActivity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActiveChannelTracker @Inject constructor() {

    @Volatile
    var activeChannelId: Long? = null
        private set
    @Volatile
    var activeTopicId: Long = 0L
        private set

    fun setActive(channelId: Long, topicId: Long = 0L) {
        activeChannelId = channelId
        activeTopicId = topicId
    }

    fun clear() {
        activeChannelId = null
        activeTopicId = 0L
    }

    fun isViewing(channelId: Long): Boolean =
        activeChannelId == channelId && !MainActivity.applicationPaused

    fun isViewing(channelId: Long, topicId: Long): Boolean =
        isViewing(channelId) && activeTopicId == topicId
}
