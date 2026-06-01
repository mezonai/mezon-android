package com.mezon.mobile.home

import android.util.LongSparseArray
import android.util.Log
import com.google.protobuf.ByteString
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.data.db.MessageDao
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.chat.AttachmentPickerItem
import com.mezon.mobile.home.chat.AttachmentInfo
import com.mezon.mobile.home.chat.MessageEntity
import com.mezon.mobile.home.chat.canEditMessage
import com.mezon.mobile.home.chat.ForwardDestination
import com.mezon.mobile.home.chat.applyReactionEvent
import com.mezon.mobile.home.chat.mergeChannelContentMentionsAndRefs
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
import com.mezon.mobile.network.STREAM_MODE_CHANNEL
import com.mezon.mobile.network.STREAM_MODE_DM
import com.mezon.mobile.network.STREAM_MODE_THREAD
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.home.clans.CHANNEL_TYPE_VOICE
import com.mezon.mobile.home.chat.thread.THREAD_ARCHIVE_DURATION_SECONDS
import com.mezon.mobile.home.chat.thread.ThreadStatus
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.util.MENTION_HERE_USER_ID
import com.mezon.mobile.util.MezonSnowflake
import com.mezon.mobile.util.firstReferenceMessageId
import com.mezon.mobile.util.SentryReporter
import com.mezon.mobile.util.MentionData
import com.mezon.mobile.util.buildTextContent
import com.mezon.mobile.util.EmojiMarker
import com.mezon.mobile.util.MarkdownMarker
import com.mezon.mobile.util.OgpMarker
import com.mezon.mobile.util.ShareContactData
import com.mezon.mobile.util.buildShareContactContent
import com.mezon.mobile.util.buildTextContentWithEmojis
import com.mezon.mobile.util.mergePendingMentionsIntoContent
import com.mezon.mobile.util.mergeShareContactEmbedIntoContent
import com.mezon.mobile.util.isShareContactMessage
import com.mezon.mobile.util.isEmbedOrComponentsPayload
import com.mezon.mobile.util.parseContentText
import com.mezon.mezon.api.ChannelMessage
import com.mezon.mezon.api.CreatePollResponse
import com.mezon.mobile.home.chat.poll.buildPollMessageContent
import com.mezon.mezon.api.ChannelMessageHeader
import com.mezon.mezon.api.MessageAttachment
import com.mezon.mezon.api.MessageMentionList
import com.mezon.mezon.api.MessageMention
import com.mezon.mezon.api.messageAttachment
import com.mezon.mezon.api.messageMention
import org.json.JSONObject
import com.mezon.mezon.rtapi.ChannelMessageSend
import com.mezon.mobile.home.chat.withTopicCreated
import com.mezon.mobile.home.chat.withTopicStats
import com.mezon.mezon.rtapi.Envelope
import com.mezon.mezon.rtapi.SdTopicEvent
import com.mezon.mezon.rtapi.channelMessageSend
import com.mezon.mezon.rtapi.channelMessageUpdate
import com.mezon.mobile.network.ConnectionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ChatController"
private const val MAX_FORWARD_COMMENT_CHARS = 2000
private const val PAGE_SIZE = 50
private const val DIRECTION_AFTER = 1
private const val DIRECTION_AROUND = 2
const val LOAD_TYPE_INITIAL = 0
const val LOAD_TYPE_MORE_TOP = 1
const val LOAD_TYPE_MORE_BOTTOM = 2
private const val DIRECTION_BEFORE = 3
/** Wait for poll message over websocket after CreatePoll REST. */
private const val POLL_MESSAGE_WAIT_MS = 8_000L
private val FILENAME_SANITIZE_REGEX = Regex("[^a-zA-Z0-9._-]")

private fun computeHasMoreTop(
    topicId: Long,
    apiBatchSize: Int,
    filteredBatch: List<MessageEntity>
): Boolean {
    val firstReached = filteredBatch.any { it.code == MessageEntity.CODE_FIRST_MESSAGE }
    return when {
        apiBatchSize == 0 -> false
        topicId != 0L -> apiBatchSize >= PAGE_SIZE && !firstReached
        else -> !firstReached
    }
}

@Singleton
class ChatController @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val api: MezonApi,
    private val mezonSocket: MezonSocket,
    private val messageDao: MessageDao,
    private val socketEventDispatcher: SocketEventDispatcher,
    private val sessionManager: SessionManager,
    private val notificationCenter: NotificationCenter,
    private val networkMonitor: NetworkMonitor,
    private val cacheTracker: ApiCacheTracker,
    private val dialogsController: DialogsController,
    private val badgeCoordinator: BadgeCoordinator,
    private val topicBadgeTracker: TopicBadgeTracker,
    private val forwardTargetUsageStore: ForwardTargetUsageStore,
    private val channelController: dagger.Lazy<com.mezon.mobile.home.clans.ChannelController>,
    private val userController: dagger.Lazy<UserController>,
    private val anonymousController: dagger.Lazy<AnonymousController>,
    private val userClanController: dagger.Lazy<UserClanController>,
    private val sentryReporter: SentryReporter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    val dialogMessage = LongSparseArray<MessageEntity>()
    private val initialFetchDone = HashSet<Long>()
    private val lastMessageByChannel = LongSparseArray<Long>()
    private val pendingTempMessageByChannel = LongSparseArray<Long>()
    private val topicRootByTopicId = LongSparseArray<TopicRootRef>()

    private data class TopicRootRef(val parentChannelId: Long, val rootMessageId: Long)

    private fun cacheTopicRoot(topicId: Long, parentChannelId: Long, rootMessageId: Long) {
        if (topicId == 0L || parentChannelId == 0L || rootMessageId == 0L) return
        synchronized(this) {
            topicRootByTopicId.put(topicId, TopicRootRef(parentChannelId, rootMessageId))
        }
    }

    private fun isAnonymousSend(clanId: Long): Boolean =
        clanId != 0L && anonymousController.get().isAnonymous(clanId)

    private fun optimisticSenderPresentation(uc: UserController, clanId: Long, channelType: Int, anon: Boolean): Pair<String, String> {
        if (anon) return Pair("Anonymous", "")
        val mode = channelTypeToStreamMode(channelType)
        val useClanPersona = clanId != 0L && (mode == STREAM_MODE_CHANNEL || mode == STREAM_MODE_THREAD)
        val fallbackName = uc.displayName.ifBlank { uc.username }
        if (!useClanPersona) return Pair(fallbackName, uc.avatarUrl)
        val myId = uc.userId
        val member = userClanController.get().getClanMembers(clanId).firstOrNull { it.userId == myId }
        if (member != null) {
            val name = member.clanNick.ifBlank { member.displayName.ifBlank { member.username } }
            val avatar = member.clanAvatar.ifBlank { member.avatarUrl }
            return Pair(name, avatar)
        }
        return Pair(fallbackName, uc.avatarUrl)
    }

    private val ANONYMOUS_USER_ID = BuildConfig.MEZON_ANONYMOUS_USER_ID.toLongOrNull() ?: 0L  // channelId → newest messageId

    init {
        appScope.launch { observeIncomingMessages() }
        appScope.launch { observeIncomingMessageUpdates() }
        appScope.launch { observeReactionEvents() }
        appScope.launch { observeSdTopicEvents() }
        appScope.launch(ioDispatcher) {
            val session = sessionManager.sessionFlow.first { it != null }
            cachedCurrentUserId = session?.userId?.toLongOrNull() ?: 0L
        }
    }

    /** Returns the newest known messageId for [channelId], 0 if unknown. */
    fun getLastMessageId(channelId: Long): Long =
        synchronized(this) { lastMessageByChannel.get(channelId, 0L) }

    private fun assignMissingMessageId(msg: ChannelMessage): ChannelMessage {
        if (msg.messageId != 0L) return msg
        val refId = firstReferenceMessageId(msg.content)
        if (refId > 0L) {
            synchronized(this) {
                val lastKnown = lastMessageByChannel.get(msg.channelId, 0L)
                var candidate = refId + 1L
                if (candidate <= refId) candidate = MezonSnowflake.generate()
                if (candidate <= lastKnown) candidate = lastKnown + 1L
                return ChannelMessage.newBuilder(msg).setMessageId(candidate).build()
            }
        }
        return ChannelMessage.newBuilder(msg).setMessageId(MezonSnowflake.generate()).build()
    }

    private fun MessageEntity.canAdvanceServerTimeline(): Boolean {
        return id > 0L && !isUnreadDivider && !isEphemeral && !isSending
    }

    private fun ChannelMessage.canAdvanceServerTimeline(): Boolean {
        return messageId > 0L &&
            code != MessageEntity.CODE_EPHEMERAL &&
            code != MessageEntity.CODE_UPDATE_EPHEMERAL &&
            code != MessageEntity.CODE_DELETE_EPHEMERAL
    }

    private fun ChannelMessageHeader.canAdvanceServerTimeline(): Boolean {
        return id > 0L
    }

    private fun updateLastMessageByChannel(channelId: Long, messages: List<MessageEntity>, latestIdFromResponse: Long = 0L) {
        val fromServer = if (latestIdFromResponse > 0L) latestIdFromResponse else null
        val fromMessages = messages.asSequence()
            .filter { it.canAdvanceServerTimeline() }
            .maxOfOrNull { it.id }
        val newestId = fromServer ?: fromMessages ?: return
        synchronized(this) {
            if (newestId > lastMessageByChannel.get(channelId, 0L)) {
                lastMessageByChannel.put(channelId, newestId)
            }
        }
    }

    fun cleanup() {
        synchronized(this) {
            dialogMessage.clear()
            initialFetchDone.clear()
            lastMessageByChannel.clear()
            pendingTempMessageByChannel.clear()
            topicRootByTopicId.clear()
            cachedCurrentUserId = 0L
        }
        pendingApiReactions.clear()
        lastApiReactionDedup = null
    }

    suspend fun getMessageById(channelId: Long, messageId: Long): MessageEntity? =
        withContext(ioDispatcher) { messageDao.getById(channelId, messageId) }

    suspend fun getMessagesByIds(channelId: Long, ids: List<Long>): Map<Long, MessageEntity> =
        withContext(ioDispatcher) {
            if (ids.isEmpty()) return@withContext emptyMap()
            messageDao.getByIds(channelId, ids).associateBy { it.id }
        }

    fun publishCreatedPollMessage(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        response: CreatePollResponse
    ) {
        val messageId = response.messageId
        if (messageId == 0L) return
        val uc = userController.get()
        val anon = isAnonymousSend(clanId)
        val (optName, optAvatar) = optimisticSenderPresentation(uc, clanId, channelType, anon)
        val content = buildPollMessageContent(response)
        val optimistic = MessageEntity(
            id = messageId,
            channelId = channelId,
            senderId = if (anon) ANONYMOUS_USER_ID else uc.userId,
            senderName = optName,
            senderUsername = if (anon) "Anonymous" else uc.username,
            senderAvatar = optAvatar,
            content = content,
            timestampSeconds = System.currentTimeMillis() / 1000,
            code = MessageEntity.CODE_CHAT,
            isMe = true,
            sendState = MessageEntity.SEND_STATE_SENDING
        )
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.didReceiveNewMessages, channelId, optimistic
        )
    }

    suspend fun awaitChannelMessage(
        channelId: Long,
        messageId: Long,
        timeoutMs: Long = POLL_MESSAGE_WAIT_MS
    ): Boolean {
        if (messageId == 0L) return false
        if (withContext(ioDispatcher) { messageDao.getById(channelId, messageId) } != null) {
            return true
        }
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val delegate = object : NotificationCenter.NotificationCenterDelegate {
                    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
                        if (id != NotificationCenter.didReceiveNewMessages || args.size < 2) return
                        val ch = args[0] as? Long ?: return
                        val entity = args[1] as? MessageEntity ?: return
                        if (ch != channelId || entity.id != messageId || entity.isSending) return
                        notificationCenter.removeObserver(this, NotificationCenter.didReceiveNewMessages)
                        if (cont.isActive) cont.resume(true)
                    }
                }
                notificationCenter.addObserver(delegate, NotificationCenter.didReceiveNewMessages)
                cont.invokeOnCancellation {
                    notificationCenter.removeObserver(delegate, NotificationCenter.didReceiveNewMessages)
                }
            }
        } == true
    }

    suspend fun reloadChannelMessageIfMissing(channelId: Long, clanId: Long, messageId: Long): Boolean {
        if (messageId == 0L) return false
        return try {
            withContext(ioDispatcher) {
                sessionManager.withAutoRefresh { session ->
                    val currentUserId = session.userId.toLongOrNull() ?: 0L
                    val response = api.listChannelMessages(
                        session.apiUrl,
                        session.token,
                        channelId,
                        clanId,
                        messageId = messageId,
                        direction = DIRECTION_AROUND,
                        limit = PAGE_SIZE
                    )
                    val entity = response.messagesList
                        .map { it.toMessageEntity(currentUserId) }
                        .firstOrNull { it.id == messageId }
                    if (entity != null) {
                        messageDao.upsert(entity)
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.didReceiveNewMessages, channelId, entity
                        )
                        true
                    } else {
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "reloadChannelMessageIfMissing failed channel=$channelId message=$messageId", e)
            false
        }
    }

    private fun messageCacheKey(channelId: Long, topicId: Long = 0L): Long =
        if (topicId != 0L) topicId else channelId

    private fun remapForCache(entities: List<MessageEntity>, cacheKey: Long): List<MessageEntity> {
        if (entities.isEmpty()) return entities
        if (entities.first().channelId == cacheKey) return entities
        return entities.map { it.copy(channelId = cacheKey) }
    }

    private fun remapForCache(entity: MessageEntity, cacheKey: Long): MessageEntity =
        if (entity.channelId == cacheKey) entity else entity.copy(channelId = cacheKey)

    fun openChannel(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        isChannelPrivate: Boolean = false,
        parentId: Long = 0L
    ) {
        val meta = channelController.get().findChannelById(channelId)
        val effectiveClanId = if (clanId != 0L) clanId else meta?.takeIf { it.clanId != 0L }?.clanId ?: clanId
        val effectiveType = if (channelType != 0) channelType else meta?.type ?: 0
        val effectiveParent = if (parentId != 0L) parentId else meta?.parentId ?: 0L
        val effectivePrivate = meta?.isPrivate ?: isChannelPrivate
        val joinSocketTypes = intArrayOf(
            CHANNEL_TYPE_CHANNEL,
            CHANNEL_TYPE_DM,
            CHANNEL_TYPE_GROUP,
            CHANNEL_TYPE_THREAD,
            CHANNEL_TYPE_VOICE
        )
        if (joinSocketTypes.none { it == effectiveType }) {
            Log.d(TAG, "openChannel: skip ChannelJoin channelId=$channelId type=$effectiveType (argType=$channelType)")
            return
        }
        val threadLike = effectiveType == CHANNEL_TYPE_THREAD || effectiveParent != 0L
        val isPublic = if (threadLike) false else !effectivePrivate
        appScope.launch {
            try {
                sessionManager.sessionFlow.first() ?: return@launch
                if (!mezonSocket.awaitConnected()) return@launch
                if (effectiveClanId != 0L) {
                    runCatching { mezonSocket.joinClanChat(effectiveClanId) }
                }
                mezonSocket.joinChat(effectiveClanId, channelId, effectiveType, isPublic)
                Log.d(TAG, "Joined channel $channelId (clanId=$effectiveClanId type=$effectiveType isPublic=$isPublic)")
            } catch (e: Exception) {
                Log.e(TAG, "joinChat failed channelId=$channelId", e)
            }
        }
    }

    fun loadMessages(
        channelId: Long,
        clanId: Long,
        forceRefresh: Boolean = false,
        preferHttp: Boolean = false,
        topicId: Long = 0L
    ) {
        val cacheKey = messageCacheKey(channelId, topicId)
        appScope.launch(ioDispatcher) {
            try {
                val cacheTrackerKey = apiCacheKey("fetchMessages", clanId, cacheKey, topicId)
                if (forceRefresh) cacheTracker.invalidate(cacheTrackerKey)
                val isOnlineNow = networkMonitor.isOnline.value

                if (!isOnlineNow) {
                    val fromDb = messageDao.getLatestByChannel(cacheKey, PAGE_SIZE)
                    val hasMoreTopOffline = computeHasMoreTop(
                        topicId,
                        fromDb.size,
                        fromDb
                    )
                    if (fromDb.isNotEmpty()) {
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.messagesDidLoad, cacheKey, ArrayList(fromDb), hasMoreTopOffline, false, true
                        )
                    } else {
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.messagesDidLoad, cacheKey, ArrayList<MessageEntity>(), false, false, true
                        )
                    }
                    return@launch
                }

                sessionManager.withAutoRefresh { session ->
                    val currentUserId = session.userId.toLongOrNull() ?: 0L
                    val response = api.listChannelMessages(
                        session.apiUrl,
                        session.token,
                        channelId,
                        clanId,
                        messageId = 0L,
                        direction = 0,
                        limit = PAGE_SIZE,
                        topicId = topicId,
                        preferHttp = preferHttp
                    )
                    val allMessages = response.messagesList.map { it.toMessageEntity(currentUserId) }
                    if (topicId == 0L) {
                        allMessages.filter { it.isRenderable && it.isTopicRootMessage }.forEach { msg ->
                            cacheTopicRoot(msg.effectiveTopicId, channelId, msg.id)
                        }
                    }
                    val hasMoreTop = computeHasMoreTop(
                        topicId,
                        response.messagesList.size,
                        allMessages.filter { it.isRenderable }
                    )

                    val messages = remapForCache(
                        allMessages.filter { it.isRenderable }.sortedBy { it.id },
                        cacheKey
                    )

                    synchronized(this@ChatController) { initialFetchDone.add(cacheKey) }
                    val serverLastSentId = if (
                        response.hasLastSentMessage() &&
                        response.lastSentMessage.canAdvanceServerTimeline()
                    ) response.lastSentMessage.id else 0L
                    updateLastMessageByChannel(cacheKey, messages, serverLastSentId)
                    val serverLastSeenId = if (response.hasLastSeenMessage()) response.lastSeenMessage.id else 0L
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesDidLoad, cacheKey, ArrayList(messages), hasMoreTop, false, false, serverLastSeenId
                    )
                    cacheTracker.markCalled(cacheTrackerKey)
                    launch { messageDao.upsertAll(messages); messageDao.trimToLatest(cacheKey, PAGE_SIZE * 4) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadMessages failed for channel $cacheKey", e)
                sentryReporter.logChatFailure("loadMessages", cacheKey, clanId, e)
                val fromDb = messageDao.getLatestByChannel(cacheKey, PAGE_SIZE)
                if (fromDb.isNotEmpty()) {
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesDidLoad, cacheKey, ArrayList(fromDb), true, false, true
                    )
                } else {
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesLoadError, cacheKey, e.message ?: "Failed to load"
                    )
                }
            }
        }
    }

    fun loadMessagesAround(
        channelId: Long,
        clanId: Long,
        anchorMessageId: Long,
        requireExactAnchor: Boolean = false,
        preferHttp: Boolean = false,
        topicId: Long = 0L
    ) {
        val cacheKey = messageCacheKey(channelId, topicId)
        appScope.launch(ioDispatcher) {
            try {
                val cacheTrackerKey = apiCacheKey("fetchMessages", clanId, cacheKey, topicId)
                val isOnlineNow = networkMonitor.isOnline.value

                if (!isOnlineNow) {
                    val fromDb = messageDao.getMessagesAround(cacheKey, anchorMessageId, PAGE_SIZE)
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
                            val lastKnown = synchronized(this@ChatController) { lastMessageByChannel.get(cacheKey, 0L) }
                            val hasMoreBottom = lastKnown > 0L && dbMaxId < lastKnown
                            Log.d(TAG, "loadMessagesAround: DB hit anchor=$anchorMessageId range=$dbMinId..$dbMaxId hasMoreBottom=$hasMoreBottom count=${fromDb.size}")
                            notificationCenter.postNotificationOnMainThread(
                                NotificationCenter.messagesDidLoad, cacheKey, ArrayList(fromDb), true, hasMoreBottom, true
                            )
                        } else {
                            Log.d(TAG, "loadMessagesAround: DB miss anchor=$anchorMessageId not in range=$dbMinId..$dbMaxId, waiting for API")
                        }
                    }

                    if (!anchorInDb && fromDb.isNotEmpty()) {
                        Log.d(TAG, "Offline — anchor not in DB, showing latest cached as fallback")
                        val fallback = messageDao.getLatestByChannel(cacheKey, PAGE_SIZE * 4)
                        if (fallback.isNotEmpty()) {
                            notificationCenter.postNotificationOnMainThread(
                                NotificationCenter.messagesDidLoad, cacheKey, ArrayList(fallback), true, false, true
                            )
                        }
                    } else if (fromDb.isEmpty()) {
                        Log.d(TAG, "Offline — no cached messages for channel $cacheKey (around)")
                    }
                    return@launch
                }

                sessionManager.withAutoRefresh { session ->
                    val currentUserId = session.userId.toLongOrNull() ?: 0L
                    val response = api.listChannelMessages(
                        session.apiUrl,
                        session.token,
                        channelId,
                        clanId,
                        anchorMessageId,
                        DIRECTION_AROUND,
                        PAGE_SIZE,
                        topicId = topicId,
                        preferHttp = preferHttp
                    )
                    val allMsgs = response.messagesList.map { it.toMessageEntity(currentUserId) }
                    val hasMoreTop = computeHasMoreTop(
                        topicId,
                        response.messagesList.size,
                        allMsgs.filter { it.isRenderable }
                    )

                    val msgs = remapForCache(
                        allMsgs.filter { it.isRenderable }.sortedBy { it.id },
                        cacheKey
                    )

                    if (msgs.isNotEmpty()) {
                        messageDao.upsertAll(msgs)
                        messageDao.trimAround(cacheKey, anchorMessageId, PAGE_SIZE * 2)
                        synchronized(this@ChatController) { initialFetchDone.add(cacheKey) }
                        val serverLastSentId = if (
                            response.hasLastSentMessage() &&
                            response.lastSentMessage.canAdvanceServerTimeline()
                        ) response.lastSentMessage.id else 0L
                        updateLastMessageByChannel(cacheKey, msgs, serverLastSentId)
                        Log.d(TAG, "loadMessagesAround: anchor=$anchorMessageId count=${msgs.size} hasMoreTop=$hasMoreTop topicId=$topicId hasLastSentMessage=${response.hasLastSentMessage()} serverLastSentId=$serverLastSentId")
                        val serverLastSeenId = if (response.hasLastSeenMessage()) response.lastSeenMessage.id else 0L
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.messagesDidLoad, cacheKey, ArrayList(msgs), hasMoreTop, true, false, serverLastSeenId
                        )
                    }
                    cacheTracker.markCalled(cacheTrackerKey)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadMessagesAround failed for channel $cacheKey", e)
                sentryReporter.logChatFailure("loadMessagesAround", cacheKey, clanId, e)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.messagesLoadError, cacheKey, e.message ?: "Failed to load"
                )
            }
        }
    }

    fun loadMoreBottom(channelId: Long, clanId: Long, newestMessageId: Long, topicId: Long = 0L) {
        val cacheKey = messageCacheKey(channelId, topicId)
        appScope.launch(ioDispatcher) {
            try {
                if (!networkMonitor.isOnline.value) {
                    val fromDb = messageDao.getMessagesAfter(cacheKey, newestMessageId, PAGE_SIZE)
                    val hasMoreBottom = fromDb.size >= PAGE_SIZE
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesDidLoad, cacheKey, ArrayList(fromDb),
                        false, hasMoreBottom, false, 0L, LOAD_TYPE_MORE_BOTTOM
                    )
                    return@launch
                }

                sessionManager.withAutoRefresh { session ->
                    val currentUserId = session.userId.toLongOrNull() ?: 0L
                    val response = api.listChannelMessages(
                        session.apiUrl, session.token, channelId, clanId,
                        newestMessageId, DIRECTION_AFTER, PAGE_SIZE, topicId = topicId
                    )
                    val newerRenderable = remapForCache(
                        response.messagesList
                            .map { it.toMessageEntity(currentUserId) }
                            .filter { it.isRenderable }
                            .sortedBy { it.id },
                        cacheKey
                    )

                    val hasMoreBottom = response.messagesList.size >= PAGE_SIZE
                    val serverLastSentId = if (
                        response.hasLastSentMessage() &&
                        response.lastSentMessage.canAdvanceServerTimeline()
                    ) response.lastSentMessage.id else 0L
                    updateLastMessageByChannel(cacheKey, newerRenderable, serverLastSentId)
                    if (newerRenderable.isNotEmpty()) {
                        messageDao.upsertAll(newerRenderable)
                        messageDao.trimAround(cacheKey, newestMessageId, PAGE_SIZE * 2)
                    }
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesDidLoad, cacheKey, ArrayList(newerRenderable), false, hasMoreBottom, false, 0L, LOAD_TYPE_MORE_BOTTOM
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadMoreBottom failed", e)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.messagesLoadError, cacheKey, e.message ?: "Load more failed"
                )
            }
        }
    }

    fun loadMoreTop(channelId: Long, clanId: Long, oldestMessageId: Long, topicId: Long = 0L) {
        val cacheKey = messageCacheKey(channelId, topicId)
        appScope.launch(ioDispatcher) {
            try {
                if (!networkMonitor.isOnline.value) {
                    val fromDb = messageDao.getMessagesBefore(cacheKey, oldestMessageId, PAGE_SIZE)
                    val hasMoreTop = fromDb.size >= PAGE_SIZE
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesDidLoad, cacheKey, ArrayList(fromDb.sortedByDescending { it.id }),
                        hasMoreTop, false, false, 0L, LOAD_TYPE_MORE_TOP
                    )
                    return@launch
                }

                sessionManager.withAutoRefresh { session ->
                    val currentUserId = session.userId.toLongOrNull() ?: 0L
                    val response = api.listChannelMessages(
                        session.apiUrl, session.token, channelId, clanId,
                        oldestMessageId, DIRECTION_BEFORE, PAGE_SIZE, topicId = topicId
                    )
                    val allOlder = response.messagesList.map { it.toMessageEntity(currentUserId) }
                    val older = remapForCache(
                        allOlder.filter { it.isRenderable }.sortedByDescending { it.id },
                        cacheKey
                    )
                    val hasMoreTop = computeHasMoreTop(
                        topicId,
                        response.messagesList.size,
                        older
                    )

                    if (older.isNotEmpty()) {
                        messageDao.upsertAll(older)
                        messageDao.trimAround(cacheKey, oldestMessageId, PAGE_SIZE * 2)
                    }
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesDidLoad, cacheKey, ArrayList(older), hasMoreTop, false, false, 0L, LOAD_TYPE_MORE_TOP
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadMoreTop failed", e)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.messagesLoadError, cacheKey, e.message ?: "Load more failed"
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
        badgeCount: Int = 0,
        applyLocal: Boolean = true
    ) {
        badgeCoordinator.scheduleLastSeenWrite(
            channelId, clanId, channelType, messageId, timestampSeconds, badgeCount, applyLocal
        )
    }

    private suspend fun channelSend(
        apiUrl: String,
        token: String,
        request: ChannelMessageSend
    ): com.mezon.mezon.rtapi.ChannelMessageAck {
        if (mezonSocket.connectionState.value == ConnectionState.CONNECTED) {
            try {
                return withContext(ioDispatcher) {
                    val env = mezonSocket.send { channelMessageSend = request }
                    if (env.messageCase != Envelope.MessageCase.CHANNEL_MESSAGE_ACK) {
                        throw IllegalStateException("unexpected envelope ${env.messageCase}")
                    }
                    env.channelMessageAck
                }
            } catch (e: Exception) {
                Log.w(TAG, "Channel message send via socket failed, using REST", e)
                sentryReporter.logSocketWarning(
                    "channelMessageSend",
                    "fallback REST channelId=${request.channelId} clanId=${request.clanId} err=${e.message}"
                )
            }
        }
        return withContext(ioDispatcher) {
            api.sendChannelMessage(apiUrl, token, request)
        }
    }

    fun sendMessage(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        isChannelPrivate: Boolean,
        text: String,
        references: List<com.mezon.mezon.api.MessageRef>? = null,
        mentions: List<MentionData>? = null,
        emojiMarkers: List<EmojiMarker>? = null,
        markdownMarkers: List<MarkdownMarker>? = null,
        ogpMarker: OgpMarker? = null,
        hashtags: List<com.mezon.mobile.util.HashtagData>? = null,
        topicId: Long = 0L
    ) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val cacheKey = messageCacheKey(channelId, topicId)
        val hasContentExtras = !emojiMarkers.isNullOrEmpty() || !markdownMarkers.isNullOrEmpty() || ogpMarker != null || !hashtags.isNullOrEmpty()
        val content = if (!hasContentExtras) buildTextContent(text)
            else buildTextContentWithEmojis(text, null, emojiMarkers, markdownMarkers, hashtags, ogpMarker)
        val mentionEveryone = mentions?.any { it.userId == ID_MENTION_HERE } == true
        val protoMentions = mentions?.map { m ->
            messageMention {
                if (m.userId.isNotBlank()) userId = m.userId.toLongOrNull() ?: 0L
                if (m.roleId.isNotBlank()) roleId = m.roleId.toLongOrNull() ?: 0L
                if (m.display.isNotBlank()) username = m.display
                s = m.startOffset
                e = m.endOffset
            }
        }

        val tempId = generateTempId(cacheKey)
        val uc = userController.get()
        val anon = isAnonymousSend(clanId)
        val (optName, optAvatar) = optimisticSenderPresentation(uc, clanId, channelType, anon)
        val optimisticContent = mergePendingMentionsIntoContent(
            mergeRefsIntoOptimisticContent(content, references),
            mentions
        )
        val optimistic = MessageEntity(
            id = tempId,
            channelId = cacheKey,
            senderId = if (anon) ANONYMOUS_USER_ID else uc.userId,
            senderName = optName,
            senderUsername = if (anon) "Anonymous" else uc.username,
            senderAvatar = optAvatar,
            content = optimisticContent,
            timestampSeconds = System.currentTimeMillis() / 1000,
            code = MessageEntity.CODE_CHAT,
            isMe = true,
            sendState = MessageEntity.SEND_STATE_SENDING
        )
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.didReceiveNewMessages, cacheKey, optimistic
        )

        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    ensureActiveArchivedThreadIfNeeded(session.apiUrl, session.token, channelId, clanId, channelType)
                    ensureMentionedUsersInThread(
                        session.apiUrl,
                        session.token,
                        channelId,
                        clanId,
                        channelType,
                        mentions
                    )
                    val request = channelMessageSend {
                        this.clanId = clanId
                        this.channelId = channelId
                        this.mode = mode
                        this.isPublic = isPublic
                        this.content = content
                        protoMentions?.let { this.mentions.addAll(it) }
                        references?.let { this.references.addAll(it) }
                        this.mentionEveryone = mentionEveryone
                        if (anon) this.anonymousMessage = true
                        if (topicId != 0L) this.topicId = topicId
                    }
                    val ack = channelSend(session.apiUrl, session.token, request)
                    markForwardTargetUsed(channelId, channelType)
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.pendingMessageSent, cacheKey, tempId, ack.messageId
                    )
                }
            } catch (e: Exception) {
                sentryReporter.logChatFailure("sendMessage", cacheKey, clanId, e)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.pendingMessageError, cacheKey, tempId
                )
            }
        }
    }

    suspend fun sendRawChannelMessage(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        isChannelPrivate: Boolean,
        contentJson: String
    ): Long {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val tempId = generateTempId(channelId)
        val uc = userController.get()
        val anon = isAnonymousSend(clanId)
        val (optName, optAvatar) = optimisticSenderPresentation(uc, clanId, channelType, anon)
        val optimistic = MessageEntity(
            id = tempId,
            channelId = channelId,
            senderId = if (anon) ANONYMOUS_USER_ID else uc.userId,
            senderName = optName,
            senderUsername = if (anon) "Anonymous" else uc.username,
            senderAvatar = optAvatar,
            content = contentJson,
            timestampSeconds = System.currentTimeMillis() / 1000,
            code = MessageEntity.CODE_CHAT,
            isMe = true,
            sendState = MessageEntity.SEND_STATE_SENDING
        )
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.didReceiveNewMessages, channelId, optimistic
        )
        return try {
            sessionManager.withAutoRefresh { session ->
                ensureActiveArchivedThreadIfNeeded(session.apiUrl, session.token, channelId, clanId, channelType)
                val request = channelMessageSend {
                    this.clanId = clanId
                    this.channelId = channelId
                    this.mode = mode
                    this.isPublic = isPublic
                    this.content = contentJson
                    if (anon) this.anonymousMessage = true
                }
                val ack = channelSend(session.apiUrl, session.token, request)
                markForwardTargetUsed(channelId, channelType)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.pendingMessageSent, channelId, tempId, ack.messageId
                )
                ack.messageId
            }
        } catch (_: Exception) {
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.pendingMessageError, channelId, tempId
            )
            0L
        }
    }

    fun sendDirectAttachment(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        isChannelPrivate: Boolean,
        url: String,
        filetype: String,
        filename: String? = null,
        references: List<com.mezon.mezon.api.MessageRef>? = null,
        topicId: Long = 0L
    ) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val cacheKey = messageCacheKey(channelId, topicId)
        val baseContent = "{\"t\":\"\"}"
        val content = mergeRefsIntoOptimisticContent(baseContent, references)
        val attachment = messageAttachment {
            this.url = url
            this.filetype = filetype
            if (filename != null) this.filename = filename
        }

        val tempId = generateTempId(cacheKey)
        val uc = userController.get()
        val anon = isAnonymousSend(clanId)
        val (optName, optAvatar) = optimisticSenderPresentation(uc, clanId, channelType, anon)
        val msgType = when {
            filetype.startsWith("image/gif") || filetype.endsWith("gif") -> MessageEntity.TYPE_GIF
            filetype.startsWith("image/") -> MessageEntity.TYPE_PHOTO
            filetype.startsWith("audio/") -> MessageEntity.TYPE_FILE
            else -> MessageEntity.TYPE_GIF
        }
        val optimistic = MessageEntity(
            id = tempId,
            channelId = cacheKey,
            senderId = if (anon) ANONYMOUS_USER_ID else uc.userId,
            senderName = optName,
            senderUsername = if (anon) "Anonymous" else uc.username,
            senderAvatar = optAvatar,
            content = content,
            timestampSeconds = System.currentTimeMillis() / 1000,
            code = MessageEntity.CODE_CHAT,
            isMe = true,
            messageType = msgType,
            attachmentUrl = url,
            attachmentFiletype = filetype,
            attachmentFilename = filename.orEmpty(),
            sendState = MessageEntity.SEND_STATE_SENDING
        )
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.didReceiveNewMessages, cacheKey, optimistic
        )

        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    ensureActiveArchivedThreadIfNeeded(session.apiUrl, session.token, channelId, clanId, channelType)
                    val request = channelMessageSend {
                        this.clanId = clanId
                        this.channelId = channelId
                        this.mode = mode
                        this.isPublic = isPublic
                        this.content = baseContent
                        this.attachments.add(attachment)
                        references?.let { this.references.addAll(it) }
                        if (anon) this.anonymousMessage = true
                        if (topicId != 0L) this.topicId = topicId
                    }
                    val ack = channelSend(session.apiUrl, session.token, request)
                    markForwardTargetUsed(channelId, channelType)
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.pendingMessageSent, cacheKey, tempId, ack.messageId
                    )
                    Log.d(TAG, "Direct attachment sent: channelId=$channelId url=${url.take(60)}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send direct attachment", e)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.pendingMessageError, cacheKey, tempId
                )
            }
        }
    }

    fun sendLocation(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        isChannelPrivate: Boolean,
        latitude: Double,
        longitude: Double
    ) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val googleMapsLink = "https://www.google.com/maps?q=$latitude,$longitude&z=14&t=m&mapclient=embed"
        val content = buildLocationContent(googleMapsLink)

        val tempId = generateTempId(channelId)
        val uc = userController.get()
        val anon = isAnonymousSend(clanId)
        val (optName, optAvatar) = optimisticSenderPresentation(uc, clanId, channelType, anon)
        val optimistic = MessageEntity(
            id = tempId,
            channelId = channelId,
            senderId = if (anon) ANONYMOUS_USER_ID else uc.userId,
            senderName = optName,
            senderUsername = if (anon) "Anonymous" else uc.username,
            senderAvatar = optAvatar,
            content = content,
            timestampSeconds = System.currentTimeMillis() / 1000,
            code = MessageEntity.CODE_LOCATION,
            isMe = true,
            sendState = MessageEntity.SEND_STATE_SENDING
        )
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.didReceiveNewMessages, channelId, optimistic
        )

        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    ensureActiveArchivedThreadIfNeeded(session.apiUrl, session.token, channelId, clanId, channelType)
                    val request = channelMessageSend {
                        this.clanId = clanId
                        this.channelId = channelId
                        this.mode = mode
                        this.isPublic = isPublic
                        this.content = content
                        this.code = MessageEntity.CODE_LOCATION
                        if (anon) this.anonymousMessage = true
                    }
                    val ack = channelSend(session.apiUrl, session.token, request)
                    markForwardTargetUsed(channelId, channelType)
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.pendingMessageSent, channelId, tempId, ack.messageId
                    )
                    Log.d(TAG, "Location sent: channelId=$channelId lat=$latitude lng=$longitude")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send location", e)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.pendingMessageError, channelId, tempId
                )
            }
        }
    }

    fun sendShareContact(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        isChannelPrivate: Boolean,
        data: ShareContactData
    ) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val content = buildShareContactContent(data)

        val tempId = generateTempId(channelId)
        val uc = userController.get()
        val anon = isAnonymousSend(clanId)
        val (optName, optAvatar) = optimisticSenderPresentation(uc, clanId, channelType, anon)
        val optimistic = MessageEntity(
            id = tempId,
            channelId = channelId,
            senderId = if (anon) ANONYMOUS_USER_ID else uc.userId,
            senderName = optName,
            senderUsername = if (anon) "Anonymous" else uc.username,
            senderAvatar = optAvatar,
            content = content,
            timestampSeconds = System.currentTimeMillis() / 1000,
            code = MessageEntity.CODE_SHARE_CONTACT,
            isMe = true,
            sendState = MessageEntity.SEND_STATE_SENDING
        )
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.didReceiveNewMessages, channelId, optimistic
        )

        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    ensureActiveArchivedThreadIfNeeded(session.apiUrl, session.token, channelId, clanId, channelType)
                    val request = channelMessageSend {
                        this.clanId = clanId
                        this.channelId = channelId
                        this.mode = mode
                        this.isPublic = isPublic
                        this.content = content
                        this.code = MessageEntity.CODE_SHARE_CONTACT
                        if (anon) this.anonymousMessage = true
                    }
                    val ack = channelSend(session.apiUrl, session.token, request)
                    markForwardTargetUsed(channelId, channelType)
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.pendingMessageSent, channelId, tempId, ack.messageId
                    )
                    Log.d(TAG, "Share contact sent: channelId=$channelId userId=${data.userId}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send share contact", e)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.pendingMessageError, channelId, tempId
                )
            }
        }
    }

    fun sendBuzzMessage(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        isChannelPrivate: Boolean,
        text: String
    ) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val content = buildTextContent(text.ifBlank { "Buzz!!" })

        val tempId = generateTempId(channelId)
        val uc = userController.get()
        val anon = isAnonymousSend(clanId)
        val (optName, optAvatar) = optimisticSenderPresentation(uc, clanId, channelType, anon)
        val optimistic = MessageEntity(
            id = tempId,
            channelId = channelId,
            senderId = if (anon) ANONYMOUS_USER_ID else uc.userId,
            senderName = optName,
            senderUsername = if (anon) "Anonymous" else uc.username,
            senderAvatar = optAvatar,
            content = content,
            timestampSeconds = System.currentTimeMillis() / 1000,
            code = MessageEntity.CODE_MESSAGE_BUZZ,
            isMe = true,
            sendState = MessageEntity.SEND_STATE_SENDING
        )
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.didReceiveNewMessages, channelId, optimistic
        )

        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    ensureActiveArchivedThreadIfNeeded(session.apiUrl, session.token, channelId, clanId, channelType)
                    val request = channelMessageSend {
                        this.clanId = clanId
                        this.channelId = channelId
                        this.mode = mode
                        this.isPublic = isPublic
                        this.content = content
                        this.code = MessageEntity.CODE_MESSAGE_BUZZ
                        if (anon) this.anonymousMessage = true
                    }
                    val ack = channelSend(session.apiUrl, session.token, request)
                    markForwardTargetUsed(channelId, channelType)
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.pendingMessageSent, channelId, tempId, ack.messageId
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send buzz message", e)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.pendingMessageError, channelId, tempId
                )
            }
        }
    }

    private fun buildLocationContent(link: String): String {
        val escaped = link
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return "{\"t\":\"$escaped\",\"lk\":[{\"s\":0,\"e\":${link.length}}],\"mk\":[{\"s\":0,\"e\":${link.length},\"type\":\"lk\"}]}"
    }

    companion object {
        const val ID_MENTION_HERE = MENTION_HERE_USER_ID
        private const val SEND_MAX_RETRIES = 2
        private const val SEND_RETRY_DELAY_MS = 500L
        private const val SHARE_MAX_RETRIES = 5
        private const val SHARE_RETRY_DELAY_MS = 4000L
        private const val PENDING_API_REACTION_DEDUP_MS = 5000L
    }

    private fun generateTempId(channelId: Long): Long {
        synchronized(this) {
            val lastServerId = lastMessageByChannel.get(channelId, 0L)
            val lastTempId = pendingTempMessageByChannel.get(channelId, 0L)
            val baseId = maxOf(lastServerId, lastTempId)
            val tempId = if (baseId > 0L) baseId + 1L else MezonSnowflake.generate()
            pendingTempMessageByChannel.put(channelId, tempId)
            return tempId
        }
    }

    private fun markForwardTargetUsed(channelId: Long, channelType: Int) {
        forwardTargetUsageStore.markLastSent(channelId, channelType)
    }

    private suspend fun ensureActiveArchivedThreadIfNeeded(
        apiUrl: String,
        token: String,
        channelId: Long,
        clanId: Long,
        channelType: Int
    ) {
        if (clanId == 0L) return
        val ch = channelController.get().findChannelById(channelId, clanId)
        val effectiveType = if (channelType != 0) channelType else ch?.type ?: 0
        val parentId = ch?.parentId ?: 0L
        val threadLike = effectiveType == CHANNEL_TYPE_THREAD || parentId != 0L
        if (!threadLike) return
        if (ch != null) {
            val now = System.currentTimeMillis() / 1000L
            val lastTs = ch.lastSentMessageTs
            val archivedByAge = lastTs > 0L && (now - lastTs) > THREAD_ARCHIVE_DURATION_SECONDS
            if (ch.active != 0 && !archivedByAge) return
        }
        withContext(ioDispatcher) {
            api.activeArchivedThread(apiUrl, token, clanId, channelId)
        }
        if (ch != null) {
            channelController.get().upsertChannel(ch.copy(active = ThreadStatus.JOINED))
        }
    }

    private fun mergeRefsIntoOptimisticContent(
        baseContent: String,
        references: List<com.mezon.mezon.api.MessageRef>?
    ): String {
        if (references.isNullOrEmpty()) return baseContent
        return try {
            val obj = org.json.JSONObject(baseContent)
            if (obj.has("references")) return baseContent
            val arr = org.json.JSONArray()
            for (ref in references) {
                val item = org.json.JSONObject()
                item.put("message_id", ref.messageId.toString())
                item.put("message_ref_id", ref.messageRefId.toString())
                item.put("ref_type", ref.refType)
                item.put("message_sender_id", ref.messageSenderId.toString())
                item.put("message_sender_username", ref.messageSenderUsername)
                item.put("message_sender_avatar", ref.messageSenderAvatar)
                item.put("mesages_sender_avatar", ref.messageSenderAvatar)
                item.put("message_sender_clan_nick", ref.messageSenderClanNick)
                item.put("message_sender_display_name", ref.messageSenderDisplayName)
                item.put("content", ref.content)
                item.put("has_attachment", ref.hasAttachment)
                arr.put(item)
            }
            obj.put("references", arr)
            obj.toString()
        } catch (_: Exception) {
            baseContent
        }
    }

    fun sendMessageWithAttachments(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        isChannelPrivate: Boolean,
        text: String,
        attachments: List<AttachmentPickerItem>,
        contentResolver: android.content.ContentResolver,
        references: List<com.mezon.mezon.api.MessageRef>? = null,
        mentions: List<MentionData>? = null,
        hashtags: List<com.mezon.mobile.util.HashtagData>? = null,
        emojiMarkers: List<EmojiMarker>? = null,
        ogpMarker: OgpMarker? = null,
        topicId: Long = 0L
    ) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val cacheKey = messageCacheKey(channelId, topicId)
        val hasContentExtras = !hashtags.isNullOrEmpty() || !emojiMarkers.isNullOrEmpty() || ogpMarker != null
        val wireBase = when {
            text.isBlank() -> "{\"t\":\"\"}"
            hasContentExtras -> buildTextContentWithEmojis(text, null, emojiMarkers, null, hashtags, ogpMarker)
            else -> buildTextContent(text)
        }
        val optimisticContent = mergePendingMentionsIntoContent(
            mergeRefsIntoOptimisticContent(wireBase, references),
            mentions
        )
        val mentionEveryone = mentions?.any { it.userId == ID_MENTION_HERE } == true
        val protoMentions = mentions?.map { m ->
            messageMention {
                if (m.userId.isNotBlank()) userId = m.userId.toLongOrNull() ?: 0L
                if (m.roleId.isNotBlank()) roleId = m.roleId.toLongOrNull() ?: 0L
                if (m.display.isNotBlank()) username = m.display
                s = m.startOffset
                e = m.endOffset
            }
        }

        val tempId = generateTempId(cacheKey)
        val uc = userController.get()
        val anon = isAnonymousSend(clanId)
        val (optName, optAvatar) = optimisticSenderPresentation(uc, clanId, channelType, anon)
        val firstItem = attachments.firstOrNull()
        val extraJson = if (attachments.size > 1) {
            val arr = org.json.JSONArray()
            for (i in 1 until attachments.size) {
                val item = attachments[i]
                val obj = org.json.JSONObject()
                obj.put("url", item.uri.toString())
                obj.put("thumb", "")
                obj.put("width", item.width)
                obj.put("height", item.height)
                obj.put("filename", item.filename)
                obj.put("filetype", item.mimeType)
                obj.put("size", item.size.toInt())
                obj.put("duration", item.duration)
                arr.put(obj)
            }
            arr.toString()
        } else ""
        val optimistic = MessageEntity(
            id = tempId,
            channelId = cacheKey,
            senderId = if (anon) ANONYMOUS_USER_ID else uc.userId,
            senderName = optName,
            senderUsername = if (anon) "Anonymous" else uc.username,
            senderAvatar = optAvatar,
            content = optimisticContent,
            timestampSeconds = System.currentTimeMillis() / 1000,
            code = MessageEntity.CODE_CHAT,
            isMe = true,
            messageType = resolveOptimisticType(firstItem),
            attachmentUrl = firstItem?.uri?.toString().orEmpty(),
            attachmentFilename = firstItem?.filename.orEmpty(),
            attachmentFiletype = firstItem?.mimeType.orEmpty(),
            attachmentWidth = firstItem?.width ?: 0,
            attachmentHeight = firstItem?.height ?: 0,
            extraAttachmentsJson = extraJson,
            sendState = MessageEntity.SEND_STATE_SENDING
        )
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.didReceiveNewMessages, cacheKey, optimistic
        )

        appScope.launch(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
                    ensureActiveArchivedThreadIfNeeded(session.apiUrl, session.token, channelId, clanId, channelType)
                    ensureMentionedUsersInThread(
                        session.apiUrl,
                        session.token,
                        channelId,
                        clanId,
                        channelType,
                        mentions
                    )
                    val cdnBaseUrl = BuildConfig.MEZON_BASE_IMG_URL
                    val uploadedAttachments = ArrayList<MessageAttachment>()

                    for (item in attachments) {
                        try {
                            if (com.mezon.mobile.util.AttachmentUploader.isOverSizeLimit(item.size)) {
                                Log.e(TAG, "Attachment too large, skipping: ${item.filename} size=${item.size}")
                                continue
                            }
                            val timestamp = System.currentTimeMillis() / 1000
                            val sanitizedName = item.filename.replace(FILENAME_SANITIZE_REGEX, "_")
                            val uploadFilename = "${timestamp}_$sanitizedName"

                            val presignResult = api.uploadAttachmentFile(
                                session.apiUrl, session.token,
                                uploadFilename, item.mimeType,
                                item.size.toInt(), item.width, item.height
                            )

                            val fileBytes = com.mezon.mobile.util.AttachmentUploader.readUriBytesSafely(
                                contentResolver, item.uri, item.mimeType, appContext.cacheDir
                            )
                            if (fileBytes == null) {
                                Log.e(TAG, "Failed to read file or over size: ${item.filename}")
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
                        val request = channelMessageSend {
                            this.clanId = clanId
                            this.channelId = channelId
                            this.mode = mode
                            this.isPublic = isPublic
                            this.content = wireBase
                            this.attachments.addAll(uploadedAttachments)
                            protoMentions?.let { this.mentions.addAll(it) }
                            references?.let { this.references.addAll(it) }
                            this.mentionEveryone = mentionEveryone
                            if (anon) this.anonymousMessage = true
                            if (topicId != 0L) this.topicId = topicId
                        }
                        val ack = channelSend(session.apiUrl, session.token, request)
                        markForwardTargetUsed(channelId, channelType)
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.pendingMessageSent, cacheKey, tempId, ack.messageId
                        )
                    } else {
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.pendingMessageError, cacheKey, tempId
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message with attachments", e)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.pendingMessageError, cacheKey, tempId
                )
            }
        }
    }

    fun shareMediaToChannel(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        isChannelPrivate: Boolean,
        text: String,
        attachments: List<AttachmentPickerItem>,
        contentResolver: android.content.ContentResolver,
        markdownMarkers: List<MarkdownMarker>? = null
    ) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val baseContent = when {
            text.isBlank() -> "{\"t\":\"\"}"
            !markdownMarkers.isNullOrEmpty() -> buildTextContentWithEmojis(text, null, null, markdownMarkers)
            else -> buildTextContent(text)
        }

        val tempId = generateTempId(channelId)
        val uc = userController.get()
        val anon = isAnonymousSend(clanId)
        val (optName, optAvatar) = optimisticSenderPresentation(uc, clanId, channelType, anon)
        val firstItem = attachments.firstOrNull()
        val extraJson = if (attachments.size > 1) {
            val arr = org.json.JSONArray()
            for (i in 1 until attachments.size) {
                val item = attachments[i]
                val obj = org.json.JSONObject()
                obj.put("url", item.uri.toString())
                obj.put("thumb", "")
                obj.put("width", item.width)
                obj.put("height", item.height)
                obj.put("filename", item.filename)
                obj.put("filetype", item.mimeType)
                obj.put("size", item.size.toInt())
                obj.put("duration", item.duration)
                arr.put(obj)
            }
            arr.toString()
        } else ""

        val optimistic = MessageEntity(
            id = tempId,
            channelId = channelId,
            senderId = if (anon) ANONYMOUS_USER_ID else uc.userId,
            senderName = optName,
            senderUsername = if (anon) "Anonymous" else uc.username,
            senderAvatar = optAvatar,
            content = baseContent,
            timestampSeconds = System.currentTimeMillis() / 1000,
            code = MessageEntity.CODE_CHAT,
            isMe = true,
            messageType = resolveOptimisticType(firstItem),
            attachmentUrl = firstItem?.uri?.toString().orEmpty(),
            attachmentFilename = firstItem?.filename.orEmpty(),
            attachmentFiletype = firstItem?.mimeType.orEmpty(),
            attachmentWidth = firstItem?.width ?: 0,
            attachmentHeight = firstItem?.height ?: 0,
            extraAttachmentsJson = extraJson,
            sendState = MessageEntity.SEND_STATE_SENDING
        )
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.didReceiveNewMessages, channelId, optimistic
        )

        appScope.launch(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
                    ensureActiveArchivedThreadIfNeeded(session.apiUrl, session.token, channelId, clanId, channelType)
                    val cdnBaseUrl = BuildConfig.MEZON_BASE_IMG_URL
                    val uploadedAttachments = ArrayList<MessageAttachment>()

                    for (item in attachments) {
                        if (com.mezon.mobile.util.AttachmentUploader.isOverSizeLimit(item.size)) {
                            Log.e(TAG, "shareMedia: attachment too large, skipping ${item.filename} size=${item.size}")
                            continue
                        }
                        var uploaded = false
                        for (attempt in 1..SHARE_MAX_RETRIES) {
                            try {
                                val timestamp = System.currentTimeMillis() / 1000
                                val sanitizedName = item.filename.replace(FILENAME_SANITIZE_REGEX, "_")
                                val uploadFilename = "${timestamp}_$sanitizedName"

                                val presignResult = api.uploadAttachmentFile(
                                    session.apiUrl, session.token,
                                    uploadFilename, item.mimeType,
                                    item.size.toInt(), item.width, item.height
                                )

                                val fileBytes = com.mezon.mobile.util.AttachmentUploader.readUriBytesSafely(
                                    contentResolver, item.uri, item.mimeType, appContext.cacheDir
                                )
                                if (fileBytes == null) {
                                    Log.e(TAG, "shareMedia: Failed to read file or over size: ${item.filename}")
                                    break
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
                                uploaded = true
                                Log.d(TAG, "shareMedia: Uploaded ${item.filename} → $cdnUrl (attempt $attempt)")
                                break
                            } catch (e: Exception) {
                                Log.e(TAG, "shareMedia: Upload attempt $attempt failed for ${item.filename}", e)
                                if (attempt < SHARE_MAX_RETRIES) {
                                    delay(SHARE_RETRY_DELAY_MS)
                                }
                            }
                        }
                        if (!uploaded) {
                            Log.e(TAG, "shareMedia: All retries exhausted for ${item.filename}")
                        }
                    }

                    if (uploadedAttachments.isNotEmpty()) {
                        val request = channelMessageSend {
                            this.clanId = clanId
                            this.channelId = channelId
                            this.mode = mode
                            this.isPublic = isPublic
                            this.content = baseContent
                            this.attachments.addAll(uploadedAttachments)
                            if (anon) this.anonymousMessage = true
                        }
                        val ack = channelSend(session.apiUrl, session.token, request)
                        markForwardTargetUsed(channelId, channelType)
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.pendingMessageSent, channelId, tempId, ack.messageId
                        )
                        Log.d(TAG, "shareMedia: Sent ${uploadedAttachments.size}/${attachments.size} attachments to channel $channelId")
                    } else {
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.pendingMessageError, channelId, tempId
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "shareMedia: Failed", e)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.pendingMessageError, channelId, tempId
                )
            }
        }
    }

    private fun resolveOptimisticType(item: AttachmentPickerItem?): Int {
        if (item == null) return MessageEntity.TYPE_TEXT
        val ft = item.mimeType.lowercase()
        return when {
            ft.startsWith("image/gif") -> MessageEntity.TYPE_GIF
            ft.startsWith("image/") -> MessageEntity.TYPE_PHOTO
            ft.startsWith("video/") -> MessageEntity.TYPE_VIDEO
            else -> MessageEntity.TYPE_FILE
        }
    }

    private suspend fun ensureMentionedUsersInThread(
        apiUrl: String,
        token: String,
        channelId: Long,
        clanId: Long,
        channelType: Int,
        mentions: List<MentionData>?
    ) {
        if (channelType != CHANNEL_TYPE_THREAD || clanId == 0L || mentions.isNullOrEmpty()) return
        val selfId = getCurrentUserId()
        val existingIds = userClanController.get().getChannelMembers(channelId)
            .asSequence()
            .map { it.userId }
            .toHashSet()
        val missing = mentions.asSequence()
            .map { it.userId }
            .filter { it.isNotBlank() && it != MENTION_HERE_USER_ID }
            .mapNotNull { it.toLongOrNull() }
            .filter { it != 0L && it != selfId && !existingIds.contains(it) }
            .distinct()
            .toList()
        if (missing.isEmpty()) return
        try {
            api.addChannelUsers(apiUrl, token, channelId, missing)
            userClanController.get().loadChannelMembers(clanId, channelId, channelType, noCache = true)
            Log.d(TAG, "Added mentioned users to thread channelId=$channelId users=${missing.joinToString(",")}")
        } catch (e: Exception) {
            Log.e(TAG, "addChannelUsers failed channelId=$channelId users=${missing.joinToString(",")}", e)
        }
    }

    @Volatile private var cachedCurrentUserId = 0L

    fun getCurrentUserId(): Long {
        if (cachedCurrentUserId != 0L) return cachedCurrentUserId
        val fromStartup = com.mezon.mobile.core.StartupCache.userId.toLongOrNull() ?: 0L
        if (fromStartup != 0L) {
            cachedCurrentUserId = fromStartup
            return fromStartup
        }
        appScope.launch(ioDispatcher) {
            val session = sessionManager.sessionFlow.first()
            cachedCurrentUserId = session?.userId?.toLongOrNull() ?: 0L
        }
        return cachedCurrentUserId
    }

    fun editMessage(
        channelId: Long, clanId: Long, channelType: Int,
        isChannelPrivate: Boolean, messageId: Long, newText: String,
        mentions: List<MentionData>? = null,
        emojiMarkers: List<EmojiMarker>? = null,
        markdownMarkers: List<MarkdownMarker>? = null,
        hashtags: List<com.mezon.mobile.util.HashtagData>? = null,
        existingMessage: MessageEntity? = null
    ) {
        if (existingMessage != null && !existingMessage.canEditMessage(getCurrentUserId())) return
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val hasExtras = !mentions.isNullOrEmpty() || !emojiMarkers.isNullOrEmpty() ||
            !markdownMarkers.isNullOrEmpty() || !hashtags.isNullOrEmpty()
        val baseContent = if (hasExtras) {
            buildTextContentWithEmojis(newText, mentions, emojiMarkers, markdownMarkers, hashtags)
        } else {
            buildTextContent(newText)
        }
        val content = if (existingMessage != null && isShareContactMessage(existingMessage.code, existingMessage.content)) {
            mergeShareContactEmbedIntoContent(baseContent, existingMessage.content)
        } else {
            baseContent
        }
        val protoMentions = mentions?.map { m ->
            messageMention {
                if (m.userId.isNotBlank()) userId = m.userId.toLongOrNull() ?: 0L
                if (m.roleId.isNotBlank()) roleId = m.roleId.toLongOrNull() ?: 0L
                if (m.display.isNotBlank()) username = m.display
                s = m.startOffset
                e = m.endOffset
            }
        }
        val protoAttachments = existingMessage?.allAttachmentsInfo?.map { att ->
            messageAttachment {
                filename = att.filename
                size = att.size
                url = att.url
                filetype = att.filetype
                width = att.width
                height = att.height
                thumbnail = att.thumb
            }
        }
        val request = channelMessageUpdate {
            this.clanId = clanId
            this.channelId = channelId
            this.messageId = messageId
            this.content = content
            protoMentions?.let { this.mentions.addAll(it) }
            protoAttachments?.let { this.attachments.addAll(it) }
            this.mode = mode
            this.isPublic = isPublic
        }
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    api.updateChannelMessage(session.apiUrl, session.token, request)
                }
                applyLocalEdit(channelId, messageId, content)
                Log.d(TAG, "Message edited: channelId=$channelId messageId=$messageId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to edit message", e)
            }
        }
    }

    private suspend fun applyLocalEdit(channelId: Long, messageId: Long, content: String) {
        val updateTime = System.currentTimeMillis() / 1000L
        val existing = withContext(ioDispatcher) { messageDao.getById(channelId, messageId) }
        val updated = existing?.copy(
            content = content,
            updateTimeSeconds = updateTime,
            hideEditted = false,
            code = CODE_CHAT_UPDATE
        ) ?: return
        appScope.launch {
            messageDao.updateContent(channelId, messageId, content, updateTime, false, CODE_CHAT_UPDATE)
        }
        synchronized(this) {
            val last = dialogMessage.get(channelId)
            if (last != null && last.id == messageId) {
                dialogMessage.put(channelId, updated)
            }
        }
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.messageDidUpdate, channelId, updated,
            NotificationCenter.UPDATE_MASK_MESSAGE_TEXT
        )
    }

    fun deleteMessage(channelId: Long, clanId: Long, channelType: Int, isChannelPrivate: Boolean, messageId: Long, topicId: Long = 0L) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        appScope.launch {
            try {
                mezonSocket.removeChatMessage(clanId, channelId, mode, isPublic, messageId, topicId = topicId)
                Log.d(TAG, "Message deleted: channelId=$channelId messageId=$messageId topicId=$topicId isPublic=$isPublic")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete message", e)
            }
        }
    }

    private suspend fun observeSdTopicEvents() {
        socketEventDispatcher.sdTopicEvents.collect { event ->
            handleSdTopicEvent(event)
        }
    }

    private suspend fun handleSdTopicEvent(event: SdTopicEvent) {
        val parentChannelId = event.channelId
        val messageId = event.messageId
        val topicId = event.id
        val creatorId = event.userId
        cacheTopicRoot(topicId, parentChannelId, messageId)
        if (event.hasLastSentMessage() && event.lastSentMessage.id > 0L) {
            synchronized(this) {
                val lastId = event.lastSentMessage.id
                if (lastId > lastMessageByChannel.get(topicId, 0L)) {
                    lastMessageByChannel.put(topicId, lastId)
                }
            }
        }
        appScope.launch(ioDispatcher) {
            val existing = messageDao.getById(parentChannelId, messageId) ?: return@launch
            val updated = existing.withTopicCreated(topicId, creatorId)
            messageDao.upsert(updated)
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.messageDidUpdate, parentChannelId, updated, NotificationCenter.UPDATE_MASK_TOPIC
            )
        }
    }

    private suspend fun findTopicRootMessage(topicId: Long, parentChannelId: Long): MessageEntity? {
        val cachedRef = synchronized(this) { topicRootByTopicId.get(topicId) }
        if (cachedRef != null) {
            messageDao.getById(cachedRef.parentChannelId, cachedRef.rootMessageId)?.let { return it }
        }
        messageDao.getLatestByTopicId(parentChannelId, topicId)?.let { found ->
            cacheTopicRoot(topicId, parentChannelId, found.id)
            return found
        }
        val latest = messageDao.getLatestByChannel(parentChannelId, 200)
        return latest.firstOrNull { it.effectiveTopicId == topicId || it.topicId == topicId }
            ?: latest.firstOrNull {
                runCatching {
                    JSONObject(it.content).optString("tp", "0").toLongOrNull() == topicId
                }.getOrDefault(false)
            }?.also { found ->
                cacheTopicRoot(topicId, parentChannelId, found.id)
            }
    }

    private suspend fun updateTopicRootStats(
        topicId: Long,
        parentChannelId: Long,
        increment: Int,
        timestampSeconds: Long
    ) {
        val root = findTopicRootMessage(topicId, parentChannelId) ?: return
        val newRpl = (root.rplCount + increment).coerceAtLeast(0)
        val newLastSent = if (increment > 0 && timestampSeconds > 0L) timestampSeconds else root.lastSentSeconds
        if (newRpl == root.rplCount && newLastSent == root.lastSentSeconds) return
        val updated = root.withTopicStats(newRpl, newLastSent)
        messageDao.upsert(updated)
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.messageDidUpdate, parentChannelId, updated, NotificationCenter.UPDATE_MASK_TOPIC
        )
    }

    private suspend fun observeIncomingMessages() {
        val currentUserId = sessionManager.sessionFlow
            .first { it != null }?.userId?.toLongOrNull() ?: 0L

        socketEventDispatcher.channelMessages.collect { raw ->
            val msg = assignMissingMessageId(resolveEphemeralSender(raw, currentUserId))
            val entity = msg.toMessageEntity(currentUserId)
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "incoming CHANNEL_MESSAGE id=${entity.id} channel=${entity.channelId} topic=${msg.topicId} " +
                        "code=${entity.code} mode=${msg.mode} ts=${entity.timestampSeconds} updateTs=${entity.updateTimeSeconds} " +
                        "renderable=${entity.isRenderable} sender=${entity.senderId} isMe=${entity.isMe} " +
                        "content=${debugMessagePreview(entity.content)}"
                )
            }

            when (msg.code) {
                CODE_CHAT_UPDATE -> {
                    if (msg.topicId != 0L) {
                        appScope.launch(ioDispatcher) {
                            val topicEntity = remapForCache(entity, msg.topicId)
                            messageDao.updateContent(
                                topicEntity.channelId, topicEntity.id, topicEntity.content,
                                topicEntity.updateTimeSeconds, topicEntity.hideEditted, topicEntity.code
                            )
                            val merged = messageDao.getById(topicEntity.channelId, topicEntity.id) ?: topicEntity
                            notificationCenter.postNotificationOnMainThread(
                                NotificationCenter.messageDidUpdate, topicEntity.channelId, merged,
                                NotificationCenter.UPDATE_MASK_MESSAGE_TEXT
                            )
                        }
                        return@collect
                    }
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "incoming CHANNEL_MESSAGE as update id=${entity.id} channel=${entity.channelId}")
                    }
                    appScope.launch {
                        messageDao.updateContent(
                            entity.channelId, entity.id, entity.content,
                            entity.updateTimeSeconds, entity.hideEditted, entity.code
                        )
                        val merged = messageDao.getById(entity.channelId, entity.id) ?: entity
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.messageDidUpdate, merged.channelId, merged,
                            NotificationCenter.UPDATE_MASK_MESSAGE_TEXT
                        )
                    }
                }
                MessageEntity.CODE_UPDATE_EPHEMERAL -> {
                    val codeToStore = MessageEntity.CODE_EPHEMERAL
                    val notifyEntity = if (codeToStore != entity.code) entity.copy(code = codeToStore) else entity
                    appScope.launch(ioDispatcher) {
                        val existing = messageDao.getById(notifyEntity.channelId, notifyEntity.id)
                        if (existing == null) {
                            messageDao.upsert(notifyEntity)
                            notificationCenter.postNotificationOnMainThread(
                                NotificationCenter.didReceiveNewMessages, notifyEntity.channelId, notifyEntity
                            )
                            notificationCenter.postNotificationOnMainThread(
                                NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_NEW_MESSAGE
                            )
                        } else {
                            messageDao.updateContent(
                                entity.channelId, entity.id, entity.content,
                                entity.updateTimeSeconds, entity.hideEditted, codeToStore
                            )
                            notificationCenter.postNotificationOnMainThread(
                                NotificationCenter.messageDidUpdate, notifyEntity.channelId, notifyEntity,
                                NotificationCenter.UPDATE_MASK_MESSAGE_TEXT
                            )
                        }
                    }
                }
                CODE_CHAT_REMOVE,
                MessageEntity.CODE_DELETE_EPHEMERAL -> {
                    val deleteCacheKey = if (msg.topicId != 0L) msg.topicId else msg.channelId
                    appScope.launch { messageDao.delete(deleteCacheKey, msg.messageId) }
                    synchronized(this) {
                        if (lastMessageByChannel.get(deleteCacheKey, 0L) == msg.messageId) {
                            appScope.launch(ioDispatcher) {
                                val newLast = messageDao.getLatestByChannel(deleteCacheKey, PAGE_SIZE)
                                    .firstOrNull { it.canAdvanceServerTimeline() }
                                synchronized(this@ChatController) {
                                    if (newLast != null) lastMessageByChannel.put(deleteCacheKey, newLast.id)
                                    else lastMessageByChannel.delete(deleteCacheKey)
                                }
                            }
                        }
                    }
                    if (msg.topicId != 0L) {
                        appScope.launch(ioDispatcher) {
                            updateTopicRootStats(msg.topicId, msg.channelId, -1, 0L)
                        }
                    }
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messageDidDelete, deleteCacheKey, msg.messageId
                    )
                }
                else -> {
                    if (msg.topicId != 0L) {
                        if (!entity.isRenderable) return@collect
                        val topicEntity = remapForCache(entity, msg.topicId)
                        appScope.launch(ioDispatcher) {
                            messageDao.upsert(topicEntity)
                            updateTopicRootStats(msg.topicId, msg.channelId, 1, topicEntity.timestampSeconds)
                        }
                        if (topicEntity.canAdvanceServerTimeline()) {
                            synchronized(this) {
                                if (topicEntity.id > lastMessageByChannel.get(topicEntity.channelId, 0L)) {
                                    lastMessageByChannel.put(topicEntity.channelId, topicEntity.id)
                                }
                            }
                        }
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.didReceiveNewMessages, topicEntity.channelId, topicEntity
                        )
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_NEW_MESSAGE
                        )
                        topicBadgeTracker.tryIncrementForMention(
                            entity,
                            msg.channelId,
                            msg.topicId,
                            msg.clanId,
                            currentUserId
                        )
                        return@collect
                    }
                    if (!entity.isRenderable) {
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                TAG,
                                "drop CHANNEL_MESSAGE non-renderable id=${entity.id} channel=${entity.channelId} code=${entity.code}"
                            )
                        }
                        return@collect
                    }
                    appScope.launch { messageDao.upsert(entity) }
                    if (entity.canAdvanceServerTimeline()) {
                        synchronized(this) {
                            dialogMessage.put(entity.channelId, entity)
                            if (entity.id > lastMessageByChannel.get(entity.channelId, 0L))
                                lastMessageByChannel.put(entity.channelId, entity.id)
                        }
                    }
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            TAG,
                            "post didReceiveNewMessages id=${entity.id} channel=${entity.channelId} " +
                                "code=${entity.code} isMe=${entity.isMe}"
                        )
                    }
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.didReceiveNewMessages, entity.channelId, entity
                    )
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_NEW_MESSAGE
                    )
                    if (entity.code == MessageEntity.CODE_MESSAGE_BUZZ && !entity.isMe) {
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.buzzMessageReceived, entity.channelId
                        )
                        dialogsController.setBuzzState(entity.channelId)
                    }
                }
            }

            dialogsController.updateOnNewMessage(msg, currentUserId)
        }
    }

    private fun debugMessagePreview(content: String): String {
        val compact = content.replace('\n', ' ').replace('\r', ' ')
        return if (compact.length > 160) compact.take(160) + "..." else compact
    }

    private suspend fun observeIncomingMessageUpdates() {
        socketEventDispatcher.channelMessageUpdates.collect { update ->
            val mentionsBytes = if (update.mentionsCount == 0) {
                ByteString.EMPTY
            } else {
                ByteString.copyFrom(
                    MessageMentionList.newBuilder().addAllMentions(update.mentionsList).build().toByteArray()
                )
            }
            val mergedContent = mergeChannelContentMentionsAndRefs(update.content, mentionsBytes, ByteString.EMPTY)
            val updateTime = System.currentTimeMillis() / 1000L
            appScope.launch(ioDispatcher) {
                val existing = messageDao.getById(update.channelId, update.messageId) ?: return@launch
                val updated = existing.copy(
                    content = mergedContent,
                    updateTimeSeconds = updateTime,
                    hideEditted = update.hideEditted,
                    code = CODE_CHAT_UPDATE
                )
                messageDao.updateContent(
                    updated.channelId,
                    updated.id,
                    updated.content,
                    updated.updateTimeSeconds,
                    updated.hideEditted,
                    updated.code
                )
                synchronized(this@ChatController) {
                    val last = dialogMessage.get(updated.channelId)
                    if (last != null && last.id == updated.id) {
                        dialogMessage.put(updated.channelId, updated)
                    }
                }
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.messageDidUpdate, updated.channelId, updated,
                    NotificationCenter.UPDATE_MASK_MESSAGE_TEXT
                )
            }
        }
    }

    private fun resolveEphemeralSender(msg: ChannelMessage, currentUserId: Long): ChannelMessage {
        if (msg.senderId != 0L) return msg
        if (msg.code != MessageEntity.CODE_EPHEMERAL && msg.code != MessageEntity.CODE_UPDATE_EPHEMERAL) return msg
        val others = dialogsController.getParticipants(msg.channelId).filter { it.userId != currentUserId }
        if (others.size == 1) {
            return ChannelMessage.newBuilder(msg).setSenderId(others[0].userId).build()
        }
        if (msg.mode == STREAM_MODE_DM) {
            val other = dialogsController.getDialog(msg.channelId)?.otherUserId ?: 0L
            if (other != 0L && other != currentUserId) {
                return ChannelMessage.newBuilder(msg).setSenderId(other).build()
            }
        }
        return msg
    }

    private suspend fun observeReactionEvents() {
        socketEventDispatcher.messageReactions.collect { reaction ->
            handleReactionEvent(reaction)
        }
    }

    private data class ReactionDedup(
        val channelId: Long,
        val messageId: Long,
        val emojiId: Long,
        val senderId: Long,
        val remove: Boolean
    )

    private val pendingApiReactions = ConcurrentHashMap<ReactionDedup, Long>()

    @Volatile
    private var lastApiReactionDedup: Pair<ReactionDedup, Long>? = null

    private fun prunePendingApiReactions() {
        val now = System.currentTimeMillis()
        val it = pendingApiReactions.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value > PENDING_API_REACTION_DEDUP_MS) it.remove()
        }
    }

    private fun registerPendingApiReaction(key: ReactionDedup) {
        prunePendingApiReactions()
        pendingApiReactions[key] = System.currentTimeMillis()
    }

    private fun clearPendingApiReaction(key: ReactionDedup) {
        pendingApiReactions.remove(key)
    }

    private fun shouldIgnoreSocketReactionAsDuplicate(reaction: com.mezon.mezon.api.MessageReaction): Boolean {
        prunePendingApiReactions()
        val key = ReactionDedup(
            reaction.channelId,
            reaction.messageId,
            reaction.emojiId,
            reaction.senderId,
            reaction.action
        )
        val pendingAt = pendingApiReactions[key] ?: 0L
        if (pendingAt != 0L && System.currentTimeMillis() - pendingAt <= PENDING_API_REACTION_DEDUP_MS) {
            return true
        }
        val now = System.currentTimeMillis()
        val last = lastApiReactionDedup ?: return false
        val (k, t) = last
        if (now - t > 4000L) return false
        return k.channelId == reaction.channelId &&
            k.messageId == reaction.messageId &&
            k.emojiId == reaction.emojiId &&
            k.senderId == reaction.senderId &&
            k.remove == reaction.action
    }

    private fun handleReactionEvent(reaction: com.mezon.mezon.api.MessageReaction) {
        if (shouldIgnoreSocketReactionAsDuplicate(reaction)) return
        publishReactionUiAndPersist(
            reaction.channelId,
            reaction.messageId,
            reaction.emojiId,
            reaction.emoji,
            reaction.senderId,
            reaction.count,
            reaction.action,
            source = "socket"
        )
    }

    private fun publishReactionUiAndPersist(
        channelId: Long,
        messageId: Long,
        emojiId: Long,
        emoji: String,
        senderId: Long,
        count: Int,
        actionRemove: Boolean,
        source: String
    ) {
        if (channelId == 0L || messageId == 0L) return

        if (source == "api") {
            lastApiReactionDedup = Pair(
                ReactionDedup(channelId, messageId, emojiId, senderId, actionRemove),
                System.currentTimeMillis()
            )
        }

        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.reactionDidUpdate,
            channelId,
            messageId,
            emojiId,
            emoji,
            senderId,
            count,
            actionRemove
        )

        appScope.launch(ioDispatcher) {
            val existing = messageDao.getById(channelId, messageId) ?: return@launch
            val updatedJson = applyReactionEvent(
                existing.reactionsJson,
                emojiId,
                emoji,
                senderId,
                count,
                actionRemove
            )
            messageDao.updateReactions(channelId, messageId, updatedJson)
        }
    }

    fun sendReaction(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        isChannelPrivate: Boolean,
        messageId: Long,
        emojiId: Long,
        emoji: String,
        count: Int,
        actionDelete: Boolean,
        messageSenderId: Long
    ) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val uc = userController.get()
        val selfIdForDedup = uc.userId
        val pendingKey = if (selfIdForDedup != 0L) {
            ReactionDedup(channelId, messageId, emojiId, selfIdForDedup, actionDelete)
        } else null
        pendingKey?.let { registerPendingApiReaction(it) }
        val anon = isAnonymousSend(clanId)
        val (reactionSenderName, _) = optimisticSenderPresentation(uc, clanId, channelType, anon)
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    api.channelMessageReact(
                        session.apiUrl,
                        session.token,
                        clanId = clanId,
                        channelId = channelId,
                        mode = mode,
                        isPublic = isPublic,
                        messageId = messageId,
                        emojiId = emojiId,
                        emoji = emoji,
                        count = count,
                        messageSenderId = messageSenderId,
                        actionDelete = actionDelete,
                        topicId = 0L,
                        emojiRecentId = 0L,
                        senderName = reactionSenderName
                    )
                    val selfId = session.userId.toLongOrNull() ?: 0L
                    if (selfId != 0L) {
                        publishReactionUiAndPersist(
                            channelId,
                            messageId,
                            emojiId,
                            emoji,
                            selfId,
                            count,
                            actionDelete,
                            source = "api"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send reaction", e)
            } finally {
                pendingKey?.let { clearPendingApiReaction(it) }
            }
        }
    }

    suspend fun sendThreadSeedMessage(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        isChannelPrivate: Boolean,
        seed: MessageEntity
    ): Boolean {
        val wire = seed.content
        val attProtos = attachmentsFromEntity(seed)
        val hasText = parseContentText(wire).trim().isNotEmpty() ||
            isEmbedOrComponentsPayload(wire) ||
            wire.contains("\"lk\"")
        if (!hasText && attProtos.isEmpty()) return true
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val mentionsProto = mentionsFromForwardContent(wire).takeUnless { it.isEmpty() }
        val mentionsData = messageMentionsToData(mentionsProto)
        return try {
            sessionManager.withAutoRefresh { session ->
                ensureActiveArchivedThreadIfNeeded(session.apiUrl, session.token, channelId, clanId, channelType)
                ensureMentionedUsersInThread(
                    session.apiUrl,
                    session.token,
                    channelId,
                    clanId,
                    channelType,
                    mentionsData
                )
                val request = channelMessageSend {
                    this.clanId = clanId
                    this.channelId = channelId
                    this.mode = mode
                    this.isPublic = isPublic
                    this.content = wire
                    this.code = seed.code
                    mentionsProto?.let { if (it.isNotEmpty()) mentions.addAll(it) }
                    if (attProtos.isNotEmpty()) attachments.addAll(attProtos)
                    mentionEveryone = extractMentionEveryoneFromForwardContent(wire)
                }
                channelSend(session.apiUrl, session.token, request)
                markForwardTargetUsed(channelId, channelType)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendThreadSeedMessage failed channel=$channelId msg=${seed.id}", e)
            false
        }
    }

    fun forwardMessages(
        sourceChannelId: Long,
        messages: List<MessageEntity>,
        destinations: List<ForwardDestination>,
        extraTextTrimmed: String,
        onComplete: (ok: Boolean) -> Unit
    ) {
        if (messages.isEmpty() || destinations.isEmpty()) {
            appScope.launch(Dispatchers.Main) { onComplete(false) }
            return
        }
        val extraLimited = extraTextTrimmed.trim().take(MAX_FORWARD_COMMENT_CHARS)
        appScope.launch(ioDispatcher) {
            var allOk = true
            try {
                sessionManager.withAutoRefresh { session ->
                    for (dest in destinations) {
                        val mode = channelTypeToStreamMode(dest.channelType)
                        val isPublic = when (dest.channelType) {
                            CHANNEL_TYPE_CHANNEL, CHANNEL_TYPE_THREAD -> !dest.isChannelPrivate
                            else -> false
                        }
                        val anon = isAnonymousSend(dest.clanId)
                        ensureActiveArchivedThreadIfNeeded(
                            session.apiUrl,
                            session.token,
                            dest.channelId,
                            dest.clanId,
                            dest.channelType
                        )
                        var sentToDestination = false
                        for (msg in messages) {
                            val wire = mergeFwdIntoContent(msg.content)
                            val mentionsProto: List<MessageMention>? =
                                if (dest.channelId == sourceChannelId)
                                    mentionsFromForwardContent(wire).takeUnless { it.isEmpty() }
                                else
                                    null
                            val mentionsData = messageMentionsToData(mentionsProto)
                            ensureMentionedUsersInThread(
                                session.apiUrl,
                                session.token,
                                dest.channelId,
                                dest.clanId,
                                dest.channelType,
                                mentionsData
                            )
                            val attProtos = attachmentsFromEntity(msg)
                            try {
                                if (BuildConfig.DEBUG) {
                                    Log.d(
                                        TAG,
                                        "forward try ch=${dest.channelId} clan=${dest.clanId} srcCh=$sourceChannelId " +
                                            "msgId=${msg.id} msgCode=${msg.code} dstType=${dest.channelType} mode=$mode " +
                                            "isPublic=$isPublic anon=$anon att=${attProtos.size} " +
                                            "mentionEv=${
                                                extractMentionEveryoneFromForwardContent(wire)
                                            } contentHead=${wire.take(240)}"
                                    )
                                }
                                val request = channelMessageSend {
                                    clanId = dest.clanId
                                    channelId = dest.channelId
                                    this.mode = mode
                                    this.isPublic = isPublic
                                    content = wire
                                    this.code = msg.code
                                    mentionsProto?.let { if (it.isNotEmpty()) mentions.addAll(it) }
                                    if (attProtos.isNotEmpty()) attachments.addAll(attProtos)
                                    mentionEveryone = extractMentionEveryoneFromForwardContent(wire)
                                    if (anon) anonymousMessage = true
                                }
                                channelSend(session.apiUrl, session.token, request)
                                sentToDestination = true
                            } catch (e: Exception) {
                                allOk = false
                                val everyone = extractMentionEveryoneFromForwardContent(wire)
                                Log.e(
                                    TAG,
                                    "forward REST chunk failed ch=${dest.channelId} msg=${msg.id} msgCode=${msg.code} " +
                                        "srcCh=$sourceChannelId clan=${dest.clanId} chType=${dest.channelType} mode=$mode " +
                                        "isPublic=$isPublic anon=$anon att=${attProtos.size} mentions=${mentionsProto?.size ?: 0} " +
                                        "mentionEveryone=$everyone contentLen=${wire.length} contentHead=${wire.take(500)}",
                                    e
                                )
                            }
                        }
                        if (extraLimited.isNotEmpty()) {
                            val txt = buildTextContent(extraLimited)
                            try {
                                val requestExtra = channelMessageSend {
                                    clanId = dest.clanId
                                    channelId = dest.channelId
                                    this.mode = mode
                                    this.isPublic = isPublic
                                    content = txt
                                    if (anon) anonymousMessage = true
                                }
                                channelSend(session.apiUrl, session.token, requestExtra)
                                sentToDestination = true
                            } catch (e: Exception) {
                                allOk = false
                                Log.e(TAG, "forward REST extra comment failed channel=${dest.channelId}", e)
                            }
                        }
                        if (sentToDestination) {
                            markForwardTargetUsed(dest.channelId, dest.channelType)
                        }
                    }
                }
            } catch (e: Exception) {
                allOk = false
                Log.e(TAG, "forwardMessages failed", e)
            }
            withContext(Dispatchers.Main) { onComplete(allOk) }
        }
    }

    private fun mergeFwdIntoContent(raw: String): String {
        return try {
            val o = JSONObject(raw)
            o.put("fwd", true)
            o.toString()
        } catch (_: Exception) {
            "{\"t\":\"\",\"fwd\":true}"
        }
    }

    private fun messageMentionsToData(proto: List<MessageMention>?): List<MentionData>? {
        if (proto.isNullOrEmpty()) return null
        return proto.map {
            MentionData(
                userId = if (it.userId != 0L) it.userId.toString() else "",
                roleId = if (it.roleId != 0L) it.roleId.toString() else "",
                display = it.username,
                startOffset = it.s,
                endOffset = it.e
            )
        }
    }

    private fun extractMentionEveryoneFromForwardContent(content: String): Boolean {
        return try {
            val o = JSONObject(content)
            val arr = o.optJSONArray("mentions") ?: return false
            for (i in 0 until arr.length()) {
                val uid = arr.getJSONObject(i).optString("user_id", "")
                if (uid == "here" || uid == MENTION_HERE_USER_ID) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun mentionsFromForwardContent(content: String): List<MessageMention> {
        return try {
            val o = JSONObject(content)
            val arr = o.optJSONArray("mentions") ?: return emptyList()
            val list = ArrayList<MessageMention>(arr.length())
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                list.add(messageMention {
                    s = m.optInt("s")
                    e = m.optInt("e")
                    val uidStr = m.optString("user_id", "")
                    if (uidStr.isNotEmpty() && uidStr != "here") {
                        uidStr.toLongOrNull()?.let { userId = it }
                    }
                    val rid = m.optLong("role_id", 0L)
                    if (rid != 0L) roleId = rid
                    val un = m.optString("username", "")
                    if (un.isNotEmpty()) username = un
                })
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun attachmentsFromEntity(msg: MessageEntity): List<MessageAttachment> {
        val ais = flattenedAttachmentInfos(msg)
        val out = ArrayList<MessageAttachment>(ais.size)
        for (ai in ais) {
            if (ai.url.isEmpty()) continue
            out.add(messageAttachment {
                url = ai.url
                filename = ai.filename
                filetype = ai.filetype
                size = ai.size
                width = ai.width
                height = ai.height
                if (ai.thumb.isNotEmpty()) thumbnail = ai.thumb
                if (ai.duration != 0) duration = ai.duration
            })
        }
        return out
    }

    private fun flattenedAttachmentInfos(msg: MessageEntity): List<AttachmentInfo> {
        val parts = ArrayList<AttachmentInfo>()
        if (msg.attachmentUrl.isNotEmpty()) {
            parts.add(
                AttachmentInfo(
                    msg.attachmentUrl, msg.attachmentThumb, msg.attachmentWidth, msg.attachmentHeight,
                    msg.attachmentFilename, msg.attachmentFiletype, msg.attachmentSize, msg.attachmentDuration
                )
            )
        }
        parts.addAll(msg.extraAttachments)
        return parts
    }
}
