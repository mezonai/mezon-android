package com.mezon.mobile.home.chat

import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP

data class ForwardDestination(
    val channelId: Long,
    val clanId: Long,
    val channelType: Int,
    val displayLabel: String,
    val subtitle: String,
    val isChannelPrivate: Boolean,
    val parentId: Long = 0L
) {
    fun joinIsPublicFlag(): Boolean {
        val t = channelType
        if (t == CHANNEL_TYPE_DM || t == CHANNEL_TYPE_GROUP) return false
        if (t == com.mezon.mobile.network.CHANNEL_TYPE_THREAD) return false
        return !isChannelPrivate
    }
}
