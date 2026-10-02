package com.mezon.mobile.home.chat

import android.os.Bundle
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD

class TopicFragment : ChatFragment() {

    companion object {
        private const val ARG_TARGET_MESSAGE_ID = "message_id"

        fun newInstance(
            topicId: Long,
            rootMessageId: Long,
            clanId: Long,
            parentChannelId: Long,
            channelType: Int = CHANNEL_TYPE_THREAD,
            isChannelPrivate: Boolean = false,
            openedFromNotification: Boolean = false,
            targetMessageId: Long = 0L
        ): TopicFragment = TopicFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_CHANNEL_ID, parentChannelId)
                putLong(ARG_CLAN_ID, clanId)
                putInt(ARG_CHANNEL_TYPE, channelType)
                putLong(ARG_TOPIC_ID, topicId)
                putLong(ARG_ROOT_MESSAGE_ID, rootMessageId)
                if (targetMessageId != 0L) putLong(ARG_TARGET_MESSAGE_ID, targetMessageId)
                if (isChannelPrivate) putBoolean(ARG_CHANNEL_PRIVATE, true)
                if (openedFromNotification) putBoolean(ARG_OPENED_FROM_NOTIFICATION, true)
            }
        }
    }
}
