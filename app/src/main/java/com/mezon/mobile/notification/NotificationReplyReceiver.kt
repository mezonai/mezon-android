package com.mezon.mobile.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.mezon.mobile.R
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.chat.LIKE_EMOJI_DISPLAY
import com.mezon.mobile.home.chat.LIKE_EMOJI_ID
import com.mezon.mobile.home.chat.LIKE_EMOJI_SHORTNAME
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.util.EmojiMarker
import com.mezon.mobile.util.buildTextContent
import com.mezon.mobile.util.buildTextContentWithEmojis
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "NotificationReply"
private const val SEND_TIMEOUT_MS = 8_000L
private val SEND_RETRY_DELAYS_MS = longArrayOf(500L, 1_500L, 3_000L)

class NotificationReplyReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REPLY = "com.mezon.mobile.notification.REPLY"
        const val ACTION_LIKE = "com.mezon.mobile.notification.LIKE"
        const val KEY_REPLY_TEXT = "mezon_notification_reply_text"
    }

    private data class ReplyTarget(
        val channelId: Long,
        val clanId: Long,
        val channelType: Int,
        val isPrivate: Boolean
    )

    override fun onReceive(context: Context, intent: Intent) {
        val isLike = intent.action == ACTION_LIKE
        if (intent.action != ACTION_REPLY && !isLike) return

        val channelId = intent.getLongExtra(NotificationHelper.EXTRA_CHANNEL_ID, 0L)
        if (channelId == 0L) return
        val clanId = intent.getLongExtra(NotificationHelper.EXTRA_CLAN_ID, 0L)
        val channelType = intent.getIntExtra(NotificationHelper.EXTRA_CHANNEL_TYPE, 0)
        val channelName = intent.getStringExtra(NotificationHelper.EXTRA_CHANNEL_NAME).orEmpty()
        val notificationTitle =
            intent.getStringExtra(NotificationHelper.EXTRA_NOTIFICATION_TITLE).orEmpty()
        val notificationId =
            intent.getIntExtra(NotificationHelper.EXTRA_NOTIFICATION_ID, channelId.toInt())
        val messageId = intent.getLongExtra(NotificationHelper.EXTRA_MESSAGE_ID, 0L)
        val messageSenderId = intent.getLongExtra(NotificationHelper.EXTRA_MESSAGE_SENDER_ID, 0L)
        val topicId = intent.getLongExtra(NotificationHelper.EXTRA_TOPIC_ID, 0L)

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

        val contentJson: String?
        val sentText: String
        if (isLike) {
            contentJson = if (messageId != 0L) {
                null
            } else {
                buildTextContentWithEmojis(
                    LIKE_EMOJI_SHORTNAME,
                    null,
                    listOf(EmojiMarker(LIKE_EMOJI_ID.toString(), 0, LIKE_EMOJI_SHORTNAME.length))
                )
            }
            sentText = if (contentJson == null) {
                context.getString(R.string.notification_like_reacted)
            } else {
                LIKE_EMOJI_DISPLAY
            }
        } else {
            val text = RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(KEY_REPLY_TEXT)
                ?.toString()
                ?.trim()
                .orEmpty()
            if (text.isEmpty()) {
                notificationHelper.cancelNotification(notificationId)
                return
            }
            contentJson = buildTextContent(text)
            sentText = text
        }
        val failureTextRes = if (isLike) {
            R.string.notification_like_failed
        } else {
            R.string.notification_reply_failed
        }
        if (!StartupCache.hasSession) {
            notificationHelper.showReplyFailedNotification(
                notificationId = notificationId,
                title = notificationTitle,
                channelId = channelId,
                clanId = clanId,
                channelName = channelName,
                channelType = channelType,
                failureTextRes = failureTextRes,
                messageId = messageId,
                messageSenderId = messageSenderId,
                topicId = topicId
            )
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var sent = false
            try {
                sent = withTimeout(SEND_TIMEOUT_MS) {
                    val target = resolveTarget(entryPoint, channelId, clanId, channelType)
                    if (contentJson != null) {
                        entryPoint.chatController().sendRawChannelMessage(
                            channelId = target.channelId,
                            clanId = target.clanId,
                            channelType = target.channelType,
                            isChannelPrivate = target.isPrivate,
                            contentJson = contentJson,
                            httpOnly = true,
                            retryDelaysMs = SEND_RETRY_DELAYS_MS
                        ) != 0L
                    } else {
                        entryPoint.chatController().sendReactionAwait(
                            channelId = target.channelId,
                            clanId = target.clanId,
                            channelType = target.channelType,
                            isChannelPrivate = target.isPrivate,
                            messageId = messageId,
                            emojiId = LIKE_EMOJI_ID,
                            emoji = LIKE_EMOJI_SHORTNAME,
                            count = 1,
                            actionDelete = false,
                            messageSenderId = messageSenderId,
                            topicId = topicId,
                            httpOnly = true,
                            retryDelaysMs = SEND_RETRY_DELAYS_MS
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send failed isLike=$isLike channelId=$channelId clanId=$clanId", e)
                entryPoint.sentryReporter()
                    .logChatFailure(
                        if (isLike) "notificationLike" else "notificationReply",
                        channelId,
                        clanId,
                        e
                    )
            } finally {
                if (sent) {
                    notificationHelper.showSentMessageNotification(
                        notificationId = notificationId,
                        title = notificationTitle,
                        channelId = channelId,
                        clanId = clanId,
                        channelName = channelName,
                        channelType = channelType,
                        sentText = sentText,
                        messageId = messageId,
                        messageSenderId = messageSenderId,
                        topicId = topicId
                    )
                } else {
                    notificationHelper.showReplyFailedNotification(
                        notificationId = notificationId,
                        title = notificationTitle,
                        channelId = channelId,
                        clanId = clanId,
                        channelName = channelName,
                        channelType = channelType,
                        failureTextRes = failureTextRes,
                        messageId = messageId,
                        messageSenderId = messageSenderId,
                        topicId = topicId
                    )
                }
                pendingResult.finish()
            }
        }
    }

    private suspend fun resolveTarget(
        entryPoint: FragmentEntryPoint,
        channelId: Long,
        clanId: Long,
        channelType: Int
    ): ReplyTarget {
        val channelController = entryPoint.channelController()
        val meta = channelController.findChannelById(channelId) ?: run {
            channelController.findOrFetchChannelLabel(channelId, clanId)
            channelController.findChannelById(channelId)
        }
        if (meta != null && meta.type != 0) {
            val resolvedType = if (meta.isThread) CHANNEL_TYPE_THREAD else meta.type
            val threadLike = resolvedType == CHANNEL_TYPE_THREAD || meta.parentId != 0L
            return ReplyTarget(
                channelId = channelId,
                clanId = if (meta.clanId != 0L) meta.clanId else clanId,
                channelType = resolvedType,
                isPrivate = meta.isPrivate || threadLike || isDirectConversation(resolvedType)
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
