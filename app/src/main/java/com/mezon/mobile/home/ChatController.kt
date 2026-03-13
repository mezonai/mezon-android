package com.mezon.mobile.home

import android.util.LongSparseArray
import android.util.Log
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.data.db.MessageDao
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
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
import com.mezon.mobile.util.buildTextContent
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
    }

    fun openChannel(channelId: Long, clanId: Long, channelType: Int) {
        val isPublic = channelType != CHANNEL_TYPE_DM
        val mode = channelTypeToStreamMode(channelType)
        cacheTracker.invalidate(apiCacheKey("fetchMessages", clanId, channelId))
        appScope.launch {
            try {
                val session = sessionManager.sessionFlow.first() ?: return@launch
                mezonSocket.joinChat(clanId, channelId, channelType, isPublic)
                Log.d(TAG, "Joined channel $channelId (clanId=$clanId type=$channelType)")
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
                    Log.d(TAG, "Loaded ${fromDb.size} messages from DB for channel $channelId")
                    val hasMoreTop = fromDb.size >= PAGE_SIZE
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesDidLoad, channelId, ArrayList(fromDb), hasMoreTop, false, true
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
                    val newestCached = fromDb.lastOrNull()
                    val hadInitialFetch = synchronized(this@ChatController) { channelId in initialFetchDone }

                    if (newestCached != null && hadInitialFetch) {
                        val response = api.listChannelMessages(
                            session.apiUrl, session.token, channelId, clanId,
                            newestCached.id, DIRECTION_AFTER, PAGE_SIZE
                        )
                        val newer = response.messagesList
                            .map { it.toMessageEntity(currentUserId) }
                            .filter { it.isRenderable }
                            .sortedBy { it.timestampSeconds }

                        if (newer.isNotEmpty()) {
                            messageDao.upsertAll(newer)
                            val hasMoreBottom = response.messagesList.size >= PAGE_SIZE
                            notificationCenter.postNotificationOnMainThread(
                                NotificationCenter.messagesDidLoad, channelId, ArrayList(newer), false, hasMoreBottom, false
                            )
                        }
                    } else {
                        val response = api.listChannelMessages(
                            session.apiUrl, session.token, channelId, clanId, limit = PAGE_SIZE
                        )
                        val messages = response.messagesList
                            .map { it.toMessageEntity(currentUserId) }
                            .filter { it.isRenderable }
                            .sortedBy { it.timestampSeconds }

                        messageDao.upsertAll(messages)
                        synchronized(this@ChatController) { initialFetchDone.add(channelId) }
                        val hasMoreTop = response.messagesList.size >= PAGE_SIZE
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.messagesDidLoad, channelId, ArrayList(messages), hasMoreTop, false, false
                        )
                    }

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
                    val msgs = response.messagesList
                        .map { it.toMessageEntity(currentUserId) }
                        .filter { it.isRenderable }
                        .sortedBy { it.timestampSeconds }

                    if (msgs.isNotEmpty()) {
                        messageDao.upsertAll(msgs)
                        synchronized(this@ChatController) { initialFetchDone.add(channelId) }
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.messagesDidLoad, channelId, ArrayList(msgs), true, true, false
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

                    if (newer.isNotEmpty()) {
                        messageDao.upsertAll(newer)
                        val hasMoreBottom = response.messagesList.size >= PAGE_SIZE
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.messagesDidLoad, channelId, ArrayList(newer), false, hasMoreBottom, false
                        )
                    } else {
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.messagesDidLoad, channelId, ArrayList<MessageEntity>(), false, false, false
                        )
                    }
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
        appScope.launch(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
                    val currentUserId = session.userId.toLongOrNull() ?: 0L
                    val response = api.listChannelMessages(
                        session.apiUrl, session.token, channelId, clanId,
                        oldestMessageId, DIRECTION_BEFORE, PAGE_SIZE
                    )
                    val older = response.messagesList
                        .map { it.toMessageEntity(currentUserId) }
                        .filter { it.isRenderable }
                        .sortedBy { it.timestampSeconds }

                    messageDao.upsertAll(older)
                    val hasMoreTop = response.messagesList.size >= PAGE_SIZE
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

    fun sendMessage(channelId: Long, clanId: Long, channelType: Int, text: String) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = channelType != CHANNEL_TYPE_DM
        val content = buildTextContent(text)
        appScope.launch {
            try {
                mezonSocket.writeChatMessage(clanId, channelId, mode, isPublic, content)
                Log.d(TAG, "Message sent: channelId=$channelId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
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
