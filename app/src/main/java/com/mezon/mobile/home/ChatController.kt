package com.mezon.mobile.home

import android.util.LongSparseArray
import android.util.Log
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.data.db.MessageDao
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.chat.AttachmentPickerItem
import com.mezon.mobile.home.chat.MessageEntity
import com.mezon.mobile.home.chat.applyReactionEvent
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
import com.mezon.mobile.network.STREAM_MODE_THREAD
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.home.clans.CHANNEL_TYPE_VOICE
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.util.MENTION_HERE_USER_ID
import com.mezon.mobile.util.MentionData
import com.mezon.mobile.util.buildTextContent
import com.mezon.mobile.util.EmojiMarker
import com.mezon.mobile.util.MarkdownMarker
import com.mezon.mobile.util.buildTextContentWithEmojis
import com.mezon.mobile.util.mergePendingMentionsIntoContent
import com.mezon.mezon.api.MessageAttachment
import com.mezon.mezon.api.messageAttachment
import com.mezon.mezon.api.messageMention
import com.mezon.mezon.rtapi.channelMessageSend
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val badgeCoordinator: BadgeCoordinator,
    private val channelController: dagger.Lazy<com.mezon.mobile.home.clans.ChannelController>,
    private val userController: dagger.Lazy<UserController>,
    private val anonymousController: dagger.Lazy<AnonymousController>,
    private val userClanController: dagger.Lazy<UserClanController>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    val dialogMessage = LongSparseArray<MessageEntity>()
    private val initialFetchDone = HashSet<Long>()
    private val lastMessageByChannel = LongSparseArray<Long>()

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
        appScope.launch { observeReactionEvents() }
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

    fun loadMessages(channelId: Long, clanId: Long, forceRefresh: Boolean = false) {
        appScope.launch(ioDispatcher) {
            try {
                val cacheKey = apiCacheKey("fetchMessages", clanId, channelId)
                if (forceRefresh) cacheTracker.invalidate(cacheKey)
                val isOnlineNow = networkMonitor.isOnline.value

                if (!isOnlineNow) {
                    val fromDb = messageDao.getLatestByChannel(channelId, PAGE_SIZE)
                    if (fromDb.isNotEmpty()) {
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.messagesDidLoad, channelId, ArrayList(fromDb), true, false, true
                        )
                    }
                    return@launch
                }

                if (!forceRefresh && cacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP) {
                    val fromDb = messageDao.getLatestByChannel(channelId, PAGE_SIZE)
                    if (fromDb.isNotEmpty()) {
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.messagesDidLoad, channelId, ArrayList(fromDb), true, false, true
                        )
                    }
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

                    synchronized(this@ChatController) { initialFetchDone.add(channelId) }
                    val serverLastSentId = if (response.hasLastSentMessage()) response.lastSentMessage.id else 0L
                    updateLastMessageByChannel(channelId, messages, serverLastSentId)
                    val serverLastSeenId = if (response.hasLastSeenMessage()) response.lastSeenMessage.id else 0L
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesDidLoad, channelId, ArrayList(messages), hasMoreTop, false, false, serverLastSeenId
                    )
                    cacheTracker.markCalled(cacheKey)
                    launch { messageDao.upsertAll(messages); messageDao.trimToLatest(channelId, PAGE_SIZE * 4) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadMessages failed for channel $channelId", e)
                val fromDb = messageDao.getLatestByChannel(channelId, PAGE_SIZE)
                if (fromDb.isNotEmpty()) {
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesDidLoad, channelId, ArrayList(fromDb), true, false, true
                    )
                } else {
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.messagesLoadError, channelId, e.message ?: "Failed to load"
                    )
                }
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
        badgeCoordinator.scheduleLastSeenWrite(
            channelId, clanId, channelType, messageId, timestampSeconds, badgeCount
        )
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
        markdownMarkers: List<MarkdownMarker>? = null
    ) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val hasContentExtras = !emojiMarkers.isNullOrEmpty() || !markdownMarkers.isNullOrEmpty()
        val content = if (!hasContentExtras) buildTextContent(text)
            else buildTextContentWithEmojis(text, null, emojiMarkers, markdownMarkers)
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

        val tempId = generateTempId()
        val uc = userController.get()
        val anon = isAnonymousSend(clanId)
        val (optName, optAvatar) = optimisticSenderPresentation(uc, clanId, channelType, anon)
        val optimisticContent = mergePendingMentionsIntoContent(
            mergeRefsIntoOptimisticContent(content, references),
            mentions
        )
        val optimistic = MessageEntity(
            id = tempId,
            channelId = channelId,
            senderId = if (anon) ANONYMOUS_USER_ID else uc.userId,
            senderName = optName,
            senderAvatar = optAvatar,
            content = optimisticContent,
            timestampSeconds = System.currentTimeMillis() / 1000,
            code = MessageEntity.CODE_CHAT,
            isMe = true,
            sendState = MessageEntity.SEND_STATE_SENDING
        )
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.didReceiveNewMessages, channelId, optimistic
        )

        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
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
                    }
                    val ack = withContext(ioDispatcher) {
                        api.sendChannelMessage(session.apiUrl, session.token, request)
                    }
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.pendingMessageSent, channelId, tempId, ack.messageId
                    )
                }
            } catch (e: Exception) {
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.pendingMessageError, channelId, tempId
                )
            }
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
        references: List<com.mezon.mezon.api.MessageRef>? = null
    ) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val baseContent = "{\"t\":\"\"}"
        val content = mergeRefsIntoOptimisticContent(baseContent, references)
        val attachment = messageAttachment {
            this.url = url
            this.filetype = filetype
            if (filename != null) this.filename = filename
        }

        val tempId = generateTempId()
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
            channelId = channelId,
            senderId = if (anon) ANONYMOUS_USER_ID else uc.userId,
            senderName = optName,
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
            NotificationCenter.didReceiveNewMessages, channelId, optimistic
        )

        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    val request = channelMessageSend {
                        this.clanId = clanId
                        this.channelId = channelId
                        this.mode = mode
                        this.isPublic = isPublic
                        this.content = baseContent
                        this.attachments.add(attachment)
                        references?.let { this.references.addAll(it) }
                        if (anon) this.anonymousMessage = true
                    }
                    val ack = withContext(ioDispatcher) {
                        api.sendChannelMessage(session.apiUrl, session.token, request)
                    }
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.pendingMessageSent, channelId, tempId, ack.messageId
                    )
                    Log.d(TAG, "Direct attachment sent: channelId=$channelId url=${url.take(60)}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send direct attachment", e)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.pendingMessageError, channelId, tempId
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

        val tempId = generateTempId()
        val uc = userController.get()
        val anon = isAnonymousSend(clanId)
        val (optName, optAvatar) = optimisticSenderPresentation(uc, clanId, channelType, anon)
        val optimistic = MessageEntity(
            id = tempId,
            channelId = channelId,
            senderId = if (anon) ANONYMOUS_USER_ID else uc.userId,
            senderName = optName,
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
                    val request = channelMessageSend {
                        this.clanId = clanId
                        this.channelId = channelId
                        this.mode = mode
                        this.isPublic = isPublic
                        this.content = content
                        this.code = MessageEntity.CODE_LOCATION
                        if (anon) this.anonymousMessage = true
                    }
                    val ack = withContext(ioDispatcher) {
                        api.sendChannelMessage(session.apiUrl, session.token, request)
                    }
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

        val tempId = generateTempId()
        val uc = userController.get()
        val anon = isAnonymousSend(clanId)
        val (optName, optAvatar) = optimisticSenderPresentation(uc, clanId, channelType, anon)
        val optimistic = MessageEntity(
            id = tempId,
            channelId = channelId,
            senderId = if (anon) ANONYMOUS_USER_ID else uc.userId,
            senderName = optName,
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
                    val request = channelMessageSend {
                        this.clanId = clanId
                        this.channelId = channelId
                        this.mode = mode
                        this.isPublic = isPublic
                        this.content = content
                        this.code = MessageEntity.CODE_MESSAGE_BUZZ
                        if (anon) this.anonymousMessage = true
                    }
                    val ack = withContext(ioDispatcher) {
                        api.sendChannelMessage(session.apiUrl, session.token, request)
                    }
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
    }

    private fun generateTempId(): Long {
        val ts = System.currentTimeMillis()
        return (ts shl 22) or (Thread.currentThread().id and 0x3FFFFF)
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
                item.put("mesages_sender_avatar", ref.messageSenderAvatar)
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
        mentions: List<MentionData>? = null
    ) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val wireBase = if (text.isNotBlank()) buildTextContent(text) else "{\"t\":\"\"}"
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

        val tempId = generateTempId()
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
            NotificationCenter.didReceiveNewMessages, channelId, optimistic
        )

        appScope.launch(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
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
                        }
                        val ack = api.sendChannelMessage(session.apiUrl, session.token, request)
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.pendingMessageSent, channelId, tempId, ack.messageId
                        )
                    } else {
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.pendingMessageError, channelId, tempId
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message with attachments", e)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.pendingMessageError, channelId, tempId
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
        contentResolver: android.content.ContentResolver
    ) {
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val baseContent = if (text.isNotBlank()) buildTextContent(text) else "{\"t\":\"\"}"

        val tempId = generateTempId()
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
                if (clanId != 0L) {
                    runCatching {
                        sessionManager.sessionFlow.first() ?: return@launch
                        if (mezonSocket.awaitConnected()) {
                            mezonSocket.joinClanChat(clanId)
                            mezonSocket.joinChat(clanId, channelId, channelType, isPublic)
                        }
                    }
                }

                sessionManager.withAutoRefresh { session ->
                    val cdnBaseUrl = BuildConfig.MEZON_BASE_IMG_URL
                    val uploadedAttachments = ArrayList<MessageAttachment>()

                    for (item in attachments) {
                        var uploaded = false
                        for (attempt in 1..SHARE_MAX_RETRIES) {
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
                                    Log.e(TAG, "shareMedia: Failed to read file: ${item.filename}")
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
                        val ack = api.sendChannelMessage(session.apiUrl, session.token, request)
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

    private suspend fun observeReactionEvents() {
        socketEventDispatcher.messageReactions.collect { reaction ->
            handleReactionEvent(reaction)
        }
    }

    private fun handleReactionEvent(reaction: com.mezon.mezon.api.MessageReaction) {
        val channelId = reaction.channelId
        val messageId = reaction.messageId
        if (channelId == 0L || messageId == 0L) return

        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.reactionDidUpdate,
            channelId,
            messageId,
            reaction.emojiId,
            reaction.emoji,
            reaction.senderId,
            reaction.count,
            reaction.action
        )

        appScope.launch(ioDispatcher) {
            val existing = messageDao.getById(channelId, messageId) ?: return@launch
            val updatedJson = applyReactionEvent(
                existing.reactionsJson,
                reaction.emojiId,
                reaction.emoji,
                reaction.senderId,
                reaction.count,
                reaction.action
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
        val anon = isAnonymousSend(clanId)
        val (reactionSenderName, _) = optimisticSenderPresentation(uc, clanId, channelType, anon)
        appScope.launch {
            try {
                mezonSocket.writeMessageReaction(
                    id = emojiId,
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
                    senderName = reactionSenderName
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send reaction", e)
            }
        }
    }
}
