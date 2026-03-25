package com.mezon.mobile.home

import android.util.LongSparseArray
import android.util.Log
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.data.db.MessageDao
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.chat.EmojiRepository
import com.mezon.mobile.home.chat.IEmoji
import com.mezon.mobile.home.chat.AttachmentPickerItem
import com.mezon.mobile.home.chat.MessageEntity
import com.mezon.mobile.home.chat.MessageReactions
import com.mezon.mobile.home.chat.WriteMessageReactionArgs
import com.mezon.mobile.home.chat.getSrcEmoji
import com.mezon.mobile.home.chat.toMessageEntity
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.CODE_CHAT_REMOVE
import com.mezon.mobile.network.CODE_CHAT_UPDATE
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.MezonSocket
import com.mezon.mobile.network.NetworkMonitor
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.network.channelTypeToStreamMode
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.util.buildTextContent
import com.mezon.mezon.api.MessageAttachment
import com.mezon.mezon.api.messageAttachment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mezon.mobile.network.ConnectionState
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ChatController"
private const val PAGE_SIZE = 50
private const val DIRECTION_AFTER = 1
private const val DIRECTION_AROUND = 2
private const val DIRECTION_BEFORE = 3

@Singleton
class ChatController @Inject constructor(
    private val api: MezonApi,
    private val mezonSocket: MezonSocket,
    private val messageDao: MessageDao,
    private val socketEventDispatcher: SocketEventDispatcher,
    private val sessionManager: SessionManager,
    private val notificationCenter: NotificationCenter,
    private val networkMonitor: NetworkMonitor,
    private val cacheTracker: ApiCacheTracker,
    private val dialogsController: DialogsController,
    private val emojiRepository: EmojiRepository,
    private val channelController: dagger.Lazy<com.mezon.mobile.home.clans.ChannelController>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    val dialogMessage = LongSparseArray<MessageEntity>()
    private val initialFetchDone = HashSet<Long>()
    private val lastMessageByChannel = LongSparseArray<Long>()  // channelId → newest messageId

    // In-memory reaction store: key = messageId
    // Access only on Dispatchers.Main (post notification) or under synchronized
    private val reactionStore = HashMap<Long, MessageReactions>()

    // Coroutine queue cho reactions — xử lý tuần tự, tránh race condition
    private val reactionQueue = Channel<WriteMessageReactionArgs>(capacity = Channel.UNLIMITED)

    @Volatile private var cachedCurrentUserId = 0L
    private val cachedUserIdString: String
        get() = if (cachedCurrentUserId != 0L) cachedCurrentUserId.toString() else ""

    init {
        appScope.launch { observeIncomingMessages() }
        appScope.launch(ioDispatcher) { processReactionQueue() }
        appScope.launch { observeIncomingReactions() }
        appScope.launch {
            val session = sessionManager.sessionFlow.first { it != null }
            cachedCurrentUserId = session?.userId?.toLongOrNull() ?: 0L
        }
    }

    fun openChannel(channelId: Long, clanId: Long, channelType: Int) {
        val isPublic = channelType != CHANNEL_TYPE_DM
        appScope.launch(ioDispatcher) {
            val session = sessionManager.sessionFlow.first { it != null }
            cachedCurrentUserId = session?.userId?.toLongOrNull() ?: 0L
        }
    }

    /** Returns the newest known messageId for [channelId], 0 if unknown. */
    fun getLastMessageId(channelId: Long): Long =
        synchronized(this) { lastMessageByChannel.get(channelId, 0L) }

    private fun updateLastMessageByChannel(channelId: Long, messages: List<MessageEntity>, latestIdFromResponse: Long = 0L) {
        // Prefer the server-provided lastSentMessage (like RN's response.last_sent_message)
        // Fall back to the max id from the loaded messages batch
        val fromServer = if (latestIdFromResponse > 0L) latestIdFromResponse else null
        val fromMessages = messages.maxOfOrNull { it.id }
        val newestId = fromServer ?: fromMessages ?: return
        synchronized(this) {
            if (newestId > lastMessageByChannel.get(channelId, 0L))
                lastMessageByChannel.put(channelId, newestId)
        }
    }

    fun cleanup() {
        synchronized(this) {
            dialogMessage.clear()
            initialFetchDone.clear()
            lastMessageByChannel.clear()
            cachedCurrentUserId = 0L
        }
    }

    fun openChannel(channelId: Long, clanId: Long, channelType: Int, isChannelPrivate: Boolean = false) {
        val isPublic = !isChannelPrivate
        appScope.launch {
            try {
                mezonSocket.joinChat(clanId, channelId, channelType, isPublic)
                Log.d(TAG, "Joined channel $channelId (clanId=$clanId type=$channelType isPublic=$isPublic)")
            } catch (e: Exception) {
                Log.e(TAG, "joinChat failed channelId=$channelId", e)
            }
        }
    }

    /** Trả về userId hiện tại (từ cached session). Trả về 0 nếu chưa có session. */
    fun getCurrentUserId(): Long {
        if (cachedCurrentUserId != 0L) return cachedCurrentUserId
        appScope.launch(ioDispatcher) {
            val session = sessionManager.sessionFlow.first()
            cachedCurrentUserId = session?.userId?.toLongOrNull() ?: 0L
        }
        return cachedCurrentUserId
    }

    fun loadMessages(channelId: Long, clanId: Long) {
    fun loadMessages(channelId: Long, clanId: Long, forceRefresh: Boolean = false) {
        appScope.launch(ioDispatcher) {
            try {
                val cacheKey = apiCacheKey("fetchMessages", clanId, channelId)
                if (forceRefresh) cacheTracker.invalidate(cacheKey)

                val fromDb = messageDao.getLatestByChannel(channelId, PAGE_SIZE)
                if (fromDb.isNotEmpty()) {
                    Log.d(TAG, "Loaded ${fromDb.size} cached messages for channel $channelId")
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesDidLoad, channelId, ArrayList(fromDb), true, false, true
                    )
                }

                if (!networkMonitor.isOnline.value) {
                    Log.d(TAG, "Offline — showing cached messages for channel $channelId")
                    return@launch
                }

                if (fromDb.isNotEmpty() && cacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP) {
                    Log.d(TAG, "Cache valid for channel $channelId, skipping API")
                    return@launch
                }

                sessionManager.withAutoRefresh { session ->
                    val currentUserId = session.userId.toLongOrNull() ?: 0L
                    val response = api.listChannelMessages(
                        session.apiUrl, session.token, channelId, clanId, limit = PAGE_SIZE
                    )
                    val allMessages = response.messagesList.map { it.toMessageEntity(currentUserId) }
                    val firstMessageReached = allMessages.any { it.code == MessageEntity.CODE_FIRST_MESSAGE }
                    val hasMoreTop = !firstMessageReached

                    val messages = allMessages
                        .filter { it.isRenderable }
                        .sortedBy { it.id }

                    // Parse reactions từ proto và load vào reactionStore
                    parseAndStoreReactions(response.messagesList)

                    messageDao.deleteByChannel(channelId)
                    messageDao.upsertAll(messages)
                    messageDao.trimToLatest(channelId, PAGE_SIZE * 4)
                    synchronized(this@ChatController) { initialFetchDone.add(channelId) }
                    val serverLastSentId = if (response.hasLastSentMessage()) response.lastSentMessage.id else 0L
                    updateLastMessageByChannel(channelId, messages, serverLastSentId)
                    Log.d(TAG, "loadMessages hasMoreTop=$hasMoreTop firstMessageReached=$firstMessageReached size=${response.messagesList.size} hasLastSentMessage=${response.hasLastSentMessage()} serverLastSentId=$serverLastSentId")
                    // arg[5] = serverLastSeenId — embedded inline so ChatFragment reads it
                    // BEFORE insertUnreadDividerIfNeeded (fixes race condition)
                    val serverLastSeenId = if (response.hasLastSeenMessage()) response.lastSeenMessage.id else 0L
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesDidLoad, channelId, ArrayList(messages), hasMoreTop, false, false, serverLastSeenId
                    )
                    // Notify UI về reactions đã parse từ server (để hiện reaction chips ngay)
                    notifyRestoredReactions(channelId)
                    cacheTracker.markCalled(cacheKey)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadMessages failed for channel $channelId", e)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.messagesLoadError, channelId, e.message ?: "Failed to load"
                )
            }
        }
    }

    fun loadMessagesAround(channelId: Long, clanId: Long, anchorMessageId: Long) {
        appScope.launch(ioDispatcher) {
            try {
                val cacheKey = apiCacheKey("fetchMessages", clanId, channelId)

                val fromDb = messageDao.getMessagesAround(channelId, anchorMessageId, PAGE_SIZE / 2)
                var anchorInDb = false
                if (fromDb.isNotEmpty()) {
                    val dbMinId = fromDb.minOf { it.id }
                    val dbMaxId = fromDb.maxOf { it.id }
                    anchorInDb = anchorMessageId in dbMinId..dbMaxId
                    if (anchorInDb) {
                        val lastKnown = synchronized(this@ChatController) { lastMessageByChannel.get(channelId, 0L) }
                        val hasMoreBottom = lastKnown > 0L && dbMaxId < lastKnown
                        Log.d(TAG, "loadMessagesAround: DB hit anchor=$anchorMessageId range=$dbMinId..$dbMaxId hasMoreBottom=$hasMoreBottom count=${fromDb.size}")
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.messagesDidLoad, channelId, ArrayList(fromDb), true, hasMoreBottom, true
                        )
                    } else {
                        Log.d(TAG, "loadMessagesAround: DB miss anchor=$anchorMessageId not in range=$dbMinId..$dbMaxId, waiting for API")
                    }
                }

                if (!networkMonitor.isOnline.value) {
                    if (!anchorInDb && fromDb.isNotEmpty()) {
                        Log.d(TAG, "Offline — anchor not in DB, showing latest cached as fallback")
                        val fallback = messageDao.getLatestByChannel(channelId, PAGE_SIZE)
                        if (fallback.isNotEmpty()) {
                            notificationCenter.postNotificationOnMainThread(
                                NotificationCenter.messagesDidLoad, channelId, ArrayList(fallback), true, false, true
                            )
                        }
                    } else if (fromDb.isEmpty()) {
                        Log.d(TAG, "Offline — no cached messages for channel $channelId (around)")
                    }
                    return@launch
                }

                if (anchorInDb && cacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP) {
                    Log.d(TAG, "Cache valid for channel $channelId (around), skipping API")
                    return@launch
                }

                sessionManager.withAutoRefresh { session ->
                    val currentUserId = session.userId.toLongOrNull() ?: 0L
                    val response = api.listChannelMessages(
                        session.apiUrl, session.token, channelId, clanId,
                        anchorMessageId, DIRECTION_AROUND, PAGE_SIZE
                    )
                    val allMsgs = response.messagesList.map { it.toMessageEntity(currentUserId) }
                    val firstMessageReached = allMsgs.any { it.code == MessageEntity.CODE_FIRST_MESSAGE }
                    val hasMoreTop = !firstMessageReached

                    val msgs = allMsgs
                        .filter { it.isRenderable }
                        .sortedBy { it.id }

                    // Parse reactions từ proto và load vào reactionStore
                    parseAndStoreReactions(response.messagesList)

                    if (msgs.isNotEmpty()) {
                        messageDao.upsertAll(msgs)
                        messageDao.trimToLatest(channelId, PAGE_SIZE * 4)
                        synchronized(this@ChatController) { initialFetchDone.add(channelId) }
                        val serverLastSentId = if (response.hasLastSentMessage()) response.lastSentMessage.id else 0L
                        updateLastMessageByChannel(channelId, msgs, serverLastSentId)
                        Log.d(TAG, "loadMessagesAround: anchor=$anchorMessageId count=${msgs.size} hasMoreTop=$hasMoreTop firstMessageReached=$firstMessageReached hasLastSentMessage=${response.hasLastSentMessage()} serverLastSentId=$serverLastSentId")
                        val serverLastSeenId = if (response.hasLastSeenMessage()) response.lastSeenMessage.id else 0L
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.messagesDidLoad, channelId, ArrayList(msgs), hasMoreTop, true, false, serverLastSeenId
                        )
                    }
                    cacheTracker.markCalled(cacheKey)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadMessagesAround failed for channel $channelId", e)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.messagesLoadError, channelId, e.message ?: "Failed to load"
                )
            }
        }
    }

    fun loadMoreBottom(channelId: Long, clanId: Long, newestMessageId: Long) {
        Log.d(TAG, "loadMoreBottom channelId=$channelId newestMessageId=$newestMessageId")
        appScope.launch(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
                    val currentUserId = session.userId.toLongOrNull() ?: 0L
                    val response = api.listChannelMessages(
                        session.apiUrl, session.token, channelId, clanId,
                        newestMessageId, DIRECTION_AFTER, PAGE_SIZE
                    )
                    val newer = response.messagesList
                        .map { it.toMessageEntity(currentUserId) }
                        .filter { it.isRenderable }
                        .sortedBy { it.id }

                    val hasMoreBottom = response.messagesList.size >= PAGE_SIZE
                    val serverLastSentId = if (response.hasLastSentMessage()) response.lastSentMessage.id else 0L
                    updateLastMessageByChannel(channelId, newer, serverLastSentId)
                    Log.d(TAG, "loadMoreBottom: count=${newer.size} hasMoreBottom=$hasMoreBottom hasLastSentMessage=${response.hasLastSentMessage()} serverLastSentId=$serverLastSentId")
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesDidLoad, channelId, ArrayList(newer), false, hasMoreBottom, false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadMoreBottom failed", e)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.messagesLoadError, channelId, e.message ?: "Load more failed"
                )
            }
        }
    }

    fun loadMoreTop(channelId: Long, clanId: Long, oldestMessageId: Long) {
        Log.d(TAG, "loadMoreTop channelId=$channelId oldestMessageId=$oldestMessageId")
        appScope.launch(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
                    val currentUserId = session.userId.toLongOrNull() ?: 0L
                    val response = api.listChannelMessages(
                        session.apiUrl, session.token, channelId, clanId,
                        oldestMessageId, DIRECTION_BEFORE, PAGE_SIZE
                    )
                    val allOlder = response.messagesList.map { it.toMessageEntity(currentUserId) }
                    val firstMessageReached = allOlder.any { it.code == MessageEntity.CODE_FIRST_MESSAGE }
                    val hasMoreTop = !firstMessageReached

                    val older = allOlder
                        .filter { it.isRenderable }
                        .sortedBy { it.timestampSeconds }
                    Log.d(TAG, "loadMoreTop returned ${older.size} hasMoreTop=$hasMoreTop firstMessageReached=$firstMessageReached")
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesDidLoad, channelId, ArrayList(older), hasMoreTop, false, false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadMoreTop failed", e)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.messagesLoadError, channelId, e.message ?: "Load more failed"
                )
            }
        }
    }

    fun updateLastSeenMessage(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        messageId: Long,
        timestampSeconds: Int,
        badgeCount: Int = 0
    ) {
        val mode = channelTypeToStreamMode(channelType)
        if (badgeCount == 0) {
            if (clanId != 0L) {
                channelController.get().markChannelAsRead(channelId)
            } else {
                dialogsController.markDialogAsRead(channelId)
            }
        } else {
            if (clanId != 0L) {
                channelController.get().updateChannelLastSeen(channelId, messageId, badgeCount)
            } else {
                dialogsController.updateDialogLastSeen(channelId, messageId, badgeCount)
            }
        }
        appScope.launch {
            try {
                mezonSocket.writeLastSeenMessage(clanId, channelId, mode, messageId, timestampSeconds, badgeCount)
                Log.d(TAG, "Updated lastSeen: channelId=$channelId messageId=$messageId badgeCount=$badgeCount")
            } catch (e: Exception) {
                Log.e(TAG, "updateLastSeenMessage failed", e)
            }
        }
    }

    fun sendMessage(channelId: Long, clanId: Long, channelType: Int, isChannelPrivate: Boolean, text: String) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val content = buildTextContent(text)
        appScope.launch {
            try {
                mezonSocket.writeChatMessage(clanId, channelId, mode, isPublic, content)
                Log.d(TAG, "Message sent: channelId=$channelId isPublic=$isPublic")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
            }
        }
    }

    fun sendMessageWithAttachments(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        isChannelPrivate: Boolean,
        text: String,
        attachments: List<AttachmentPickerItem>,
        contentResolver: android.content.ContentResolver
    ) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val content = if (text.isNotBlank()) buildTextContent(text) else "{\"t\":\"\"}"

        appScope.launch(ioDispatcher) {
            try {
                val session = sessionManager.sessionFlow.first() ?: return@launch
                val cdnBaseUrl = BuildConfig.MEZON_BASE_IMG_URL
                val uploadedAttachments = ArrayList<MessageAttachment>()

                for (item in attachments) {
                    try {
                        val timestamp = System.currentTimeMillis() / 1000
                        val sanitizedName = item.filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        val uploadFilename = "${timestamp}_$sanitizedName"

                        val presignResult = api.uploadAttachmentFile(
                            session.apiUrl, session.token,
                            uploadFilename, item.mimeType,
                            item.size.toInt(), item.width, item.height
                        )

                        val fileBytes = contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
                        if (fileBytes == null) {
                            Log.e(TAG, "Failed to read file: ${item.filename}")
                            continue
                        }

                        api.putFileToPresignedUrl(presignResult.url, fileBytes, item.mimeType)

                        val cdnUrl = "$cdnBaseUrl/${presignResult.filename}"
                        val attachment = messageAttachment {
                            this.filename = item.filename
                            this.url = cdnUrl
                            this.filetype = item.mimeType
                            this.size = item.size.toInt()
                            this.width = item.width
                            this.height = item.height
                            if (item.duration > 0) this.duration = item.duration
                        }
                        uploadedAttachments.add(attachment)
                        Log.d(TAG, "Uploaded: ${item.filename} → $cdnUrl")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to upload attachment: ${item.filename}", e)
                    }
                }

                if (uploadedAttachments.isNotEmpty()) {
                    mezonSocket.writeChatMessage(
                        clanId, channelId, mode, isPublic, content,
                        attachments = uploadedAttachments
                    )
                    Log.d(TAG, "Message with ${uploadedAttachments.size} attachments sent: channelId=$channelId")
                } else {
                    Log.e(TAG, "No attachments uploaded successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message with attachments", e)
            }
        }
    }

    fun getCurrentUserIdString(): String = cachedUserIdString

    fun deleteMessage(channelId: Long, clanId: Long, channelType: Int, isChannelPrivate: Boolean, messageId: Long) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        appScope.launch {
            try {
                mezonSocket.removeChatMessage(clanId, channelId, mode, isPublic, messageId)
                Log.d(TAG, "Message deleted: channelId=$channelId messageId=$messageId isPublic=$isPublic")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete message", e)
            }
        }
    }

    private suspend fun observeIncomingMessages() {
        val currentUserId = sessionManager.sessionFlow
            .first { it != null }?.userId?.toLongOrNull() ?: 0L

        socketEventDispatcher.channelMessages.collect { msg ->
            val entity = msg.toMessageEntity(currentUserId)

            when (msg.code) {
                CODE_CHAT_UPDATE -> {
                    appScope.launch { messageDao.upsert(entity) }
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messageDidUpdate, entity.channelId, entity,
                        NotificationCenter.UPDATE_MASK_MESSAGE_TEXT
                    )
                }
                CODE_CHAT_REMOVE -> {
                    appScope.launch { messageDao.delete(msg.channelId, msg.messageId) }
                    synchronized(this) {
                        if (lastMessageByChannel.get(msg.channelId, 0L) == msg.messageId) {
                            appScope.launch(ioDispatcher) {
                                val newLast = messageDao.getLatestByChannel(msg.channelId, 1).firstOrNull()
                                synchronized(this@ChatController) {
                                    if (newLast != null) lastMessageByChannel.put(msg.channelId, newLast.id)
                                    else lastMessageByChannel.delete(msg.channelId)
                                }
                            }
                        }
                    }
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messageDidDelete, msg.channelId, msg.messageId
                    )
                }
                else -> {
                    if (!entity.isRenderable) return@collect
                    appScope.launch { messageDao.upsert(entity) }
                    synchronized(this) {
                        dialogMessage.put(entity.channelId, entity)
                        if (entity.id > lastMessageByChannel.get(entity.channelId, 0L))
                            lastMessageByChannel.put(entity.channelId, entity.id)
                    }
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.didReceiveNewMessages, entity.channelId, entity
                    )
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_NEW_MESSAGE
                    )
                }
            }

            dialogsController.updateOnNewMessage(msg, currentUserId)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Reaction store (in-memory, per-message)
    // ──────────────────────────────────────────────────────────────────────

    /** Trả về MessageReactions của 1 message (copy an toàn để render). */
    fun getReactions(messageId: Long): MessageReactions? {
        return synchronized(reactionStore) { reactionStore[messageId]?.copy() }
    }

    /**
     * Quan sát WS MessageReaction events, cập nhật store và thông báo UI.
     * MessageReaction proto fields (từ SocketEventDispatcher):
     *   messageId, channelId, senderId, emoji, emojiId, count, actionDelete
     */
    private suspend fun observeIncomingReactions() {
        val myUserId = sessionManager.sessionFlow.first { it != null }?.userId ?: ""
        socketEventDispatcher.messageReactions.collect { event ->
            val messageId  = event.messageId
            val channelId  = event.channelId
            val shortname  = event.emoji.trim()          // ví dụ: ":thumbsup:"
            val senderId   = event.senderId.toString()
            val senderName = event.senderName.ifBlank { senderId }
            val count      = event.count.coerceAtLeast(1)
            val del        = event.action                // proto field 'action' = true khi xóa

            // Dùng emojiId Long nếu có, fallback sang shortname để làm key gom nhóm
            // Khi Android gửi lên shortname-only (emojiId=0L), server echo lại emojiId=0
            // → dùng shortname làm key để gom cùng nhóm với phía desktop
            val emojiIdKey = if (event.emojiId != 0L) event.emojiId.toString() else shortname.ifBlank { "0" }

            val reactions = synchronized(reactionStore) {
                val r = reactionStore.getOrPut(messageId) { MessageReactions() }
                r.applyEvent(emojiIdKey, shortname, senderId, senderName, count, del,
                    isIdempotent = true)  // WS echo: không đếm 2 lần nếu optimistic đã apply
                if (r.isEmpty()) {
                    reactionStore.remove(messageId)
                    null
                } else {
                    r.copy()
                }
            }

            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.messageReactionsDidUpdate,
                channelId, messageId, reactions ?: MessageReactions()
            )
            Log.d(TAG, "Reaction event: msgId=$messageId emojiKey=$emojiIdKey del=$del senderId=$senderId")
        }
    }

    /**
     * Parse reactions bytes từ ChannelMessage proto list và populate reactionStore.
     * Được gọi sau khi load messages từ server để restore reactions khi mở lại app.
     */
    private fun parseAndStoreReactions(protoMessages: List<com.mezon.mezon.api.ChannelMessage>) {
        synchronized(reactionStore) {
            for (msg in protoMessages) {
                if (msg.reactions.isEmpty) continue
                val messageId = msg.messageId
                try {
                    val reactionList = com.mezon.mezon.api.MessageReactionList.parseFrom(msg.reactions)
                    if (reactionList.getReactionsList().isEmpty()) continue
                    val reactions = reactionStore.getOrPut(messageId) { MessageReactions() }
                    for (r in reactionList.getReactionsList()) {
                        if (r.action) continue  // skip delete actions
                        val count = r.count.coerceAtLeast(1)
                        val senderId = r.senderId.toString()
                        val senderName = r.senderName.ifBlank { senderId }
                        // Dùng Long emojiId làm key nếu có, fallback shortname
                        val emojiKey = if (r.emojiId != 0L) r.emojiId.toString() else r.emoji.ifBlank { "0" }
                        val shortname = r.emoji.ifBlank { emojiKey }
                        // Tính URL ảnh từ emojiId Long
                        val emojiSrc = if (r.emojiId != 0L)
                            "https://cdn.mezon.vn/emojis/${r.emojiId}.webp"
                        else getSrcEmoji(shortname)
                        // isIdempotent=false vì đây là load ban đầu từ DB, mỗi entry riêng biệt
                        reactions.applyEvent(emojiKey, shortname, senderId, senderName, count,
                            actionDelete = false, emojiSrc = emojiSrc, isIdempotent = false)
                    }
                    Log.d(TAG, "parseAndStoreReactions: msgId=$messageId reactions=${reactionList.getReactionsList().size}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse reactions for msgId=$messageId: ${e.message}")
                }
            }
        }
    }

    /**
     * Sau khi parseAndStoreReactions xong, notify UI để cập nhật reaction chips cho các message trong channel.
     */
    private fun notifyRestoredReactions(channelId: Long) {
        val affectedMessages = synchronized(reactionStore) { reactionStore.keys.toList() }
        for (messageId in affectedMessages) {
            val reactions = synchronized(reactionStore) { reactionStore[messageId]?.copy() } ?: continue
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.messageReactionsDidUpdate,
                channelId, messageId, reactions
            )
        }
    }

    /**
     * Optimistic local update khi user vừa bấm react.
     * Cập nhật store ngay lập tức và post notification để UI hiện reaction trước khi nhận WS echo.
     */
    fun applyOptimisticReaction(
        channelId: Long,
        messageId: Long,
        emojiId: String,
        shortname: String,
        myUserId: String,
        actionDelete: Boolean,
        count: Int = 1,
        emojiSrc: String = ""
    ) {
        // Khi emojiId không parse được thành Long (tức là "hq_xxx" hoặc unicode string),
        // server sẽ echo về với emojiId=0 và shortname làm identifier.
        // → dùng shortname làm key để match với WS echo.
        val storeKey = if (emojiId.toLongOrNull() != null) emojiId else shortname.ifBlank { emojiId }

        // Resolve URL ảnh: nếu có emojiSrc truyền vào thì dùng, fallback getSrcEmoji(emojiId)
        val resolvedSrc = emojiSrc.ifBlank { getSrcEmoji(emojiId) }

        val reactions: MessageReactions? = synchronized(reactionStore) {
            val r = reactionStore.getOrPut(messageId) { MessageReactions() }
            r.applyEvent(storeKey, shortname, myUserId, "Me", count, actionDelete, resolvedSrc)
            if (r.isEmpty()) {
                reactionStore.remove(messageId)
                null
            } else {
                r.copy()
            }
        }
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.messageReactionsDidUpdate,
            channelId, messageId, reactions ?: MessageReactions()
        )
        Log.d(TAG, "Optimistic reaction: msgId=$messageId storeKey=$storeKey del=$actionDelete")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Emoji fetch
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Lấy danh sách emoji. Ưu tiên in-memory cache (TTL 5 phút).
     * Kết quả trả về qua callback [onResult] trên Main thread.
     */
    fun fetchEmojis(
        noCache: Boolean = false,
        onResult: (List<IEmoji>) -> Unit
    ) {
        appScope.launch(ioDispatcher) {
            try {
                val cached = emojiRepository.getCachedEmojis(noCache)
                if (cached != null) {
                    withContext(Dispatchers.Main) { onResult(cached) }
                    return@launch
                }
                val session = sessionManager.sessionFlow.first() ?: run {
                    withContext(Dispatchers.Main) { onResult(emptyList()) }
                    return@launch
                }
                val emojis = api.getListEmojisByUserId(session.apiUrl, session.token)
                emojiRepository.cacheEmojis(emojis)
                Log.d(TAG, "fetchEmojis: got ${emojis.size} emojis from API")
                withContext(Dispatchers.Main) { onResult(emojis) }
            } catch (e: Exception) {
                Log.e(TAG, "fetchEmojis failed", e)
                withContext(Dispatchers.Main) { onResult(emptyList()) }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Reaction queue
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Enqueue một reaction. Xử lý tuần tự qua [processReactionQueue].
     * @param actionDelete true = bỏ reaction, false = thêm reaction
     * @param countToRemove số lần cần xóa (khi actionDelete=true)
     */
    fun reactToMessage(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        messageId: Long,
        emojiId: String,
        emojiShortname: String,
        messageSenderId: Long = 0L,
        senderName: String = "",
        actionDelete: Boolean = false,
        countToRemove: Int = 1
    ) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = channelType != CHANNEL_TYPE_DM
        appScope.launch(ioDispatcher) {
            val session = sessionManager.sessionFlow.first()
            val userId = session?.userId ?: ""
            val args = WriteMessageReactionArgs(
                id = "",
                clanId = if (clanId == 0L) "0" else clanId.toString(),
                channelId = channelId.toString(),
                mode = mode,
                messageId = messageId.toString(),
                emojiId = emojiId.ifBlank { "0" },
                emoji = emojiShortname,
                count = if (actionDelete) countToRemove else 1,
                messageSenderId = messageSenderId.toString(),
                actionDelete = actionDelete,
                isPublic = isPublic,
                userId = userId,
                topicId = "0",
                emojiRecentId = "0",
                senderName = senderName
            )
            reactionQueue.send(args)
            Log.d(TAG, "Enqueued reaction: msgId=$messageId emoji=$emojiShortname actionDelete=$actionDelete")
        }
    }

    private suspend fun processReactionQueue() {
        reactionQueue.consumeEach { args ->
            sendReactionWithRetry(args)
        }
    }

    private suspend fun sendReactionWithRetry(args: WriteMessageReactionArgs) {
        val maxRetries = 10
        val retryDelayMs = 700L
        var attempt = 0

        while (attempt <= maxRetries) {
            if (!networkMonitor.isOnline.value) {
                if (attempt >= maxRetries) {
                    Log.e(TAG, "Reaction dropped after $maxRetries retries (offline): msgId=${args.messageId}")
                    return
                }
                Log.d(TAG, "Offline — retry ${attempt + 1}/$maxRetries in ${retryDelayMs}ms")
                delay(retryDelayMs)
                attempt++
                continue
            }

            if (mezonSocket.connectionState.value == ConnectionState.CONNECTED) {
                try {
                    mezonSocket.writeMessageReaction(
                        id = args.id.toLongOrNull() ?: 0L,
                        clanId = args.clanId.toLongOrNull() ?: 0L,
                        channelId = args.channelId.toLongOrNull() ?: 0L,
                        mode = args.mode,
                        isPublic = args.isPublic,
                        messageId = args.messageId.toLongOrNull() ?: 0L,
                        emojiId = args.emojiId.toLongOrNull() ?: 0L,
                        emoji = args.emoji,
                        count = args.count,
                        messageSenderId = args.messageSenderId.toLongOrNull() ?: 0L,
                        actionDelete = args.actionDelete,
                        topicId = args.topicId.toLongOrNull() ?: 0L,
                        emojiRecentId = args.emojiRecentId.toLongOrNull() ?: 0L,
                        senderName = args.senderName ?: ""
                    )
                    if (!args.actionDelete) {
                        emojiRepository.saveRecentEmoji(args.emojiId, args.emoji)
                    }
                    Log.d(TAG, "Reaction sent via WebSocket (fire-and-forget): msgId=${args.messageId} emoji=${args.emoji}")
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "WebSocket reaction error (${e.message}) — falling back to HTTP REST")
                }
            }

            try {
                sessionManager.withAutoRefresh { session ->
                    api.reactToMessageRest(
                        apiUrl = session.apiUrl,
                        token = session.token,
                        clanId = args.clanId,
                        channelId = args.channelId,
                        messageId = args.messageId,
                        emojiId = args.emojiId,
                        emoji = args.emoji,
                        count = args.count,
                        messageSenderId = args.messageSenderId,
                        actionDelete = args.actionDelete,
                        mode = args.mode,
                        isPublic = args.isPublic,
                        topicId = args.topicId,
                        emojiRecentId = args.emojiRecentId,
                        senderName = args.senderName ?: ""
                    )
                }
                if (!args.actionDelete) {
                    emojiRepository.saveRecentEmoji(args.emojiId, args.emoji)
                }
                Log.d(TAG, "Reaction sent via HTTP REST fallback: msgId=${args.messageId}")
                return
            } catch (e: Exception) {
                Log.e(TAG, "HTTP REST fallback failed (attempt ${attempt + 1}): ${e.message}")
                if (attempt >= maxRetries) {
                    Log.e(TAG, "Reaction dropped after max retries: msgId=${args.messageId}")
                    return
                }
                delay(retryDelayMs)
                attempt++
            }
        }
    }
    fun fetchEmojiRecent() {
        appScope.launch(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
                    // Gọi song song 2 API
                    val recentIdsDeferred = appScope.async(ioDispatcher) {
                        api.getEmojiRecentList(session.apiUrl, session.token)
                    }
                    val allEmojisDeferred = appScope.async(ioDispatcher) {
                        api.getListEmojisByUserId(session.apiUrl, session.token)
                    }

                    val recentIds = recentIdsDeferred.await()   // [emojiId Long, ...]
                    val allEmojis = allEmojisDeferred.await()   // [IEmoji{id,shortname,src}, ...]

                    if (recentIds.isEmpty()) {
                        Log.d(TAG, "fetchEmojiRecent: no recent emojis from server")
                        return@withAutoRefresh
                    }

                    val emojiMap = allEmojis.associateBy { it.id }

                    var savedCount = 0
                    for (emojiId in recentIds) {
                        val idStr = emojiId.toString()
                        val emoji = emojiMap[idStr]
                        val shortname = emoji?.shortname ?: idStr
                        emojiRepository.saveRecentEmoji(idStr, shortname)
                        savedCount++
                        if (savedCount >= 20) break
                    }

                    if (allEmojis.isNotEmpty()) emojiRepository.cacheEmojis(allEmojis)

                    Log.d(TAG, "fetchEmojiRecent: synced $savedCount recent emojis from server")
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchEmojiRecent failed: ${e.message}")
            }
        }
    }
}
