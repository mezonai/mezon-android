package com.mezon.mobile.notification

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClanEntity
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.avatarImgproxyUrl
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channelController: ChannelController,
    private val dialogsController: dagger.Lazy<DialogsController>,
    private val clansController: dagger.Lazy<ClansController>,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val MAX_NOTI_DELAY: Long = 3000L
        private const val MAX_BODY_LEN = 120
        private const val MAX_HISTORY_MESSAGES = 10
        private const val MAX_AVATAR_DELAY: Long = 2000L
        private const val AVATAR_ICON_SIZE = 192
        private const val MAX_CACHED_AVATARS = 12
        private const val SHORTCUT_CATEGORY_CONVERSATION = "android.shortcut.conversation"
        private const val DISMISS_GRACE_MS = 2000L
        private const val SUMMARY_NOTIFICATION_ID = 424242
        private val SENDER_IN_TITLE_REGEX = Regex("""^(.+?)\s*\(.*\)$""")
        const val GROUP_MESSAGES = "mezon_messages"
        const val CHANNEL_MESSAGES = "mezon_channel_messages"
        const val CHANNEL_DM = "mezon_dm"
        const val CHANNEL_SYSTEM = "mezon_system"

        private fun truncateBody(text: String): String {
            val single = text.replace('\n', ' ').replace('\r', ' ').trim()
            return if (single.length > MAX_BODY_LEN) single.substring(0, MAX_BODY_LEN) + "…" else single
        }

        const val ACTION_OPEN_CHAT = "com.mezon.openchat"
        const val ACTION_OPEN_FRIEND_REQUESTS = "com.mezon.open_friend_requests"

        const val EXTRA_CHANNEL_ID = "notification_channel_id"
        const val EXTRA_CLAN_ID = "notification_clan_id"
        const val EXTRA_CHANNEL_NAME = "notification_channel_name"
        const val EXTRA_CHANNEL_TYPE = "notification_channel_type"
        const val EXTRA_DM_ID = "notification_dm_id"
        const val EXTRA_FRIEND_REQUEST = "notification_friend_request"
        const val EXTRA_FRIEND_REQUEST_CONSUMED = "notification_friend_request_consumed"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_MESSAGE_ID = "notification_message_id"
        const val EXTRA_MESSAGE_SENDER_ID = "notification_message_sender_id"
        const val EXTRA_TOPIC_ID = "notification_topic_id"
        const val EXTRA_NOTIFICATION_TITLE = "notification_title"

        fun isFriendRequestNotification(title: String?, body: String?, data: Map<String, String> = emptyMap()): Boolean {
            val values = ArrayList<String>()
            title?.takeIf { it.isNotBlank() }?.let(values::add)
            body?.takeIf { it.isNotBlank() }?.let(values::add)
            for ((key, value) in data) {
                val normalizedKey = key.lowercase()
                if (normalizedKey in FRIEND_REQUEST_SIGNAL_KEYS || FRIEND_REQUEST_SIGNAL_KEYS.any { normalizedKey.contains(it) }) {
                    value.takeIf { it.isNotBlank() }?.let(values::add)
                }
            }
            return values.any { value ->
                val normalized = value.lowercase()
                FRIEND_REQUEST_PATTERNS.any { pattern -> normalized.contains(pattern) }
            }
        }

        fun isFriendRequestNotificationExtras(extras: Bundle?): Boolean {
            if (extras == null || extras.getBoolean(EXTRA_FRIEND_REQUEST_CONSUMED, false)) return false
            if (extras.getBoolean(EXTRA_FRIEND_REQUEST, false)) return true

            val data = LinkedHashMap<String, String>()
            collectBundleStrings(extras, data)
            val title = data["title"]
                ?: data["gcm.notification.title"]
                ?: data["google.c.a.c_l"]
            val body = data["body"]
                ?: data["message"]
                ?: data["gcm.notification.body"]
            return isFriendRequestNotification(title, body, data)
        }

        private fun collectBundleStrings(bundle: Bundle, out: MutableMap<String, String>, prefix: String = "") {
            for (key in bundle.keySet()) {
                val value = bundle.get(key) ?: continue
                val outKey = if (prefix.isEmpty()) key else "$prefix.$key"
                when (value) {
                    is Bundle -> collectBundleStrings(value, out, outKey)
                    is String -> out[outKey] = value
                    is Number, is Boolean -> out[outKey] = value.toString()
                }
            }
        }

        private val FRIEND_REQUEST_SIGNAL_KEYS = setOf(
            "type",
            "notification_type",
            "notificationtype",
            "event",
            "event_type",
            "eventtype",
            "action",
            "category",
            "category_name",
            "categoryname",
            "subject",
            "title",
            "body",
            "message",
            "content",
            "screen",
            "route",
            "link"
        )

        private val FRIEND_REQUEST_PATTERNS = listOf(
            "friend_request",
            "friend-request",
            "friend request",
            "friend.invite",
            "add_friend",
            "add-friend",
            "addfriend",
            "add friend",
            "request_friend",
            "wants to add you",
            "wants to be your friend",
            "sent you a friend request",
            "add you as a friend",
            "loi moi ket ban",
            "muon ket ban",
            "lời mời kết bạn",
            "muốn kết bạn"
        )
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
         if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

         val group = NotificationChannelGroup(
             GROUP_MESSAGES,
             context.getString(R.string.app_name)
         )
         notificationManager.createNotificationChannelGroup(group)

         val messageChannel = NotificationChannel(
             CHANNEL_MESSAGES,
             context.getString(R.string.notification_channel_messages),
             NotificationManager.IMPORTANCE_HIGH
         ).apply {
             description = context.getString(R.string.notification_channel_messages_desc)
             setGroup(GROUP_MESSAGES)
             enableVibration(true)
             vibrationPattern = longArrayOf(300, 500, 300, 500)
         }

         val dmChannel = NotificationChannel(
             CHANNEL_DM,
             context.getString(R.string.notification_channel_dm),
             NotificationManager.IMPORTANCE_HIGH
         ).apply {
             description = context.getString(R.string.notification_channel_dm_desc)
             setGroup(GROUP_MESSAGES)
             enableVibration(true)
             vibrationPattern = longArrayOf(300, 500, 300, 500)
         }

         val systemChannel = NotificationChannel(
             CHANNEL_SYSTEM,
             context.getString(R.string.notification_channel_system),
             NotificationManager.IMPORTANCE_DEFAULT
         ).apply {
             description = context.getString(R.string.notification_channel_system_desc)
         }

         notificationManager.createNotificationChannels(
             listOf(messageChannel, dmChannel, systemChannel)
         )
    }

    private data class ConversationMessage(
        val sender: String,
        val text: String,
        val timestamp: Long,
        val avatarUrl: String,
        val fromSelf: Boolean = false
    )

    private val avatarBitmaps = LruCache<String, Bitmap>(MAX_CACHED_AVATARS)

    private fun avatarRequestUrl(url: String): String = avatarImgproxyUrl(url, AVATAR_ICON_SIZE)

    private fun cachedAvatar(url: String): Bitmap? =
        if (url.isEmpty()) null else avatarBitmaps.get(avatarRequestUrl(url))

    private suspend fun cacheAvatarBitmap(url: String) {
        if (url.isEmpty()) return
        val requestUrl = avatarRequestUrl(url)
        if (avatarBitmaps.get(requestUrl) != null) return
        val bitmap = withTimeoutOrNull(MAX_AVATAR_DELAY) { loadAvatarBitmap(requestUrl) } ?: return
        val cropped = runCatching { circleCrop(bitmap) }.getOrNull() ?: return
        avatarBitmaps.put(requestUrl, cropped)
    }

    private suspend fun resolveClan(clanId: Long?): ClanEntity? {
        if (clanId == null || clanId == 0L) return null
        return clansController.get().findClanById(clanId)
    }

    private fun clanLetterAvatar(clanId: Long, clanName: String): Bitmap? = runCatching {
        val bitmap = Bitmap.createBitmap(AVATAR_ICON_SIZE, AVATAR_ICON_SIZE, Bitmap.Config.ARGB_8888)
        AvatarDrawable().apply {
            setInfo(clanId, clanName)
            setBounds(0, 0, AVATAR_ICON_SIZE, AVATAR_ICON_SIZE)
            draw(Canvas(bitmap))
        }
        bitmap
    }.getOrNull()

    private fun pushConversationShortcut(
        shortcutId: String,
        label: String,
        iconBitmap: Bitmap?,
        person: Person?,
        openIntent: Intent
    ): String? = runCatching {
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(label.ifEmpty { context.getString(R.string.app_name) })
            .setLongLived(true)
            .setCategories(setOf(SHORTCUT_CATEGORY_CONVERSATION))
            .setIntent(openIntent)
            .apply {
                if (iconBitmap != null) setIcon(IconCompat.createWithBitmap(iconBitmap))
                if (person != null) setPerson(person)
            }
            .build()
        if (ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)) shortcutId else null
    }.getOrNull()

    private fun applyConversationIdentity(
        builder: NotificationCompat.Builder,
        shortcutId: String?,
        conversationIcon: Bitmap?
    ) {
        if (shortcutId != null) {
            builder.setShortcutId(shortcutId)
            builder.setLocusId(LocusIdCompat(shortcutId))
        }
        if (conversationIcon != null) builder.setLargeIcon(conversationIcon)
    }

    private suspend fun loadAvatarBitmap(url: String): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            val request = try {
                MezonImageLoader.getInstance(context).load(
                    url = url,
                    reqWidth = AVATAR_ICON_SIZE,
                    reqHeight = AVATAR_ICON_SIZE,
                    onSuccess = { bitmap -> if (continuation.isActive) continuation.resume(bitmap) },
                    onError = { if (continuation.isActive) continuation.resume(null) }
                )
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resume(null)
                MezonImageLoader.Cancellable.EMPTY
            }
            continuation.invokeOnCancellation { request.cancel() }
        }

    private fun circleCrop(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        if (size <= 0) return source
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(
            source,
            -(source.width - size) / 2f,
            -(source.height - size) / 2f,
            paint
        )
        return output
    }

    private class ConversationNotification(
        var conversationTitle: String
    ) {
        val messages = ArrayList<ConversationMessage>()
        var lastPostedAt = 0L
    }

    private val conversations = HashMap<Int, ConversationNotification>()

    @Synchronized
    private fun recordConversationMessage(
        notificationId: Int,
        conversationTitle: String,
        message: ConversationMessage
    ): List<ConversationMessage> {
        dropDismissedConversations()
        val state = conversations.getOrPut(notificationId) {
            ConversationNotification(conversationTitle)
        }
        state.conversationTitle = conversationTitle
        state.lastPostedAt = System.currentTimeMillis()
        state.messages.add(message)
        state.messages.sortBy { it.timestamp }
        while (state.messages.size > MAX_HISTORY_MESSAGES) {
            state.messages.removeAt(0)
        }
        return ArrayList(state.messages)
    }

    @Synchronized
    private fun dropConversation(notificationId: Int) {
        conversations.remove(notificationId)
    }

    @Synchronized
    private fun dropAllConversations() {
        conversations.clear()
    }

    private fun dropDismissedConversations() {
        val activeIds = activeNotificationIds() ?: return
        val now = System.currentTimeMillis()
        conversations.entries.removeAll { (id, state) ->
            id !in activeIds && now - state.lastPostedAt > DISMISS_GRACE_MS
        }
    }

    private fun activeNotificationIds(): Set<Int>? = try {
        notificationManager.activeNotifications.mapTo(HashSet()) { it.id }
    } catch (e: Exception) {
        null
    }

    private fun senderFromTitle(title: String): String {
        val trimmed = title.trim()
        val match = SENDER_IN_TITLE_REGEX.find(trimmed) ?: return trimmed
        return match.groupValues[1].trim().ifEmpty { trimmed }
    }

    private fun buildMessagingStyle(
        conversationTitle: String,
        isGroupConversation: Boolean,
        history: List<ConversationMessage>
    ): NotificationCompat.MessagingStyle {
        val self = Person.Builder()
            .setName(context.getString(R.string.notification_self))
            .build()
        val style = NotificationCompat.MessagingStyle(self)
            .setGroupConversation(isGroupConversation)
        if (isGroupConversation && conversationTitle.isNotEmpty()) {
            style.setConversationTitle(conversationTitle)
        }
        for (message in history) {
            if (message.fromSelf) {
                style.addMessage(message.text, message.timestamp, null as Person?)
                continue
            }
            val person = Person.Builder()
                .setName(message.sender)
                .setKey(message.sender)
            cachedAvatar(message.avatarUrl)?.let {
                person.setIcon(IconCompat.createWithBitmap(it))
            }
            style.addMessage(message.text, message.timestamp, person.build())
        }
        return style
    }

    @Synchronized
    private fun refreshGroupSummary(addedId: Int? = null, removedId: Int? = null) {
        val active = try {
            notificationManager.activeNotifications
        } catch (e: Exception) {
            null
        } ?: return
        val childIds = active
            .filter { it.id != SUMMARY_NOTIFICATION_ID && it.notification.group == GROUP_MESSAGES }
            .mapTo(HashSet()) { it.id }
        if (addedId != null) childIds.add(addedId)
        if (removedId != null) childIds.remove(removedId)
        if (childIds.size < 2) {
            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
            return
        }
        val visible = conversations.entries
            .filter { it.key in childIds }
            .sortedByDescending { it.value.lastPostedAt }
        val messageCount = visible.sumOf { it.value.messages.size }.coerceAtLeast(childIds.size)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            SUMMARY_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val inboxStyle = NotificationCompat.InboxStyle()
            .setSummaryText(context.getString(R.string.app_name))
        for (entry in visible) {
            val state = entry.value
            val last = state.messages.lastOrNull() ?: continue
            val label = state.conversationTitle.ifEmpty { last.sender }
            inboxStyle.addLine("$label: ${last.text}")
        }
        val summary = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_color))
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_group_summary, messageCount))
            .setStyle(inboxStyle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_MESSAGES)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        notificationManager.notify(SUMMARY_NOTIFICATION_ID, summary)
    }

    private fun replyPendingIntentFlags(): Int {
        val mutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return mutability or PendingIntent.FLAG_UPDATE_CURRENT
    }

    private fun addMessageActions(
        builder: NotificationCompat.Builder,
        notificationId: Int,
        title: String,
        openChatIntent: Intent,
        channelId: Long,
        clanId: Long,
        channelName: String,
        channelType: Int,
        messageId: Long,
        messageSenderId: Long,
        topicId: Long
    ) {
        if (channelId == 0L) return

        val viewPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openChatIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        builder.addAction(
            NotificationCompat.Action.Builder(
                R.drawable.ic_notification,
                context.getString(R.string.notification_action_view),
                viewPendingIntent
            ).build()
        )

        val replyIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            action = NotificationReplyReceiver.ACTION_REPLY
            putExtra(EXTRA_CHANNEL_ID, channelId)
            putExtra(EXTRA_CLAN_ID, clanId)
            putExtra(EXTRA_CHANNEL_TYPE, channelType)
            putExtra(EXTRA_CHANNEL_NAME, channelName)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_NOTIFICATION_TITLE, title)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            replyIntent,
            replyPendingIntentFlags()
        )
        val remoteInput = RemoteInput.Builder(NotificationReplyReceiver.KEY_REPLY_TEXT)
            .setLabel(context.getString(R.string.notification_reply_hint))
            .build()
        builder.addAction(
            NotificationCompat.Action.Builder(
                R.drawable.ic_notification,
                context.getString(R.string.notification_action_reply),
                replyPendingIntent
            )
                .addRemoteInput(remoteInput)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .setAllowGeneratedReplies(false)
                .build()
        )

        val likeIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            action = NotificationReplyReceiver.ACTION_LIKE
            putExtra(EXTRA_CHANNEL_ID, channelId)
            putExtra(EXTRA_CLAN_ID, clanId)
            putExtra(EXTRA_CHANNEL_TYPE, channelType)
            putExtra(EXTRA_CHANNEL_NAME, channelName)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_NOTIFICATION_TITLE, title)
            putExtra(EXTRA_MESSAGE_ID, messageId)
            putExtra(EXTRA_MESSAGE_SENDER_ID, messageSenderId)
            putExtra(EXTRA_TOPIC_ID, topicId)
        }
        val likePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            likeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        builder.addAction(
            NotificationCompat.Action.Builder(
                R.drawable.ic_notification,
                context.getString(R.string.notification_action_like),
                likePendingIntent
            )
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_THUMBS_UP)
                .setShowsUserInterface(false)
                .build()
        )
    }

    private fun buildOpenChatIntent(
        channelId: Long,
        clanId: Long,
        channelName: String,
        channelType: Int
    ): Intent = Intent(context, MainActivity::class.java).apply {
        action = ACTION_OPEN_CHAT
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
        if (clanId != 0L) {
            putExtra(EXTRA_CHANNEL_ID, channelId)
            putExtra(EXTRA_CLAN_ID, clanId)
        } else {
            putExtra(EXTRA_DM_ID, channelId)
        }
        if (channelName.isNotEmpty()) putExtra(EXTRA_CHANNEL_NAME, channelName)
        if (channelType != 0) putExtra(EXTRA_CHANNEL_TYPE, channelType)
    }

    fun showSentMessageNotification(
        notificationId: Int,
        title: String,
        channelId: Long,
        clanId: Long,
        channelName: String,
        channelType: Int,
        sentText: String,
        messageId: Long = 0L,
        messageSenderId: Long = 0L,
        topicId: Long = 0L
    ) {
        val conversationTitle = channelName.ifEmpty { title }
        val history = recordConversationMessage(
            notificationId = notificationId,
            conversationTitle = conversationTitle,
            message = ConversationMessage(
                sender = context.getString(R.string.notification_self),
                text = sentText,
                timestamp = System.currentTimeMillis(),
                avatarUrl = "",
                fromSelf = true
            )
        )
        val intent = buildOpenChatIntent(channelId, clanId, channelName, channelType)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notifChannel = if (clanId == 0L) CHANNEL_DM else CHANNEL_MESSAGES
        val builder = NotificationCompat.Builder(context, notifChannel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_color))
            .setContentTitle(title.ifEmpty { context.getString(R.string.app_name) })
            .setContentText(sentText)
            .setStyle(
                buildMessagingStyle(
                    conversationTitle,
                    clanId != 0L || channelType == CHANNEL_TYPE_GROUP,
                    history
                )
            )
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setNumber(history.size)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_MESSAGES)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        applyConversationIdentity(builder, "chat_$channelId", null)
        addMessageActions(
            builder = builder,
            notificationId = notificationId,
            title = title,
            openChatIntent = intent,
            channelId = channelId,
            clanId = clanId,
            channelName = channelName,
            channelType = channelType,
            messageId = messageId,
            messageSenderId = messageSenderId,
            topicId = topicId
        )
        notificationManager.notify(notificationId, builder.build())
        refreshGroupSummary(addedId = notificationId)
    }

    fun showReplyFailedNotification(
        notificationId: Int,
        title: String,
        channelId: Long,
        clanId: Long,
        channelName: String,
        channelType: Int,
        failureTextRes: Int = R.string.notification_reply_failed,
        messageId: Long = 0L,
        messageSenderId: Long = 0L,
        topicId: Long = 0L
    ) {
        val failureText = context.getString(failureTextRes)
        val intent = buildOpenChatIntent(channelId, clanId, channelName, channelType)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notifChannel = if (clanId == 0L) CHANNEL_DM else CHANNEL_MESSAGES
        val builder = NotificationCompat.Builder(context, notifChannel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_color))
            .setContentTitle(title.ifEmpty { context.getString(R.string.app_name) })
            .setContentText(failureText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(failureText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_MESSAGES)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        addMessageActions(
            builder = builder,
            notificationId = notificationId,
            title = title,
            openChatIntent = intent,
            channelId = channelId,
            clanId = clanId,
            channelName = channelName,
            channelType = channelType,
            messageId = messageId,
            messageSenderId = messageSenderId,
            topicId = topicId
        )
        notificationManager.notify(notificationId, builder.build())
        refreshGroupSummary(addedId = notificationId)
    }

    fun showMessageNotification(
        title: String,
        body: String,
        channelId: Long? = null,
        clanId: Long? = null,
        channelName: String = "",
        channelType: Int? = null,
        canReply: Boolean = true,
        senderName: String = "",
        avatarUrl: String = "",
        timestamp: Long = System.currentTimeMillis(),
        messageId: Long = 0L,
        messageSenderId: Long = 0L,
        topicId: Long = 0L
    ) {
        val body = truncateBody(body)
        appScope.launch {
            val conversationClanId = clanId ?: 0L
            val clan = resolveClan(clanId)
            val conversationAvatarUrl =
                if (conversationClanId != 0L) clan?.logo.orEmpty() else avatarUrl
            val senderReady = async { cacheAvatarBitmap(avatarUrl) }
            val conversationReady = async { cacheAvatarBitmap(conversationAvatarUrl) }
            val computedChannelName = channelName.ifEmpty {
                if (channelId != null && clanId != null) {
                    (withTimeoutOrNull(MAX_NOTI_DELAY) {
                        channelController.findOrFetchChannelLabel(channelId, clanId)
                    } ?: title).ifEmpty { title }
                } else title
            }
            val notificationId = channelId?.toInt() ?: System.nanoTime().toInt().and(0x7FFFFFFF)
            val intent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_CHAT
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (channelId != null) putExtra(EXTRA_CHANNEL_ID, channelId)
                if (clanId != null) putExtra(EXTRA_CLAN_ID, clanId)
                if (computedChannelName.isNotEmpty()) putExtra(EXTRA_CHANNEL_NAME, computedChannelName)
                if (channelType != null) putExtra(EXTRA_CHANNEL_TYPE, channelType)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notifChannel = if (clanId == 0L) CHANNEL_DM else CHANNEL_MESSAGES
            val conversationTitle = computedChannelName.ifEmpty { title }
            senderReady.await()
            conversationReady.await()
            val conversationIcon = cachedAvatar(conversationAvatarUrl)
                ?: if (conversationClanId != 0L) {
                    clanLetterAvatar(
                        conversationClanId,
                        clan?.clanName.orEmpty().ifEmpty { conversationTitle }
                    )
                } else {
                    null
                }
            val shortcutId = channelId?.let {
                pushConversationShortcut(
                    shortcutId = "chat_$it",
                    label = conversationTitle,
                    iconBitmap = conversationIcon,
                    person = null,
                    openIntent = intent
                )
            }
            val history = recordConversationMessage(
                notificationId = notificationId,
                conversationTitle = conversationTitle,
                message = ConversationMessage(
                    sender = senderName.ifEmpty { senderFromTitle(title) },
                    text = body,
                    timestamp = timestamp,
                    avatarUrl = avatarUrl
                )
            )
            val builder = NotificationCompat.Builder(context, notifChannel)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.notification_color))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(buildMessagingStyle(conversationTitle, true, history))
                .setWhen(timestamp)
                .setShowWhen(true)
                .setNumber(history.size)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setGroup(GROUP_MESSAGES)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVibrate(longArrayOf(300, 500, 300, 500))
            applyConversationIdentity(builder, shortcutId, conversationIcon)
            if (canReply && channelId != null) {
                addMessageActions(
                    builder = builder,
                    notificationId = notificationId,
                    title = title,
                    openChatIntent = intent,
                    channelId = channelId,
                    clanId = clanId ?: 0L,
                    channelName = computedChannelName,
                    channelType = channelType ?: CHANNEL_TYPE_CHANNEL,
                    messageId = messageId,
                    messageSenderId = messageSenderId,
                    topicId = topicId
                )
            }
            notificationManager.notify(notificationId, builder.build())
            refreshGroupSummary(addedId = notificationId)
        }
    }

    fun showDmNotification(
        title: String,
        body: String,
        dmChannelId: Long,
        canReply: Boolean = true,
        senderName: String = "",
        avatarUrl: String = "",
        timestamp: Long = System.currentTimeMillis(),
        messageId: Long = 0L,
        messageSenderId: Long = 0L,
        topicId: Long = 0L
    ) {
        val body = truncateBody(body)
        appScope.launch {
            val senderReady = async { cacheAvatarBitmap(avatarUrl) }
            val dmDialog = dialogsController.get().getDialog(dmChannelId)
            val dmName = dmDialog?.let { dm ->
                dm.displayName.ifEmpty { dm.label }
            } ?: title
            val dmType = dmDialog?.type?.takeIf { it != 0 } ?: CHANNEL_TYPE_DM
            val intent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_CHAT
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_DM_ID, dmChannelId)
                if (dmName.isNotEmpty()) putExtra(EXTRA_CHANNEL_NAME, dmName)
                putExtra(EXTRA_CHANNEL_TYPE, dmType)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                dmChannelId.toInt(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val isGroupConversation = dmType == CHANNEL_TYPE_GROUP
            val conversationTitle = dmName.ifEmpty { title }
            val conversationAvatarUrl = dmDialog?.avatarUrl.orEmpty().ifEmpty { avatarUrl }
            val conversationReady = async { cacheAvatarBitmap(conversationAvatarUrl) }
            senderReady.await()
            conversationReady.await()
            val conversationIcon = cachedAvatar(conversationAvatarUrl)
            val shortcutId = pushConversationShortcut(
                shortcutId = "chat_$dmChannelId",
                label = conversationTitle,
                iconBitmap = conversationIcon,
                person = Person.Builder()
                    .setName(conversationTitle)
                    .setKey("chat_$dmChannelId")
                    .apply {
                        if (conversationIcon != null) {
                            setIcon(IconCompat.createWithBitmap(conversationIcon))
                        }
                    }
                    .build(),
                openIntent = intent
            )
            val history = recordConversationMessage(
                notificationId = dmChannelId.toInt(),
                conversationTitle = conversationTitle,
                message = ConversationMessage(
                    sender = senderName.ifEmpty {
                        if (isGroupConversation) senderFromTitle(title) else conversationTitle
                    },
                    text = body,
                    timestamp = timestamp,
                    avatarUrl = avatarUrl
                )
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_DM)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.notification_color))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(buildMessagingStyle(conversationTitle, isGroupConversation, history))
                .setWhen(timestamp)
                .setShowWhen(true)
                .setNumber(history.size)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setGroup(GROUP_MESSAGES)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVibrate(longArrayOf(300, 500, 300, 500))
            applyConversationIdentity(builder, shortcutId, conversationIcon)
            if (canReply) {
                addMessageActions(
                    builder = builder,
                    notificationId = dmChannelId.toInt(),
                    title = title,
                    openChatIntent = intent,
                    channelId = dmChannelId,
                    clanId = 0L,
                    channelName = dmName,
                    channelType = dmType,
                    messageId = messageId,
                    messageSenderId = messageSenderId,
                    topicId = topicId
                )
            }
            notificationManager.notify(dmChannelId.toInt(), builder.build())
            refreshGroupSummary(addedId = dmChannelId.toInt())
        }
    }

    fun showFriendRequestNotification(title: String, body: String) {
        val truncatedBody = truncateBody(body)
        val notificationId = System.nanoTime().toInt().and(0x7FFFFFFF)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_FRIEND_REQUESTS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_FRIEND_REQUEST, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_DM)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_color))
            .setContentTitle(title)
            .setContentText(truncatedBody)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_MESSAGES)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setVibrate(longArrayOf(300, 500, 300, 500))
            .build()
        notificationManager.notify(notificationId, notification)
        refreshGroupSummary(addedId = notificationId)
    }

    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
        dropConversation(notificationId)
        refreshGroupSummary(removedId = notificationId)
    }

    fun cancelAllNotifications() {
        notificationManager.cancelAll()
        dropAllConversations()
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }
    }

    fun showInAppToast(
        title: String,
        body: String,
        channelId: Long = 0L,
        clanId: Long = 0L,
        dmId: Long = 0L,
        friendRequest: Boolean = false
    ) {
        val truncatedBody = truncateBody(body)
        appScope.launch {
            val dmDialog = if (dmId != 0L) dialogsController.get().getDialog(dmId) else null
            withContext(Dispatchers.Main) {
                val activity = MainActivity.instance ?: return@withContext
                val onTap: (() -> Unit)? = when {
                    friendRequest -> {
                        {
                            activity.openFriendRequestsFromNotification()
                        }
                    }
                    dmId != 0L -> {
                        val dmType = dmDialog?.type?.takeIf { it != 0 } ?: CHANNEL_TYPE_DM
                        val dmName = dmDialog?.let { dm ->
                            dm.displayName.ifEmpty { dm.label }
                        } ?: title
                        {
                            activity.openChat(dmId, dmName, 0L, dmType, fromNotification = true)
                        }
                    }
                    clanId != 0L && channelId != 0L -> {
                        {
                            appScope.launch {
                                val channelName = (withTimeoutOrNull(MAX_NOTI_DELAY) {
                                    channelController.findOrFetchChannelLabel(channelId, clanId)
                                } ?: title).ifEmpty { title }
                                withContext(Dispatchers.Main) {
                                    activity.openChat(
                                        channelId,
                                        channelName,
                                        clanId,
                                        CHANNEL_TYPE_CHANNEL,
                                        fromNotification = true
                                    )
                                }
                            }
                        }
                    }
                    else -> null
                }
                activity.drawerLayoutContainer.post {
                    ToastOverlay.showInAppNotification(
                        activity = activity,
                        parent = activity.drawerLayoutContainer,
                        title = title,
                        body = truncatedBody,
                        onTap = onTap
                    )
                }
            }
        }
    }
}
