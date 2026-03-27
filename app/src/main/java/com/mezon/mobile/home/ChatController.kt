package com.mezon.mobile.home

import android.util.LongSparseArray
import android.util.Log
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.data.db.MessageDao
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.chat.AttachmentPickerItem
import com.mezon.mobile.home.chat.MessageEntity
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
import com.mezon.mobile.util.MentionData
import com.mezon.mobile.util.buildTextContent
import com.mezon.mobile.util.buildTextContentWithMentions
import com.mezon.mezon.api.MessageAttachment
import com.mezon.mezon.api.messageAttachment
import com.mezon.mezon.api.messageMention
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ChatController"
private const val PAGE_SIZE = 50
private const val DIRECTION_AFTER = 1
private const val DIRECTION_AROUND = 2
const val LOAD_TYPE_INITIAL = 0
const val LOAD_TYPE_MORE_TOP = 1
const val LOAD_TYPE_MORE_BOTTOM = 2
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
    private val channelController: dagger.Lazy<com.mezon.mobile.home.clans.ChannelController>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    val dialogMessage = LongSparseArray<MessageEntity>()
    private val initialFetchDone = HashSet<Long>()
    private val lastMessageByChannel = LongSparseArray<Long>()  // channelId → newest messageId

    init {
        appScope.launch { observeIncomingMessages() }
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
                val session = sessionManager.sessionFlow.first() ?: return@launch
                mezonSocket.joinChat(clanId, channelId, channelType, isPublic)
                Log.d(TAG, "Joined channel $channelId (clanId=$clanId type=$channelType isPublic=$isPublic)")
            } catch (e: Exception) {
                Log.e(TAG, "joinChat failed channelId=$channelId", e)
            }
        }
    }

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

    fun loadMessagesAround(channelId: Long, clanId: Long, anchorMessageId: Long, requireExactAnchor: Boolean = false) {
        appScope.launch(ioDispatcher) {
            try {
                val cacheKey = apiCacheKey("fetchMessages", clanId, channelId)

                val fromDb = messageDao.getMessagesAround(channelId, anchorMessageId, PAGE_SIZE)
                var anchorInDb = false
                if (fromDb.isNotEmpty()) {
                    val dbMinId = fromDb.minOf { it.id }
                    val dbMaxId = fromDb.maxOf { it.id }
                    anchorInDb = if (requireExactAnchor) {
                        fromDb.any { it.id == anchorMessageId }
                    } else {
                        anchorMessageId in dbMinId..dbMaxId
                    }
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
                        val fallback = messageDao.getLatestByChannel(channelId, PAGE_SIZE * 4)
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

                    if (msgs.isNotEmpty()) {
                        messageDao.upsertAll(msgs)
                        messageDao.trimAround(channelId, anchorMessageId, PAGE_SIZE * 2)
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
                if (!networkMonitor.isOnline.value) {
                    val fromDb = messageDao.getMessagesAfter(channelId, newestMessageId, PAGE_SIZE)
                    val hasMoreBottom = fromDb.size >= PAGE_SIZE
                    Log.d(TAG, "loadMoreBottom offline fallback: ${fromDb.size} from DB")
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesDidLoad, channelId, ArrayList(fromDb),
                        false, hasMoreBottom, false, 0L, LOAD_TYPE_MORE_BOTTOM
                    )
                    return@launch
                }

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
                    if (newer.isNotEmpty()) {
                        messageDao.upsertAll(newer)
                        messageDao.trimAround(channelId, newestMessageId, PAGE_SIZE * 2)
                    }
                    Log.d(TAG, "loadMoreBottom: count=${newer.size} hasMoreBottom=$hasMoreBottom hasLastSentMessage=${response.hasLastSentMessage()} serverLastSentId=$serverLastSentId rawCount=${response.messagesList.size} newerRange=${newer.minOfOrNull { it.id }}..${newer.maxOfOrNull { it.id }}")
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesDidLoad, channelId, ArrayList(newer), false, hasMoreBottom, false, 0L, LOAD_TYPE_MORE_BOTTOM
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
                if (!networkMonitor.isOnline.value) {
                    val fromDb = messageDao.getMessagesBefore(channelId, oldestMessageId, PAGE_SIZE)
                    val hasMoreTop = fromDb.size >= PAGE_SIZE
                    Log.d(TAG, "loadMoreTop offline fallback: ${fromDb.size} from DB")
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesDidLoad, channelId, ArrayList(fromDb.sortedBy { it.timestampSeconds }),
                        hasMoreTop, false, false, 0L, LOAD_TYPE_MORE_TOP
                    )
                    return@launch
                }

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
                    if (older.isNotEmpty()) {
                        messageDao.upsertAll(older)
                        messageDao.trimAround(channelId, oldestMessageId, PAGE_SIZE * 2)
                    }
                    Log.d(TAG, "loadMoreTop returned ${older.size} hasMoreTop=$hasMoreTop firstMessageReached=$firstMessageReached")
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesDidLoad, channelId, ArrayList(older), hasMoreTop, false, false, 0L, LOAD_TYPE_MORE_TOP
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

    fun sendMessage(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        isChannelPrivate: Boolean,
        text: String,
        references: List<com.mezon.mezon.api.MessageRef>? = null,
        mentions: List<MentionData>? = null
    ) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val content = if (mentions.isNullOrEmpty()) buildTextContent(text)
            else buildTextContentWithMentions(text, mentions)
        val mentionEveryone = mentions?.any { it.userId == ID_MENTION_HERE } == true
        val protoMentions = mentions?.mapNotNull { m ->
            if (m.userId == ID_MENTION_HERE) return@mapNotNull null
            com.mezon.mezon.api.messageMention {
                if (m.userId.isNotBlank()) userId = m.userId.toLongOrNull() ?: 0L
                if (m.roleId.isNotBlank()) roleId = m.roleId.toLongOrNull() ?: 0L
            }
        }
        appScope.launch {
            try {
                mezonSocket.writeChatMessage(
                    clanId, channelId, mode, isPublic, content,
                    mentions = protoMentions, references = references,
                    mentionEveryone = mentionEveryone
                )
                Log.d(TAG, "Message sent: channelId=$channelId mentions=${mentions?.size ?: 0}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
            }
        }
    }

    companion object {
        const val ID_MENTION_HERE = "here"
    }

    fun sendMessageWithAttachments(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        isChannelPrivate: Boolean,
        text: String,
        attachments: List<AttachmentPickerItem>,
        contentResolver: android.content.ContentResolver,
        references: List<com.mezon.mezon.api.MessageRef>? = null
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
                        attachments = uploadedAttachments,
                        references = references
                    )
                    Log.d(TAG, "Message with ${uploadedAttachments.size} attachments sent: channelId=$channelId hasReferences=${references != null}")
                } else {
                    Log.e(TAG, "No attachments uploaded successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message with attachments", e)
            }
        }
    }

    @Volatile private var cachedCurrentUserId = 0L

    fun getCurrentUserId(): Long {
        if (cachedCurrentUserId != 0L) return cachedCurrentUserId
        appScope.launch(ioDispatcher) {
            val session = sessionManager.sessionFlow.first()
            cachedCurrentUserId = session?.userId?.toLongOrNull() ?: 0L
        }
        return cachedCurrentUserId
    }

    fun editMessage(
        channelId: Long, clanId: Long, channelType: Int,
        isChannelPrivate: Boolean, messageId: Long, newText: String
    ) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val content = buildTextContent(newText)
        appScope.launch {
            try {
                mezonSocket.updateChatMessage(clanId, channelId, mode, isPublic, messageId, content)
                Log.d(TAG, "Message edited: channelId=$channelId messageId=$messageId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to edit message", e)
            }
        }
    }

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
                    appScope.launch {
                        messageDao.updateContent(
                            entity.channelId, entity.id, entity.content,
                            entity.updateTimeSeconds, entity.hideEditted, entity.code
                        )
                    }
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
}
