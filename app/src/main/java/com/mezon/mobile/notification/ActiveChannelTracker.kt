package com.mezon.mobile.notification

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActiveChannelTracker @Inject constructor() {

    @Volatile
    var activeChannelId: Long? = null
        private set

    fun setActive(channelId: Long) {
        activeChannelId = channelId
    }

    fun clear() {
        activeChannelId = null
    }

    fun isViewing(channelId: Long): Boolean = activeChannelId == channelId
}
