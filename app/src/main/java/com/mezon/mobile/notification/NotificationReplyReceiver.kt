package com.mezon.mobile.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.util.buildTextContent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "NotificationReply"
private const val SEND_TIMEOUT_MS = 8_000L

class NotificationReplyReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REPLY = "com.mezon.mobile.notification.REPLY"
        const val KEY_REPLY_TEXT = "mezon_notification_reply_text"
    }

    private data class ReplyTarget(
        val channelId: Long,
        val clanId: Long,
        val channelType: Int,
        val isPrivate: Boolean
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return

        val channelId = intent.getLongExtra(NotificationHelper.EXTRA_CHANNEL_ID, 0L)
        if (channelId == 0L) return
        val clanId = intent.getLongExtra(NotificationHelper.EXTRA_CLAN_ID, 0L)
        val channelType = intent.getIntExtra(NotificationHelper.EXTRA_CHANNEL_TYPE, 0)
        val channelName = intent.getStringExtra(NotificationHelper.EXTRA_CHANNEL_NAME).orEmpty()
        val notificationTitle =
            intent.getStringExtra(NotificationHelper.EXTRA_NOTIFICATION_TITLE).orEmpty()
        val notificationId =
            intent.getIntExtra(NotificationHelper.EXTRA_NOTIFICATION_ID, channelId.toInt())

        val entryPoint = try {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                FragmentEntryPoint::class.java
            )
        } catch (e: Exception) {
            Log.e(TAG, "Hilt entry point unavailable", e)
            return
        }
        val notificationHelper = entryPoint.notificationHelper()

        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY_TEXT)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (text.isEmpty()) {
            notificationHelper.cancelNotification(notificationId)
            return
        }
        if (!StartupCache.hasSession) {
            notificationHelper.showReplyFailedNotification(
                notificationId = notificationId,
                title = notificationTitle,
                channelId = channelId,
                clanId = clanId,
                channelName = channelName,
                channelType = channelType
            )
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var sent = false
            try {
                val target = resolveTarget(entryPoint, channelId, clanId, channelType)
                val messageId = withTimeoutOrNull(SEND_TIMEOUT_MS) {
                    entryPoint.chatController().sendRawChannelMessage(
                        channelId = target.channelId,
                        clanId = target.clanId,
                        channelType = target.channelType,
                        isChannelPrivate = target.isPrivate,
                        contentJson = buildTextContent(text)
                    )
                } ?: 0L
                sent = messageId != 0L
            } catch (e: Exception) {
                Log.e(TAG, "Reply send failed channelId=$channelId clanId=$clanId", e)
                entryPoint.sentryReporter()
                    .logChatFailure("notificationReply", channelId, clanId, e)
            } finally {
                if (sent) {
                    notificationHelper.cancelNotification(notificationId)
                } else {
                    notificationHelper.showReplyFailedNotification(
                        notificationId = notificationId,
                        title = notificationTitle,
                        channelId = channelId,
                        clanId = clanId,
                        channelName = channelName,
                        channelType = channelType
                    )
                }
                pendingResult.finish()
            }
        }
    }

    private fun resolveTarget(
        entryPoint: FragmentEntryPoint,
        channelId: Long,
        clanId: Long,
        channelType: Int
    ): ReplyTarget {
        val meta = entryPoint.channelController().findChannelById(channelId)
        if (meta != null && meta.type != 0) {
            val resolvedType = if (meta.isThread) CHANNEL_TYPE_THREAD else meta.type
            return ReplyTarget(
                channelId = channelId,
                clanId = if (meta.clanId != 0L) meta.clanId else clanId,
                channelType = resolvedType,
                isPrivate = meta.isPrivate || isDirectConversation(resolvedType)
            )
        }
        val dialog = entryPoint.dialogsController().getDialog(channelId)
        if (dialog != null && dialog.type != 0) {
            return ReplyTarget(channelId, 0L, dialog.type, isDirectConversation(dialog.type))
        }
        val fallbackType = when {
            channelType != 0 -> channelType
            clanId == 0L -> CHANNEL_TYPE_DM
            else -> CHANNEL_TYPE_CHANNEL
        }
        return ReplyTarget(channelId, clanId, fallbackType, isDirectConversation(fallbackType))
    }

    private fun isDirectConversation(channelType: Int): Boolean =
        channelType == CHANNEL_TYPE_DM || channelType == CHANNEL_TYPE_GROUP
}
