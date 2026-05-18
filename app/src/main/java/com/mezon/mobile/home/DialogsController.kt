package com.mezon.mobile.home

import android.content.Context
import android.util.LongSparseArray
import android.util.Log
import com.mezon.mezon.api.ChannelMessage
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.data.db.DirectMessageDao
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.messages.DirectMessage
import com.mezon.mobile.home.messages.DmParticipant
import com.mezon.mobile.home.messages.extractParticipants
import com.mezon.mobile.home.messages.toDirectMessage
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
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
import com.mezon.mobile.home.call.messagePreviewForDialog
import dagger.Lazy
import com.mezon.mezon.api.ChannelDescription
import com.mezon.mezon.rtapi.LastSeenMessageEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
        if (channelId == 0L) return
        val hasCache: Boolean
        synchronized(this) { hasCache = participantsByChannel[channelId] != null }
        if (hasCache) return
        appScope.launch(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
                    val response = api.listChannelUsersUC(session.apiUrl, session.token, channelId)
                    val count = response.userIdsCount
                    if (count == 0) return@withAutoRefresh
                    val participants = ArrayList<DmParticipant>(count)
                    for (i in 0 until count) {
                        participants.add(DmParticipant(
                            userId = response.getUserIds(i),
                            username = response.usernamesList.getOrElse(i) { "" },
                            displayName = response.displayNamesList.getOrElse(i) { "" },
                            avatarUrl = response.avatarsList.getOrElse(i) { "" }
                        ))
                    }
                    synchronized(this@DialogsController) {
                        participantsByChannel.put(channelId, participants)
                    }
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadDmParticipants failed for channel $channelId", e)
            }
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

    fun loadDialogs(page: Int = 1, limit: Int = 500) {
        appScope.launch(ioDispatcher) {
            try {
                if (StartupCache.suppressHomeListApiForIncomingCallWake) {
                    return@launch
                }

                val cacheKey = apiCacheKey("listChannelDescs", page)
                val hasCache: Boolean
                synchronized(this@DialogsController) { hasCache = dialogs.isNotEmpty() }
                Log.d(TAG, "loadDialogs: hasCache=$hasCache dialogsLoaded=$dialogsLoaded online=${networkMonitor.isOnline.value}")

                if (!networkMonitor.isOnline.value && hasCache) {
                    Log.d(TAG, "loadDialogs: offline+cache → skip")
                    if (!dialogsLoaded) dialogsLoaded = true
                    badgeCoordinator.get().processDeferredQueue()
                    return@launch
                }
                if (hasCache && cacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP) {
                    Log.d(TAG, "loadDialogs: cache fresh → skip list, sync DM badges")
                    if (!dialogsLoaded) dialogsLoaded = true
                    sessionManager.withAutoRefresh { session -> syncDmBadgesWithApi(session) }
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
                    badgeCoordinator.get().processDeferredQueue()
                    return@launch
                }

                Log.d(TAG, "loadDialogs: fetching from API…")
                sessionManager.withAutoRefresh { session ->
                    val currentUserId = session.userId.toLongOrNull() ?: 0L

                    val response = api.listChannelDescs(session.apiUrl, session.token, CHANNEL_TYPE_GROUP, page, limit)

                    val rawList = response.channeldescList
                    val activeCount = rawList.count { it.active == 1 }
                    val inactiveCount = rawList.size - activeCount
                    // Log.d(TAG, "loadDialogs: raw=${rawList.size} active=$activeCount inactive=$inactiveCount")
                    // rawList.forEachIndexed { i, ch ->
                    //     Log.d(TAG, "  [$i] channelId=${ch.channelId} type=${ch.type} active=${ch.active} label='${ch.channelLabel}'")
                    // }

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

                    val withContent = merged.count { it.lastMessageContent.isNotBlank() }
                    Log.d(TAG, "loadDialogs: API returned ${merged.size} items, withContent=$withContent")

                    putDialogs(merged)
                    cacheTracker.markCalled(cacheKey)
                    syncDmBadgesWithApi(session)
                }

                dialogsLoaded = true
                Log.d(TAG, "loadDialogs: done, posting dialogsNeedReload")
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

    fun updateOnNewMessage(msg: ChannelMessage, currentUserId: Long) {
        if (msg.mode != STREAM_MODE_DM && msg.mode != STREAM_MODE_GROUP) return
        var updatedDm: DirectMessage? = null
        synchronized(this) {
            val dm = dialogsDict[msg.channelId] ?: return
            val isContentMutation = msg.code == CODE_CHAT_UPDATE || msg.code == CODE_CHAT_REMOVE
            val isFromMe = msg.senderId == currentUserId
            val isCurrentlyOpen = currentChannelId == msg.channelId

            val newUnread = when {
                isContentMutation -> dm.unreadCount
                isCurrentlyOpen -> 0
                isFromMe -> dm.unreadCount
                else -> dm.unreadCount + 1
            }
            val newPreview = if (!isContentMutation || msg.code == CODE_CHAT_UPDATE)
                messagePreviewForDialog(appContext, msg.content) else dm.lastMessageContent

            val newSentMessageId = if (!isContentMutation) msg.messageId else dm.lastSentMessageId

            val newLastSeenId = if (isFromMe && !isContentMutation && msg.messageId > dm.lastSeenMessageId)
                msg.messageId else dm.lastSeenMessageId

            val newSentTs = if (!isContentMutation && msg.createTimeSeconds > 0) {
                maxOf(dm.lastSentMessageTs, msg.createTimeSeconds.toLong() and 0xFFFF_FFFFL)
            } else dm.lastSentMessageTs
            val result = dm.copy(
                lastMessageContent = newPreview.ifBlank { dm.lastMessageContent },
                lastSentMessageId = newSentMessageId.takeIf { it > 0 } ?: dm.lastSentMessageId,
                lastSentMessageTs = newSentTs,
                lastSeenMessageId = newLastSeenId,
                unreadCount = newUnread
            )
            updatedDm = result
            dialogsDict.put(msg.channelId, result)
            reorderDialogInPlace(msg.channelId, result, isContentMutation)
        }
        updatedDm?.let { dm ->
            appScope.launch { directMessageDao.upsert(dm) }
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
                    val merged = dm.copy(
                        label = dm.label.ifBlank { existing.label },
                        avatarUrl = dm.avatarUrl.ifBlank { existing.avatarUrl },
                        displayName = dm.displayName.ifBlank { existing.displayName },
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
                val preview = messagePreviewForDialog(appContext, m.content)
                if (preview.isNotBlank() && preview != next.lastMessageContent) {
                    val sameLastMessage = m.id != 0L && m.id == next.lastSentMessageId
                    val sameTsSparseHeader = m.id == 0L && ts > 0L && ts == next.lastSentMessageTs
                    val shouldTakePreview =
                        isNewer || next.lastMessageContent.isBlank() || sameLastMessage || sameTsSparseHeader
                    if (shouldTakePreview) {
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

    private fun applyDmReadStatePatchFromSocket(badgeDescs: List<ChannelDescription>) {
        if (badgeDescs.isEmpty()) return
        val byId = badgeDescs.associateBy { it.channelId }
        var changed = false
        var snapshot: ArrayList<DirectMessage>? = null
        synchronized(this) {
            val newList = ArrayList<DirectMessage>(dialogs.size)
            for (row in dialogs) {
                val p = byId[row.channelId]
                if (p == null) {
                    newList.add(row)
                } else {
                    val (next, rowChanged) = patchDmRowReadStateFromSparseBadge(row, p)
                    if (rowChanged) {
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
        if (!changed) return
        snapshot?.let { snap ->
            appScope.launch(ioDispatcher) { directMessageDao.upsertAll(snap) }
        }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
        )
    }

    private suspend fun syncDmBadgesWithApi(session: StoredSession) {
        runCatching {
            val badge = api.listChannelBadgeCount(session.apiUrl, session.token, 0L)
            val badgeWithContent = badge.channeldescList.count { it.hasLastSentMessage() && it.lastSentMessage.content.isNotEmpty() }
            Log.d(TAG, "syncDmBadgesWithApi: badge returned ${badge.channeldescList.size} channels, withContent=$badgeWithContent")
            applyDmReadStatePatchFromSocket(badge.channeldescList)
        }
    }

    private suspend fun loadDialogsFromDb() {
        Log.d(TAG, "loadDialogsFromDb: start")
        val cached = withContext(ioDispatcher) { directMessageDao.getAll() }
        Log.d(TAG, "loadDialogsFromDb: Room returned ${cached.size} items")
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
            cacheTracker.markCalled(apiCacheKey("listChannelDescs", 1))
            dialogsLoaded = true
            Log.d(TAG, "loadDialogsFromDb: done, posting dialogsNeedReload")
            notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
        } else {
            Log.d(TAG, "loadDialogsFromDb: empty cache, no notification")
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
