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
import com.mezon.mobile.util.buildTextContent
import com.mezon.mezon.api.MessageAttachment
import com.mezon.mezon.api.messageAttachment
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
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    val dialogMessage = LongSparseArray<MessageEntity>()
    private val initialFetchDone = HashSet<Long>()

    init {
        appScope.launch { observeIncomingMessages() }
        appScope.launch(ioDispatcher) {
            val session = sessionManager.sessionFlow.first { it != null }
            cachedCurrentUserId = session?.userId?.toLongOrNull() ?: 0L
        }
    }

    fun cleanup() {
        synchronized(this) {
            dialogMessage.clear()
            initialFetchDone.clear()
            cachedCurrentUserId = 0L
        }
    }

    fun openChannel(channelId: Long, clanId: Long, channelType: Int, isChannelPrivate: Boolean = false) {
        val isPublic = !isChannelPrivate
        val mode = channelTypeToStreamMode(channelType)
        cacheTracker.invalidate(apiCacheKey("fetchMessages", clanId, channelId))
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

    fun loadMessages(channelId: Long, clanId: Long) {
        appScope.launch(ioDispatcher) {
            try {
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

                val cacheKey = apiCacheKey("fetchMessages", clanId, channelId)
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
                        .sortedBy { it.timestampSeconds }

                    messageDao.deleteByChannel(channelId)
                    messageDao.upsertAll(messages)
                    synchronized(this@ChatController) { initialFetchDone.add(channelId) }
                    Log.d(TAG, "loadMessages hasMoreTop=$hasMoreTop firstMessageReached=$firstMessageReached size=${response.messagesList.size}")
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesDidLoad, channelId, ArrayList(messages), hasMoreTop, false, false
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

    fun loadMessagesAround(channelId: Long, clanId: Long, anchorMessageId: Long) {
        appScope.launch(ioDispatcher) {
            try {
                if (!networkMonitor.isOnline.value) return@launch

                val cacheKey = apiCacheKey("fetchMessages", clanId, channelId)
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
                        .sortedBy { it.timestampSeconds }

                    if (msgs.isNotEmpty()) {
                        messageDao.deleteByChannel(channelId)
                        messageDao.upsertAll(msgs)
                        synchronized(this@ChatController) { initialFetchDone.add(channelId) }
                        Log.d(TAG, "loadMessagesAround: anchor=$anchorMessageId count=${msgs.size} hasMoreTop=$hasMoreTop firstMessageReached=$firstMessageReached")
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.messagesDidLoad, channelId, ArrayList(msgs), hasMoreTop, true, false
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
                        .sortedBy { it.timestampSeconds }

                    val hasMoreBottom = response.messagesList.size >= PAGE_SIZE
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
        timestampSeconds: Int
    ) {
        val mode = channelTypeToStreamMode(channelType)
        appScope.launch {
            try {
                mezonSocket.writeLastSeenMessage(clanId, channelId, mode, messageId, timestampSeconds, 0)
                dialogsController.markDialogAsRead(channelId)
                Log.d(TAG, "Updated lastSeen: channelId=$channelId messageId=$messageId")
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

    @Volatile private var cachedCurrentUserId = 0L

    fun getCurrentUserId(): Long {
        if (cachedCurrentUserId != 0L) return cachedCurrentUserId
        appScope.launch(ioDispatcher) {
            val session = sessionManager.sessionFlow.first()
            cachedCurrentUserId = session?.userId?.toLongOrNull() ?: 0L
        }
        return cachedCurrentUserId
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
                    appScope.launch { messageDao.upsert(entity) }
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messageDidUpdate, entity.channelId, entity,
                        NotificationCenter.UPDATE_MASK_MESSAGE_TEXT
                    )
                }
                CODE_CHAT_REMOVE -> {
                    appScope.launch { messageDao.delete(msg.channelId, msg.messageId) }
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messageDidDelete, msg.channelId, msg.messageId
                    )
                }
                else -> {
                    if (!entity.isRenderable) return@collect
                    appScope.launch { messageDao.upsert(entity) }
                    synchronized(this) { dialogMessage.put(entity.channelId, entity) }
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
