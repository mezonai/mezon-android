package com.mezon.mobile.home

import android.content.Context
import android.util.LongSparseArray
import android.util.Log
import com.mezon.mezon.api.AllUsersAddChannelResponse
import com.mezon.mezon.api.ChannelMessage
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.data.db.DirectMessageDao
import com.mezon.mobile.data.db.MessageDao
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.chat.MessageEntity
import com.mezon.mobile.home.messages.DirectMessage
import com.mezon.mobile.home.messages.DmParticipant
import com.mezon.mobile.home.messages.extractParticipants
import com.mezon.mobile.home.messages.toDirectMessage
import com.mezon.mobile.home.messages.toDirectMessageFromIncoming
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.sanitizeServerMessageId as sanitizeProvisionalId
import com.mezon.mobile.network.CODE_CHAT_REMOVE
import com.mezon.mobile.network.CODE_CHAT_UPDATE
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.NetworkMonitor
import com.mezon.mobile.network.STREAM_MODE_DM
import com.mezon.mobile.network.STREAM_MODE_GROUP
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.notification.ActiveChannelTracker
import com.mezon.mobile.notification.NotificationHelper
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.session.StoredSession
import com.mezon.mobile.util.parseContentPreview
import com.mezon.mobile.home.call.messagePreviewForDialog
import dagger.Lazy
import com.mezon.mezon.api.ChannelDescription
import com.mezon.mezon.rtapi.LastSeenMessageEvent
import com.mezon.mezon.rtapi.UserChannelAdded
import com.mezon.mezon.rtapi.UserChannelRemoved
import com.mezon.mezon.rtapi.UserProfileRedis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

private const val TAG = "DialogsController"

@Singleton
class DialogsController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val api: MezonApi,
    private val directMessageDao: DirectMessageDao,
    private val messageDao: MessageDao,
    private val socketEventDispatcher: SocketEventDispatcher,
    private val sessionManager: SessionManager,
    private val notificationCenter: NotificationCenter,
    private val networkMonitor: NetworkMonitor,
    private val cacheTracker: ApiCacheTracker,
    private val activeChannelTracker: ActiveChannelTracker,
    private val notificationHelper: NotificationHelper,
    private val badgeCoordinator: Lazy<BadgeCoordinator>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    val dialogs = ArrayList<DirectMessage>()
    val dialogsDict = LongSparseArray<DirectMessage>()
    private val participantsByChannel = LongSparseArray<List<DmParticipant>>()

    var dialogsLoaded = false
        private set

    @Volatile
    private var currentChannelId: Long? = null

    private val buzzStates = HashMap<Long, Long>()
    private val buzzHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun setBuzzState(channelId: Long) {
        synchronized(this) { buzzStates[channelId] = System.currentTimeMillis() }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
        buzzHandler.postDelayed({
            synchronized(this) { buzzStates.remove(channelId) }
            notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
        }, 10_000)
    }

    fun isBuzzActive(channelId: Long): Boolean {
        return synchronized(this) { buzzStates.containsKey(channelId) }
    }

    init {
        appScope.launch { loadDialogsFromDb() }
        appScope.launch { observeMarkAsRead() }
        appScope.launch { observeLastSeenMessages() }
        appScope.launch { observeUserChannelAdded() }
        appScope.launch { observeUserChannelRemoved() }
    }

    fun cleanup() {
        synchronized(this) {
            dialogs.clear()
            dialogsDict.clear()
            participantsByChannel.clear()
            buzzStates.clear()
            dialogsLoaded = false
            currentChannelId = null
        }
        buzzHandler.removeCallbacksAndMessages(null)
    }

    @Synchronized
    fun getDialogs(): List<DirectMessage> = ArrayList(dialogs)

    @Synchronized
    fun getDialog(channelId: Long): DirectMessage? = dialogsDict[channelId]

    @Synchronized
    fun getParticipants(channelId: Long): List<DmParticipant> =
        participantsByChannel[channelId] ?: emptyList()

    private fun UserProfileRedis.toDmParticipant(): DmParticipant = DmParticipant(
        userId = userId,
        username = username,
        displayName = displayName.ifBlank { username },
        avatarUrl = avatar
    )

    private fun mergeParticipants(
        channelId: Long,
        incoming: List<DmParticipant>
    ) {
        if (incoming.isEmpty()) return
        val current = participantsByChannel[channelId]
        if (current.isNullOrEmpty()) {
            participantsByChannel.put(channelId, incoming)
            return
        }
        val mergedById = LinkedHashMap<Long, DmParticipant>(current.size + incoming.size)
        for (p in current) mergedById[p.userId] = p
        for (p in incoming) mergedById[p.userId] = p
        participantsByChannel.put(channelId, ArrayList(mergedById.values))
    }

    private fun removeParticipants(
        channelId: Long,
        userIds: Set<Long>
    ): Boolean {
        if (userIds.isEmpty()) return false
        val current = participantsByChannel[channelId] ?: return false
        val updated = current.filter { it.userId !in userIds }
        if (updated.size == current.size) return false
        participantsByChannel.put(channelId, updated)
        return true
    }

    private fun applyDmPeerIdentity(
        row: DirectMessage,
        currentUserId: Long,
        users: List<UserProfileRedis>,
        participants: List<DmParticipant>
    ): DirectMessage {
        if (row.type != CHANNEL_TYPE_DM) return row
        if (row.otherUserId != 0L && row.otherUserId != currentUserId) return row
        val peer = users.firstOrNull { it.userId != 0L && it.userId != currentUserId }?.toDmParticipant()
            ?: participants.firstOrNull { it.userId != 0L && it.userId != currentUserId }
            ?: return row
        val name = peer.displayName.ifBlank { peer.username }
        if (name.isBlank()) return row
        return row.copy(
            label = name,
            displayName = name,
            username = peer.username.ifBlank { name },
            avatarUrl = peer.avatarUrl.ifBlank { row.avatarUrl },
            otherUserId = peer.userId
        )
    }

    private fun groupNameFallbackFromUsers(
        users: List<UserProfileRedis>
    ): String {
        if (users.isEmpty()) return ""
        val names = users
            .map { it.displayName.ifBlank { it.username }.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return formatGroupNameFallback(names)
    }

    private fun groupNameFallbackFromParticipants(
        participants: List<DmParticipant>
    ): String {
        if (participants.isEmpty()) return ""
        val names = participants
            .map { it.displayName.ifBlank { it.username }.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return formatGroupNameFallback(names)
    }

    private fun formatGroupNameFallback(names: List<String>): String {
        return names.joinToString(",")
    }

    private fun applyGroupFallbackMetadata(
        row: DirectMessage,
        desc: ChannelDescription,
        users: List<UserProfileRedis>
    ): Pair<DirectMessage, Boolean> {
        if (desc.type != CHANNEL_TYPE_GROUP) return Pair(row, false)
        val fallbackName = desc.channelLabel.ifBlank { groupNameFallbackFromUsers(users) }
        if (fallbackName.isBlank()) return Pair(row, false)
        val incomingNames = users
            .map { it.displayName.ifBlank { it.username }.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        val shouldReplaceName = desc.channelLabel.isNotBlank() ||
            row.displayName.isBlank() ||
            row.label.isBlank() ||
            (incomingNames.size > 1 && row.displayName in incomingNames)
        var next = row
        var changed = false
        if ((shouldReplaceName || next.label.isBlank()) && next.label != fallbackName) {
            next = next.copy(label = fallbackName)
            changed = true
        }
        if ((shouldReplaceName || next.displayName.isBlank()) && next.displayName != fallbackName) {
            next = next.copy(displayName = fallbackName)
            changed = true
        }
        if ((shouldReplaceName || next.username.isBlank()) && next.username != fallbackName) {
            next = next.copy(username = fallbackName)
            changed = true
        }
        if (desc.channelAvatar.isNotBlank() && next.avatarUrl != desc.channelAvatar) {
            next = next.copy(avatarUrl = desc.channelAvatar)
            changed = true
        }
        return Pair(next, changed)
    }

    fun setCurrentChannel(channelId: Long) {
        currentChannelId = channelId
        activeChannelTracker.setActive(channelId)
        notificationHelper.cancelNotification(channelId.toInt())
        markDialogAsRead(channelId)
    }

    fun clearCurrentChannel() {
        currentChannelId = null
        activeChannelTracker.clear()
    }

    fun loadDmParticipants(channelId: Long) {
        loadDmParticipants(channelId, force = false)
    }

    fun loadDmParticipants(channelId: Long, force: Boolean) {
        if (channelId == 0L) return
        val hasCache: Boolean
        synchronized(this) { hasCache = participantsByChannel[channelId] != null }
        if (hasCache && !force) return
        appScope.launch(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
                    val response = api.listChannelUsersUC(session.apiUrl, session.token, channelId)
                    val participants = response.toDmParticipants()
                    if (participants.isEmpty()) return@withAutoRefresh
                    synchronized(this@DialogsController) {
                        participantsByChannel.put(channelId, participants)
                    }
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.channelMembersDidLoad, channelId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadDmParticipants failed for channel $channelId", e)
            }
        }
    }

    suspend fun createGroup(userIds: List<Long>, participantHints: List<DmParticipant>): DirectMessage? {
        val ids = userIds.filter { it != 0L }.distinct()
        if (ids.size < 2) return null
        return try {
            sessionManager.withAutoRefresh { session ->
                val currentUserId = session.userId.toLongOrNull() ?: 0L
                val response = api.createChannelDesc(
                    apiUrl = session.apiUrl,
                    token = session.token,
                    type = CHANNEL_TYPE_GROUP,
                    userIds = ids
                )
                cacheTracker.invalidateByPrefix("listChannelDescs_")
                ingestChannelDescForCreatedGroup(response, currentUserId, participantHints)
            }
        } catch (e: Exception) {
            Log.e(TAG, "createGroup failed userIds=${ids.joinToString(",")}", e)
            null
        }
    }

    suspend fun addMembersToGroup(
        channelId: Long,
        userIds: List<Long>,
        participantHints: List<DmParticipant>
    ): Boolean {
        val ids = userIds.filter { it != 0L }.distinct()
        if (channelId == 0L || ids.isEmpty()) return false
        return try {
            sessionManager.withAutoRefresh { session ->
                api.addChannelUsers(session.apiUrl, session.token, channelId, ids)
                val participants = runCatching {
                    api.listChannelUsersUC(session.apiUrl, session.token, channelId).toDmParticipants()
                }.getOrElse { participantHints }
                synchronized(this@DialogsController) {
                    if (participants.isNotEmpty()) {
                        participantsByChannel.put(channelId, participants)
                    } else if (participantHints.isNotEmpty()) {
                        mergeParticipants(channelId, participantHints)
                    }
                }
            }
            cacheTracker.invalidateByPrefix("listChannelDescs_")
            notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelMembersDidLoad, channelId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "addMembersToGroup failed channelId=$channelId users=${ids.joinToString(",")}", e)
            false
        }
    }

    suspend fun getOrCreateDm(userId: Long): Long {
        synchronized(this) {
            for (i in 0 until dialogsDict.size()) {
                val dm = dialogsDict.valueAt(i)
                if (dm.type == CHANNEL_TYPE_DM && dm.otherUserId == userId) {
                    return dm.channelId
                }
            }
        }
        return try {
            sessionManager.withAutoRefresh { session ->
                val currentUserId = session.userId.toLongOrNull() ?: 0L
                val response = api.createChannelDesc(
                    apiUrl = session.apiUrl,
                    token = session.token,
                    type = CHANNEL_TYPE_DM,
                    userIds = listOf(userId)
                )
                ingestChannelDescForCreatedDm(response, currentUserId)
                response.channelId
            }
        } catch (e: Exception) {
            Log.e(TAG, "getOrCreateDm failed for userId=$userId", e)
            0L
        }
    }

    private fun ingestChannelDescForCreatedDm(desc: ChannelDescription, currentUserId: Long) {
        val participants = desc.extractParticipants()
        val fresh = desc.toDirectMessage(currentUserId, appContext)
        val toPersist: DirectMessage
        synchronized(this) {
            if (participants.isNotEmpty()) {
                participantsByChannel.put(desc.channelId, participants)
            }
            val existing = dialogsDict[fresh.channelId]
            val merged = if (existing != null) {
                existing.copy(
                    type = fresh.type.takeIf { it != 0 } ?: existing.type,
                    label = fresh.label.ifBlank { existing.label },
                    avatarUrl = fresh.avatarUrl.ifBlank { existing.avatarUrl },
                    displayName = fresh.displayName.ifBlank { existing.displayName },
                    lastMessageContent = fresh.lastMessageContent.ifBlank { existing.lastMessageContent },
                    isOnline = false,
                    otherUserId = if (fresh.otherUserId != 0L) fresh.otherUserId else existing.otherUserId,
                    lastSeenMessageId = maxOf(existing.lastSeenMessageId, fresh.lastSeenMessageId),
                    lastSentMessageId = maxOf(existing.lastSentMessageId, fresh.lastSentMessageId),
                    lastSeenMessageTs = maxOf(existing.lastSeenMessageTs, fresh.lastSeenMessageTs),
                    lastSentMessageTs = maxOf(existing.lastSentMessageTs, fresh.lastSentMessageTs),
                    unreadCount = mergeDmUnreadFromList(existing.unreadCount, fresh),
                    isMute = existing.isMute
                )
            } else {
                fresh
            }
            dialogsDict.put(merged.channelId, merged)
            toPersist = merged
            val oldIdx = dialogs.indexOfFirst { it.channelId == merged.channelId }
            if (oldIdx >= 0) dialogs.removeAt(oldIdx)
            var lo = 0
            var hi = dialogs.size
            val target = merged.lastSentMessageTs
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (dialogs[mid].lastSentMessageTs > target) lo = mid + 1 else hi = mid
            }
            dialogs.add(lo, merged)
        }
        appScope.launch(ioDispatcher) { directMessageDao.upsert(toPersist) }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
    }

    private fun ingestChannelDescForCreatedGroup(
        desc: ChannelDescription,
        currentUserId: Long,
        participantHints: List<DmParticipant>
    ): DirectMessage {
        val participants = mergeParticipantHints(desc.extractParticipants(), participantHints)
        val fresh = desc.toDirectMessage(currentUserId, appContext)
        val fallbackName = desc.channelLabel.ifBlank { groupNameFallbackFromParticipants(participants) }
        val createdTs = System.currentTimeMillis() / 1000L
        val row = fresh.copy(
            type = CHANNEL_TYPE_GROUP,
            label = fallbackName.ifBlank { fresh.label },
            displayName = fallbackName.ifBlank { fresh.displayName },
            username = fallbackName.ifBlank { fresh.username },
            lastSentMessageTs = if (fresh.lastSentMessageTs == 0L) createdTs else fresh.lastSentMessageTs,
            groupCreatorId = if (fresh.groupCreatorId != 0L) fresh.groupCreatorId else currentUserId
        )
        val toPersist: DirectMessage
        synchronized(this) {
            if (participants.isNotEmpty()) {
                participantsByChannel.put(desc.channelId, participants)
            }
            val existing = dialogsDict[row.channelId]
            val merged = if (existing != null) {
                existing.copy(
                    type = CHANNEL_TYPE_GROUP,
                    label = row.label.ifBlank { existing.label },
                    avatarUrl = row.avatarUrl.ifBlank { existing.avatarUrl },
                    displayName = row.displayName.ifBlank { existing.displayName },
                    username = row.username.ifBlank { existing.username },
                    lastMessageContent = row.lastMessageContent.ifBlank { existing.lastMessageContent },
                    isOnline = false,
                    lastSeenMessageId = maxOf(existing.lastSeenMessageId, row.lastSeenMessageId),
                    lastSentMessageId = maxOf(existing.lastSentMessageId, row.lastSentMessageId),
                    lastSeenMessageTs = maxOf(existing.lastSeenMessageTs, row.lastSeenMessageTs),
                    lastSentMessageTs = maxOf(existing.lastSentMessageTs, row.lastSentMessageTs),
                    unreadCount = mergeDmUnreadFromList(existing.unreadCount, row),
                    isMute = existing.isMute,
                    groupCreatorId = if (row.groupCreatorId != 0L) row.groupCreatorId else existing.groupCreatorId
                )
            } else {
                row
            }
            dialogsDict.put(merged.channelId, merged)
            val oldIdx = dialogs.indexOfFirst { it.channelId == merged.channelId }
            if (oldIdx >= 0) dialogs.removeAt(oldIdx)
            var lo = 0
            var hi = dialogs.size
            val target = merged.lastSentMessageTs
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (dialogs[mid].lastSentMessageTs > target) lo = mid + 1 else hi = mid
            }
            dialogs.add(lo, merged)
            toPersist = merged
        }
        appScope.launch(ioDispatcher) { directMessageDao.upsert(toPersist) }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.channelMembersDidLoad, desc.channelId)
        return toPersist
    }

    private fun mergeParticipantHints(
        fromDesc: List<DmParticipant>,
        hints: List<DmParticipant>
    ): List<DmParticipant> {
        if (fromDesc.isEmpty() && hints.isEmpty()) return emptyList()
        val merged = LinkedHashMap<Long, DmParticipant>(fromDesc.size + hints.size)
        for (p in fromDesc) {
            if (p.userId != 0L) merged[p.userId] = p
        }
        for (p in hints) {
            if (p.userId != 0L) merged[p.userId] = p
        }
        return ArrayList(merged.values)
    }

    private fun AllUsersAddChannelResponse.toDmParticipants(): List<DmParticipant> {
        val count = userIdsCount
        if (count == 0) return emptyList()
        val participants = ArrayList<DmParticipant>(count)
        for (i in 0 until count) {
            participants.add(DmParticipant(
                userId = getUserIds(i),
                username = usernamesList.getOrElse(i) { "" },
                displayName = displayNamesList.getOrElse(i) { "" }.ifBlank { usernamesList.getOrElse(i) { "" } },
                avatarUrl = avatarsList.getOrElse(i) { "" }
            ))
        }
        return participants
    }

    fun loadDialogs(page: Int = 1, limit: Int = 500) {
        appScope.launch(ioDispatcher) {
            try {
                if (StartupCache.suppressHomeListApiForIncomingCallWake) {
                    return@launch
                }

                val cacheKey = apiCacheKey("listChannelDescs", page)
                val hasCache: Boolean
                synchronized(this@DialogsController) { hasCache = dialogs.isNotEmpty() }

                if (!networkMonitor.isOnline.value && hasCache) {
                    if (!dialogsLoaded) dialogsLoaded = true
                    badgeCoordinator.get().processDeferredQueue()
                    return@launch
                }
                if (hasCache && cacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP) {
                    if (!dialogsLoaded) dialogsLoaded = true
                    val badgePatched = sessionManager.withAutoRefresh { session -> syncDmBadgesWithApi(session) }
                    if (badgePatched) {
                        notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
                    }
                    badgeCoordinator.get().processDeferredQueue()
                    return@launch
                }

                sessionManager.withAutoRefresh { session ->
                    val currentUserId = session.userId.toLongOrNull() ?: 0L

                    val response = api.listChannelDescs(session.apiUrl, session.token, CHANNEL_TYPE_GROUP, page, limit)

                    val rawList = response.channeldescList

                    val activeDescs = rawList.filter { it.active == 1 }
                    synchronized(this@DialogsController) {
                        for (desc in activeDescs) {
                            val participants = desc.extractParticipants()
                            if (participants.isNotEmpty()) {
                                participantsByChannel.put(desc.channelId, participants)
                            }
                        }
                    }

                    val merged = activeDescs
                        .map { it.toDirectMessage(currentUserId, appContext) }
                        .sortedByDescending { it.lastSentMessageTs }

                    putDialogs(merged)
                    cacheTracker.markCalled(cacheKey)
                    syncDmBadgesWithApi(session)
                }

                dialogsLoaded = true
                notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
                badgeCoordinator.get().processDeferredQueue()
            } catch (e: Exception) {
                Log.e(TAG, "loadDialogs failed", e)
                dialogsLoaded = true
                notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsLoadError, e.message ?: "Failed to load")
                badgeCoordinator.get().processDeferredQueue()
            }
        }
    }

    fun updateDialogLastSeen(
        channelId: Long,
        messageId: Long,
        remainingUnread: Int,
        timestampSeconds: Int = 0
    ) {
        var updated: DirectMessage? = null
        synchronized(this) {
            val dm = dialogsDict[channelId] ?: return
            if (messageId <= dm.lastSeenMessageId) return
            val newUnread = remainingUnread.coerceAtLeast(0)
            if (newUnread == dm.unreadCount && messageId == dm.lastSeenMessageId) return
            val tsLong = timestampSeconds.toLong() and 0xFFFF_FFFFL
            val syncTs = when {
                newUnread == 0 -> maxOf(dm.lastSeenMessageTs, dm.lastSentMessageTs, tsLong)
                tsLong > 0L -> maxOf(dm.lastSeenMessageTs, tsLong)
                else -> dm.lastSeenMessageTs
            }
            val u = dm.copy(
                lastSeenMessageId = messageId,
                unreadCount = newUnread,
                lastSeenMessageTs = syncTs
            )
            dialogsDict.put(channelId, u)
            val idx = dialogs.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) dialogs[idx] = u
            updated = u
        }
        updated?.let { appScope.launch(ioDispatcher) { directMessageDao.upsert(it) } }
    }

    fun markDialogAsRead(channelId: Long, postEvent: Boolean = true, seenTimestampSeconds: Int = 0, seenMessageId: Long = 0L) {
        var changed = false
        var newSeenId = 0L
        var updated: DirectMessage? = null
        synchronized(this) {
            val dm = dialogsDict[channelId] ?: return
            if (dm.unreadCount == 0 && seenMessageId <= dm.lastSeenMessageId) return
            newSeenId = maxOf(dm.lastSeenMessageId, dm.lastSentMessageId, seenMessageId)
            val tsLong = seenTimestampSeconds.toLong() and 0xFFFF_FFFFL
            val newSeenTs = maxOf(dm.lastSeenMessageTs, dm.lastSentMessageTs, tsLong)
            val u = dm.copy(
                unreadCount = 0,
                lastSeenMessageId = newSeenId,
                lastSeenMessageTs = newSeenTs
            )
            dialogsDict.put(channelId, u)
            val idx = dialogs.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) {
                dialogs[idx] = u
                changed = true
            }
            updated = u
        }
        if (changed && updated != null) {
            val row = updated!!
            appScope.launch(ioDispatcher) { directMessageDao.upsert(row) }
            if (postEvent) {
                notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_READ_DIALOG_MESSAGE
                )
            }
        }
    }

    private fun ChannelMessage.isEphemeralControlMessage(): Boolean {
        return code == MessageEntity.CODE_EPHEMERAL ||
            code == MessageEntity.CODE_UPDATE_EPHEMERAL ||
            code == MessageEntity.CODE_DELETE_EPHEMERAL
    }

    private fun DirectMessage.withSanitizedServerMessageIds(): DirectMessage {
        val sent = sanitizeProvisionalId(lastSentMessageId)
        val seen = sanitizeProvisionalId(lastSeenMessageId)
        return if (sent == lastSentMessageId && seen == lastSeenMessageId) this else copy(
            lastSentMessageId = sent,
            lastSeenMessageId = seen
        )
    }

    fun updateOnNewMessage(msg: ChannelMessage, currentUserId: Long) {
        if (msg.mode != STREAM_MODE_DM && msg.mode != STREAM_MODE_GROUP) return
        val isEphemeralControl = msg.isEphemeralControlMessage()
        val isContentMutation = msg.code == CODE_CHAT_UPDATE ||
            msg.code == CODE_CHAT_REMOVE ||
            msg.code == MessageEntity.CODE_UPDATE_EPHEMERAL ||
            msg.code == MessageEntity.CODE_DELETE_EPHEMERAL
        var updatedDm: DirectMessage? = null
        synchronized(this) {
            var dm = dialogsDict[msg.channelId]
            if (dm == null) {
                if (isContentMutation || isEphemeralControl) return
                dm = msg.toDirectMessageFromIncoming(currentUserId, appContext, currentChannelId)
                if (msg.mode == STREAM_MODE_DM && msg.senderId != currentUserId) {
                    participantsByChannel.put(
                        msg.channelId,
                        listOf(
                            DmParticipant(
                                msg.senderId,
                                msg.username,
                                msg.displayName.ifBlank { msg.username },
                                msg.avatar
                            )
                        )
                    )
                }
                dialogsDict.put(dm.channelId, dm)
                updatedDm = dm
                var lo = 0
                var hi = dialogs.size
                val target = dm.lastSentMessageTs
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (dialogs[mid].lastSentMessageTs > target) lo = mid + 1 else hi = mid
                }
                dialogs.add(lo, dm)
            } else {
                val isFromMe = msg.senderId == currentUserId
                val isCurrentlyOpen = currentChannelId == msg.channelId
                var baseDm = dm.withSanitizedServerMessageIds()
                if (msg.mode == STREAM_MODE_DM && !isFromMe && dm.otherUserId == 0L) {
                    val senderName = msg.displayName.ifBlank { msg.username }
                    if (senderName.isNotBlank()) {
                        baseDm = baseDm.copy(
                            label = senderName,
                            displayName = senderName,
                            username = msg.username.ifBlank { senderName },
                            avatarUrl = msg.avatar.ifBlank { dm.avatarUrl },
                            otherUserId = msg.senderId
                        )
                    }
                }

                val newUnread = when {
                    isContentMutation -> baseDm.unreadCount
                    isEphemeralControl -> baseDm.unreadCount
                    isCurrentlyOpen -> 0
                    isFromMe -> baseDm.unreadCount
                    else -> baseDm.unreadCount + 1
                }
                val newPreview = if (!isContentMutation ||
                    msg.code == CODE_CHAT_UPDATE ||
                    msg.code == MessageEntity.CODE_UPDATE_EPHEMERAL)
                    messagePreviewForDialog(appContext, msg.content) else baseDm.lastMessageContent

                val canAdvanceTimeline = !isContentMutation && !isEphemeralControl && msg.messageId > 0L

                val newSentMessageId = if (canAdvanceTimeline) msg.messageId else baseDm.lastSentMessageId

                val newLastSeenId = if (isFromMe && canAdvanceTimeline && msg.messageId > baseDm.lastSeenMessageId)
                    msg.messageId else baseDm.lastSeenMessageId

                val newSentTs = if (canAdvanceTimeline && msg.createTimeSeconds > 0) {
                    maxOf(baseDm.lastSentMessageTs, msg.createTimeSeconds.toLong() and 0xFFFF_FFFFL)
                } else baseDm.lastSentMessageTs
                val result = baseDm.copy(
                    lastMessageContent = newPreview.ifBlank { baseDm.lastMessageContent },
                    lastSentMessageId = newSentMessageId.takeIf { it > 0 } ?: baseDm.lastSentMessageId,
                    lastSentMessageTs = newSentTs,
                    lastSeenMessageId = newLastSeenId,
                    unreadCount = newUnread
                )
                updatedDm = result
                dialogsDict.put(msg.channelId, result)
                reorderDialogInPlace(msg.channelId, result, isContentMutation || isEphemeralControl)
            }
        }
        updatedDm?.let { dm ->
            appScope.launch(ioDispatcher) { directMessageDao.upsert(dm) }
            notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
        }
    }

    private fun reorderDialogInPlace(
        channelId: Long,
        result: DirectMessage,
        isContentMutation: Boolean
    ) {
        val oldIdx = dialogs.indexOfFirst { it.channelId == channelId }
        if (oldIdx < 0) return
        if (isContentMutation) {
            dialogs[oldIdx] = result
            return
        }
        dialogs.removeAt(oldIdx)
        var lo = 0
        var hi = dialogs.size
        val target = result.lastSentMessageTs
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (dialogs[mid].lastSentMessageTs > target) lo = mid + 1 else hi = mid
        }
        dialogs.add(lo, result)
    }

    private fun mergeDmUnreadFromList(cachedUnread: Int, api: DirectMessage): Int {
        val listSignalsReadState =
            api.lastSeenMessageTs != 0L || api.lastSentMessageTs != 0L
        return when {
            api.unreadCount != 0 -> api.unreadCount
            listSignalsReadState -> api.unreadCount
            else -> cachedUnread
        }
    }

    private fun putDialogs(list: List<DirectMessage>) {
        val snapshot: ArrayList<DirectMessage>
        synchronized(this) {
            for (dm in list) {
                val existing = dialogsDict[dm.channelId]
                if (existing == null) {
                    dialogsDict.put(dm.channelId, dm)
                } else {
                    val keepExistingIdentity = existing.otherUserId != 0L && dm.otherUserId == 0L
                    val merged = dm.copy(
                        label = if (keepExistingIdentity && existing.label.isNotBlank()) {
                            existing.label
                        } else {
                            dm.label.ifBlank { existing.label }
                        },
                        avatarUrl = dm.avatarUrl.ifBlank { existing.avatarUrl },
                        displayName = if (keepExistingIdentity && existing.displayName.isNotBlank()) {
                            existing.displayName
                        } else {
                            dm.displayName.ifBlank { existing.displayName }
                        },
                        username = if (keepExistingIdentity && existing.username.isNotBlank()) {
                            existing.username
                        } else {
                            dm.username.ifBlank { existing.username }
                        },
                        lastMessageContent = dm.lastMessageContent.ifBlank { existing.lastMessageContent },
                        isOnline = false,
                        otherUserId = if (dm.otherUserId != 0L) dm.otherUserId else existing.otherUserId,
                        groupCreatorId = if (dm.groupCreatorId != 0L) dm.groupCreatorId else existing.groupCreatorId,
                        lastSeenMessageId = maxOf(existing.lastSeenMessageId, dm.lastSeenMessageId),
                        lastSentMessageId = maxOf(existing.lastSentMessageId, dm.lastSentMessageId),
                        lastSeenMessageTs = maxOf(existing.lastSeenMessageTs, dm.lastSeenMessageTs),
                        lastSentMessageTs = maxOf(existing.lastSentMessageTs, dm.lastSentMessageTs),
                        unreadCount = mergeDmUnreadFromList(existing.unreadCount, dm)
                    )
                    dialogsDict.put(dm.channelId, merged)
                }
            }
            val apiIds = HashSet<Long>(list.size).apply { for (dm in list) add(dm.channelId) }
            var i = 0
            while (i < dialogsDict.size()) {
                if (dialogsDict.keyAt(i) !in apiIds) dialogsDict.removeAt(i) else i++
            }
            dialogs.clear()
            for (j in 0 until dialogsDict.size()) dialogs.add(dialogsDict.valueAt(j))
            dialogs.sortByDescending { it.lastSentMessageTs }
            snapshot = ArrayList(dialogs)
        }
        appScope.launch(ioDispatcher) {
            directMessageDao.upsertAll(snapshot)
        }
    }

    private fun patchDmRowReadStateFromSparseBadge(
        row: DirectMessage,
        p: ChannelDescription
    ): Pair<DirectMessage, Boolean> {
        var next = row
        var changed = false
        if (p.countMessUnread != next.unreadCount) {
            changed = true
            next = next.copy(unreadCount = p.countMessUnread)
        }
        if (p.hasLastSentMessage()) {
            val m = p.lastSentMessage
            val ts = m.timestampSeconds.toLong() and 0xFFFF_FFFFL
            val idNewer = m.id != 0L && m.id > next.lastSentMessageId
            val tsNewerNoMessageId = m.id == 0L && ts > 0L && ts > next.lastSentMessageTs
            val isNewer = idNewer || tsNewerNoMessageId
            if (m.id != 0L) {
                val merged = maxOf(next.lastSentMessageId, m.id)
                if (merged != next.lastSentMessageId) {
                    changed = true
                    next = next.copy(lastSentMessageId = merged)
                }
            }
            if (ts > 0L) {
                val mergedTs = maxOf(next.lastSentMessageTs, ts)
                if (mergedTs != next.lastSentMessageTs) {
                    changed = true
                    next = next.copy(lastSentMessageTs = mergedTs)
                }
            }
            if (m.content.isNotEmpty()) {
                val sameLastMessage = m.id != 0L && m.id == next.lastSentMessageId
                val sameTsHeader = m.id == 0L && ts > 0L && ts == next.lastSentMessageTs
                val needsPreview = next.lastMessageContent.isBlank() || sameLastMessage || sameTsHeader || isNewer
                if (needsPreview) {
                    val preview = messagePreviewForDialog(appContext, m.content)
                    if (preview.isNotBlank() && preview != next.lastMessageContent) {
                        changed = true
                        next = next.copy(lastMessageContent = preview)
                    }
                }
            }
        }
        if (p.hasLastSeenMessage()) {
            val m = p.lastSeenMessage
            if (m.id != 0L) {
                val merged = maxOf(next.lastSeenMessageId, m.id)
                if (merged != next.lastSeenMessageId) {
                    changed = true
                    next = next.copy(lastSeenMessageId = merged)
                }
            }
            val ts = m.timestampSeconds.toLong() and 0xFFFF_FFFFL
            if (ts > 0L) {
                val mergedTs = maxOf(next.lastSeenMessageTs, ts)
                if (mergedTs != next.lastSeenMessageTs) {
                    changed = true
                    next = next.copy(lastSeenMessageTs = mergedTs)
                }
            }
        }
        return Pair(next, changed)
    }

    private fun mergeDmMetadataFromDesc(
        row: DirectMessage,
        desc: ChannelDescription,
        currentUserId: Long
    ): Pair<DirectMessage, Boolean> {
        val fresh = desc.toDirectMessage(currentUserId, appContext)
        var next = row
        var changed = false
        val peerResolved = fresh.type != CHANNEL_TYPE_DM ||
            (fresh.otherUserId != 0L && fresh.otherUserId != currentUserId)
        val displayName = if (desc.type == CHANNEL_TYPE_GROUP && desc.channelLabel.isNotBlank()) {
            desc.channelLabel
        } else if (peerResolved) {
            fresh.displayName
        } else {
            ""
        }
        if (fresh.type != 0 && fresh.type != next.type) {
            changed = true
            next = next.copy(type = fresh.type)
        }
        if (peerResolved || desc.type == CHANNEL_TYPE_GROUP) {
            if (fresh.label.isNotBlank() && fresh.label != next.label) {
                changed = true
                next = next.copy(label = fresh.label)
            }
            if (displayName.isNotBlank() && displayName != next.displayName) {
                changed = true
                next = next.copy(displayName = displayName)
            }
            if (fresh.username.isNotBlank() && fresh.username != next.username) {
                changed = true
                next = next.copy(username = fresh.username)
            }
            if (fresh.avatarUrl.isNotBlank() && fresh.avatarUrl != next.avatarUrl) {
                changed = true
                next = next.copy(avatarUrl = fresh.avatarUrl)
            }
        }
        if (fresh.type == CHANNEL_TYPE_DM && fresh.otherUserId != 0L && fresh.otherUserId != next.otherUserId) {
            changed = true
            next = next.copy(otherUserId = fresh.otherUserId)
        }
        return Pair(next, changed)
    }

    private fun shouldCacheParticipantsFromDesc(
        desc: ChannelDescription,
        participants: List<DmParticipant>
    ): Boolean {
        if (participants.isEmpty()) return false
        if (desc.type == CHANNEL_TYPE_GROUP && participants.size <= 1) return false
        return true
    }

    private fun applyDmReadStatePatchFromSocket(
        badgeDescs: List<ChannelDescription>,
        currentUserId: Long
    ): Boolean {
        if (badgeDescs.isEmpty()) return false
        val byId = badgeDescs.associateBy { it.channelId }
        var changed = false
        var snapshot: ArrayList<DirectMessage>? = null
        synchronized(this) {
            for (p in badgeDescs) {
                val participants = p.extractParticipants()
                if (p.active == 1 && shouldCacheParticipantsFromDesc(p, participants)) {
                    participantsByChannel.put(p.channelId, participants)
                }
                if (p.active != 1 || dialogsDict.get(p.channelId) != null) continue
                val inserted = p.toDirectMessage(currentUserId, appContext)
                dialogsDict.put(inserted.channelId, inserted)
                dialogs.add(inserted)
                changed = true
            }
            val newList = ArrayList<DirectMessage>(dialogs.size)
            for (row in dialogs) {
                val p = byId[row.channelId]
                if (p == null) {
                    newList.add(row)
                } else {
                    val (withMetadata, metadataChanged) = mergeDmMetadataFromDesc(row, p, currentUserId)
                    val (next, readStateChanged) = patchDmRowReadStateFromSparseBadge(withMetadata, p)
                    if (metadataChanged || readStateChanged) {
                        changed = true
                        dialogsDict.put(next.channelId, next)
                    }
                    newList.add(next)
                }
            }
            if (changed) {
                dialogs.clear()
                dialogs.addAll(newList.sortedByDescending { it.lastSentMessageTs })
                snapshot = ArrayList(dialogs)
            }
        }
        if (!changed) return false
        snapshot?.let { snap ->
            appScope.launch(ioDispatcher) { directMessageDao.upsertAll(snap) }
        }
        return true
    }

    private suspend fun syncDmBadgesWithApi(session: StoredSession): Boolean {
        val currentUserId = session.userId.toLongOrNull() ?: 0L
        return runCatching {
            val badge = api.listChannelBadgeCount(session.apiUrl, session.token, 0L)
            applyDmReadStatePatchFromSocket(badge.channeldescList, currentUserId)
        }.getOrElse { e ->
            Log.e(TAG, "syncDmBadgesWithApi failed", e)
            false
        }
    }

    private suspend fun loadDialogsFromDb() {
        val cached = withContext(ioDispatcher) { directMessageDao.getAll() }
        if (cached.isNotEmpty()) {
            synchronized(this) {
                dialogs.clear()
                dialogsDict.clear()
                val sorted = cached.sortedByDescending { it.lastSentMessageTs }
                dialogs.addAll(sorted)
                for (dm in sorted) {
                    dialogsDict.put(dm.channelId, dm)
                }
            }
            dialogsLoaded = true
            notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
        }
    }

    private suspend fun observeMarkAsRead() {
        socketEventDispatcher.markAsRead.collect { event ->
            if (event.channelId == 0L) return@collect
            markDialogAsRead(event.channelId)
        }
    }

    private suspend fun observeLastSeenMessages() {
        socketEventDispatcher.lastSeenMessageEvents.collect { event ->
            applyLastSeenDmFromSocket(event)
        }
    }

    private suspend fun observeUserChannelAdded() {
        val currentUserId = sessionManager.sessionFlow
            .first { it != null }
            ?.userId
            ?.toLongOrNull() ?: 0L
        socketEventDispatcher.userChannelAddedEvents.collect { event ->
            applyUserChannelAdded(event, currentUserId)
        }
    }

    private suspend fun observeUserChannelRemoved() {
        val currentUserId = sessionManager.sessionFlow
            .first { it != null }
            ?.userId
            ?.toLongOrNull() ?: 0L
        socketEventDispatcher.userChannelRemovedEvents.collect { event ->
            applyUserChannelRemoved(event, currentUserId)
        }
    }

    private fun applyUserChannelAdded(event: UserChannelAdded, currentUserId: Long) {
        if (!event.hasChannelDesc()) return
        val desc = event.channelDesc
        if (desc.type != CHANNEL_TYPE_DM && desc.type != CHANNEL_TYPE_GROUP) return
        val channelId = desc.channelId
        if (channelId == 0L) return
        val addedParticipants = event.usersList.map { it.toDmParticipant() }
        val descParticipants = desc.extractParticipants()
        val containsSelf = event.usersList.any { it.userId == currentUserId } ||
            desc.userIdsList.any { it == currentUserId }
        var toPersist: DirectMessage? = null
        var changed = false
        synchronized(this) {
            val existing = dialogsDict[channelId]
            if (existing == null && !containsSelf) return@synchronized
            if (descParticipants.isNotEmpty()) {
                mergeParticipants(channelId, descParticipants)
                changed = true
            }
            if (addedParticipants.isNotEmpty()) {
                mergeParticipants(channelId, addedParticipants)
                changed = true
            }
            val fresh = desc.toDirectMessage(currentUserId, appContext)
            val channelParticipants = participantsByChannel[channelId] ?: emptyList()
            var row = if (existing == null) {
                val (withFallback, fallbackChanged) = applyGroupFallbackMetadata(fresh, desc, event.usersList)
                changed = changed || fallbackChanged
                withFallback
            } else {
                val (withMetadata, metadataChanged) = mergeDmMetadataFromDesc(existing, desc, currentUserId)
                val (withFallback, fallbackChanged) = applyGroupFallbackMetadata(withMetadata, desc, event.usersList)
                changed = changed || metadataChanged
                changed = changed || fallbackChanged
                withFallback.copy(
                    lastMessageContent = withFallback.lastMessageContent.ifBlank { fresh.lastMessageContent },
                    lastSeenMessageId = maxOf(withFallback.lastSeenMessageId, fresh.lastSeenMessageId),
                    lastSentMessageId = maxOf(withFallback.lastSentMessageId, fresh.lastSentMessageId),
                    lastSeenMessageTs = maxOf(withFallback.lastSeenMessageTs, fresh.lastSeenMessageTs),
                    lastSentMessageTs = maxOf(withFallback.lastSentMessageTs, fresh.lastSentMessageTs),
                    unreadCount = mergeDmUnreadFromList(withFallback.unreadCount, fresh)
                )
            }
            if (desc.type == CHANNEL_TYPE_DM) {
                val patched = applyDmPeerIdentity(row, currentUserId, event.usersList, channelParticipants)
                if (patched != row) {
                    changed = true
                    row = patched
                }
            }
            dialogsDict.put(channelId, row)
            val oldIdx = dialogs.indexOfFirst { it.channelId == channelId }
            if (oldIdx >= 0) dialogs.removeAt(oldIdx)
            var lo = 0
            var hi = dialogs.size
            val target = row.lastSentMessageTs
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (dialogs[mid].lastSentMessageTs > target) lo = mid + 1 else hi = mid
            }
            dialogs.add(lo, row)
            toPersist = row
            changed = true
        }
        toPersist?.let { dm -> appScope.launch(ioDispatcher) { directMessageDao.upsert(dm) } }
        if (changed) {
            notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelMembersDidLoad, channelId)
        }
    }

    private fun applyUserChannelRemoved(event: UserChannelRemoved, currentUserId: Long) {
        if (event.channelType != CHANNEL_TYPE_DM && event.channelType != CHANNEL_TYPE_GROUP) return
        val channelId = event.channelId
        if (channelId == 0L) return
        val removedIds = event.userIdsList.toHashSet()
        if (removedIds.isEmpty()) return
        val removedSelf = currentUserId in removedIds
        var changed = false
        synchronized(this) {
            if (removedSelf) {
                val idx = dialogs.indexOfFirst { it.channelId == channelId }
                if (idx >= 0) dialogs.removeAt(idx)
                if (dialogsDict[channelId] != null) {
                    dialogsDict.remove(channelId)
                }
                participantsByChannel.remove(channelId)
                buzzStates.remove(channelId)
                if (currentChannelId == channelId) currentChannelId = null
                changed = true
            } else {
                changed = removeParticipants(channelId, removedIds)
            }
        }
        if (!changed) return
        if (removedSelf) {
            appScope.launch(ioDispatcher) {
                directMessageDao.delete(channelId)
                messageDao.deleteByChannel(channelId)
            }
            notificationCenter.postNotificationOnMainThread(NotificationCenter.closeChats, channelId, event.channelType)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.navigateToMessagesTab)
        }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.channelMembersDidLoad, channelId)
    }

    private fun applyLastSeenDmFromSocket(event: LastSeenMessageEvent) {
        if (event.channelId == 0L) return
        if (event.clanId != 0L) return
        val ts = event.timestampSeconds
        when {
            event.messageId != 0L && event.badgeCount == 0 ->
                updateLastSeen(event.channelId, event.messageId, ts)
            event.messageId != 0L ->
                updateDialogLastSeen(event.channelId, event.messageId, event.badgeCount, ts)
            ts != 0 ->
                applyLastSeenTsOnlyDm(event.channelId, ts, event.badgeCount)
        }
    }

    private fun applyLastSeenTsOnlyDm(channelId: Long, timestampSeconds: Int, badgeCount: Int) {
        val tsLong = timestampSeconds.toLong() and 0xFFFF_FFFFL
        if (tsLong == 0L) return
        var updated: DirectMessage? = null
        synchronized(this) {
            val dm = dialogsDict[channelId] ?: return
            val newSeenTs = maxOf(dm.lastSeenMessageTs, tsLong)
            val newUnread = badgeCount.coerceAtLeast(0)
            if (newSeenTs == dm.lastSeenMessageTs && newUnread == dm.unreadCount) return
            val u = dm.copy(
                lastSeenMessageTs = newSeenTs,
                unreadCount = newUnread
            )
            dialogsDict.put(channelId, u)
            val idx = dialogs.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) dialogs[idx] = u
            updated = u
        }
        updated?.let { appScope.launch(ioDispatcher) { directMessageDao.upsert(it) } }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_READ_DIALOG_MESSAGE
        )
    }

    fun updateLastSeen(channelId: Long, messageId: Long, timestampSeconds: Int = 0) {
        var changed = false
        var updated: DirectMessage? = null
        synchronized(this) {
            val dm = dialogsDict[channelId] ?: return
            if (messageId <= dm.lastSeenMessageId) return
            val tsLong = timestampSeconds.toLong() and 0xFFFF_FFFFL
            val newSeenTs = maxOf(dm.lastSeenMessageTs, dm.lastSentMessageTs, tsLong)
            val u = dm.copy(
                lastSeenMessageId = messageId,
                unreadCount = 0,
                lastSeenMessageTs = newSeenTs
            )
            dialogsDict.put(channelId, u)
            val idx = dialogs.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) {
                dialogs[idx] = u
                changed = true
            }
            updated = u
        }
        if (changed && updated != null) {
            val row = updated!!
            appScope.launch(ioDispatcher) { directMessageDao.upsert(row) }
            notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_READ_DIALOG_MESSAGE
            )
        }
    }
}
