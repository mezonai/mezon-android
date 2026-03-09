package com.mezon.mobile.notification

import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.network.CODE_CHAT_REMOVE
import com.mezon.mobile.network.CODE_CHAT_UPDATE
import com.mezon.mobile.network.STREAM_MODE_DM
import com.mezon.mobile.network.STREAM_MODE_GROUP
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.util.parseContentPreview
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationObserver @Inject constructor(
    private val socketEventDispatcher: SocketEventDispatcher,
    private val notificationHelper: NotificationHelper,
    private val activeChannelTracker: ActiveChannelTracker,
    private val sessionManager: SessionManager,
    @ApplicationScope private val appScope: CoroutineScope
) {
    companion object {
        private const val TAG = "NotificationObserver"
    }

    init {
        appScope.launch { observeMessages() }
    }

    private suspend fun observeMessages() {
        val currentUserId = sessionManager.sessionFlow
            .first { it != null }
            ?.userId
            ?.toLongOrNull() ?: 0L

        socketEventDispatcher.channelMessages.collect { msg ->
            if (msg.code == CODE_CHAT_UPDATE || msg.code == CODE_CHAT_REMOVE) return@collect
            if (msg.senderId == currentUserId) return@collect
            if (activeChannelTracker.isViewing(msg.channelId)) return@collect

            val body = parseContentPreview(msg.content)
            if (body.isBlank()) return@collect

            val senderName = msg.displayName.ifBlank { msg.username }
            val isDm = msg.mode == STREAM_MODE_DM || msg.mode == STREAM_MODE_GROUP

            if (isDm) {
                notificationHelper.showDmNotification(
                    title = senderName,
                    body = body,
                    dmChannelId = msg.channelId
                )
            } else {
                val channelLabel = msg.channelLabel.ifBlank { "Channel" }
                notificationHelper.showMessageNotification(
                    title = "$senderName · $channelLabel",
                    body = body,
                    channelId = msg.channelId,
                    clanId = msg.clanId,
                    channelName = channelLabel,
                    channelType = msg.mode
                )
            }

            Log.d(TAG, "Local notification: sender=$senderName, ch=${msg.channelId}, isDm=$isDm")
        }
    }
}
