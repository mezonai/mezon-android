package ai.mezon.app.notification

import ai.mezon.app.di.ApplicationScope
import ai.mezon.app.network.SocketEventDispatcher
import ai.mezon.app.session.SessionManager
import ai.mezon.app.util.parseContentPreview
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
        private const val STREAM_MODE_DM = 4
        private const val STREAM_MODE_GROUP = 3
        private const val CODE_CHAT_UPDATE = 2
        private const val CODE_CHAT_REMOVE = 3
    }

    init {
        appScope.launch { observeMessages() }
    }

    private suspend fun observeMessages() {
        val currentUserId = sessionManager.sessionFlow
            .first { it != null }
            ?.userId ?: ""

        socketEventDispatcher.channelMessages.collect { msg ->
            if (msg.code == CODE_CHAT_UPDATE || msg.code == CODE_CHAT_REMOVE) return@collect
            if (msg.senderId.toString() == currentUserId) return@collect
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
