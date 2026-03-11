package com.mezon.mobile.home

import android.util.LongSparseArray
import android.util.Log
import com.mezon.mezon.api.ChannelMessage
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.data.db.DirectMessageDao
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.messages.DirectMessage
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
import com.mezon.mobile.util.parseContentPreview
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DialogsController"

@Singleton
class DialogsController @Inject constructor(
    private val api: MezonApi,
    private val directMessageDao: DirectMessageDao,
    private val socketEventDispatcher: SocketEventDispatcher,
    private val sessionManager: SessionManager,
    private val notificationCenter: NotificationCenter,
    private val networkMonitor: NetworkMonitor,
    private val cacheTracker: ApiCacheTracker,
    private val activeChannelTracker: ActiveChannelTracker,
    private val notificationHelper: NotificationHelper,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    val dialogs = ArrayList<DirectMessage>()
    val dialogsDict = LongSparseArray<DirectMessage>()

    var dialogsLoaded = false
        private set

    private var currentChannelId: Long? = null

    init {
        appScope.launch { loadDialogsFromDb() }
        appScope.launch { observePresenceChanges() }
        appScope.launch { observeMarkAsRead() }
    }

    @Synchronized
    fun getDialogs(): List<DirectMessage> = ArrayList(dialogs)

    @Synchronized
    fun getDialog(channelId: Long): DirectMessage? = dialogsDict[channelId]

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

    fun loadDialogs(page: Int = 1, limit: Int = 50) {
        appScope.launch(ioDispatcher) {
            try {
                val cacheKey = apiCacheKey("listChannelDescs", page)
                val hasCache: Boolean
                synchronized(this@DialogsController) { hasCache = dialogs.isNotEmpty() }
                Log.d(TAG, "loadDialogs: hasCache=$hasCache dialogsLoaded=$dialogsLoaded online=${networkMonitor.isOnline.value}")

                if (!networkMonitor.isOnline.value && hasCache) { Log.d(TAG, "loadDialogs: offline+cache → skip"); return@launch }
                if (hasCache && cacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP) { Log.d(TAG, "loadDialogs: cache fresh → skip"); return@launch }

                Log.d(TAG, "loadDialogs: fetching from API…")
                sessionManager.withAutoRefresh { session ->
                    val currentUserId = session.userId.toLongOrNull() ?: 0L

                    val (dmResponse, groupResponse) = coroutineScope {
                        val dm = async { api.listChannelDescs(session.apiUrl, session.token, CHANNEL_TYPE_DM, page, limit) }
                        val group = async { api.listChannelDescs(session.apiUrl, session.token, CHANNEL_TYPE_GROUP, page, limit) }
                        awaitAll(dm, group).let { it[0] to it[1] }
                    }

                    val merged = (dmResponse.channeldescList + groupResponse.channeldescList)
                        .filter { it.active == 1 }
                        .distinctBy { it.channelId }
                        .map { it.toDirectMessage(currentUserId) }
                        .sortedByDescending { it.lastMessageTimestamp }

                    Log.d(TAG, "loadDialogs: API returned ${merged.size} items")
                    putDialogs(merged)
                    cacheTracker.markCalled(cacheKey)
                }

                dialogsLoaded = true
                Log.d(TAG, "loadDialogs: done, posting dialogsNeedReload")
                notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
            } catch (e: Exception) {
                Log.e(TAG, "loadDialogs failed", e)
                dialogsLoaded = true
                notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsLoadError, e.message ?: "Failed to load")
            }
        }
    }

    fun markDialogAsRead(channelId: Long) {
        var changed = false
        synchronized(this) {
            val dm = dialogsDict[channelId]
            if (dm == null || dm.unreadCount == 0) return
            val updated = dm.copy(unreadCount = 0)
            dialogsDict.put(channelId, updated)
            val idx = dialogs.indexOfFirst { it.channelId == channelId }
            if (idx >= 0) { dialogs[idx] = updated; changed = true }
        }
        if (changed) {
            appScope.launch { directMessageDao.updateUnreadCount(channelId, 0) }
            notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_READ_DIALOG_MESSAGE
            )
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
                parseContentPreview(msg.content) else dm.lastMessageContent
            val newTimestamp = if (!isContentMutation) msg.createTimeSeconds.toLong() else dm.lastMessageTimestamp

            val result = dm.copy(
                lastMessageContent = newPreview.ifBlank { dm.lastMessageContent },
                lastMessageTimestamp = newTimestamp.takeIf { it > 0 } ?: dm.lastMessageTimestamp,
                unreadCount = newUnread
            )
            updatedDm = result
            dialogsDict.put(msg.channelId, result)
            val idx = dialogs.indexOfFirst { it.channelId == msg.channelId }
            if (idx >= 0) dialogs[idx] = result
            if (!isContentMutation) dialogs.sortByDescending { it.lastMessageTimestamp }
        }
        updatedDm?.let { dm ->
            appScope.launch { directMessageDao.upsert(dm) }
            notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
        }
    }

    private fun putDialogs(list: List<DirectMessage>) {
        synchronized(this) {
            dialogs.clear()
            dialogsDict.clear()
            dialogs.addAll(list)
            for (dm in list) dialogsDict.put(dm.channelId, dm)
        }
        appScope.launch { directMessageDao.upsertAll(list) }
    }

    private suspend fun loadDialogsFromDb() {
        Log.d(TAG, "loadDialogsFromDb: start")
        val cached = withContext(ioDispatcher) { directMessageDao.getAll() }
        Log.d(TAG, "loadDialogsFromDb: Room returned ${cached.size} items")
        if (cached.isNotEmpty()) {
            synchronized(this) {
                dialogs.clear()
                dialogsDict.clear()
                val sorted = cached.distinctBy { it.channelId }.sortedByDescending { it.lastMessageTimestamp }
                dialogs.addAll(sorted)
                for (dm in sorted) dialogsDict.put(dm.channelId, dm)
            }
            Log.d(TAG, "loadDialogsFromDb: done, posting dialogsNeedReload")
            notificationCenter.postNotificationOnMainThread(NotificationCenter.dialogsNeedReload)
        } else {
            Log.d(TAG, "loadDialogsFromDb: empty cache, no notification")
        }
    }

    private suspend fun observePresenceChanges() {
        socketEventDispatcher.statusPresenceEvents.collect { event ->
            val onlineUserIds = event.joinsList.map { it.userId }.toSet()
            val offlineUserIds = event.leavesList.map { it.userId }.toSet()
            if (onlineUserIds.isEmpty() && offlineUserIds.isEmpty()) return@collect

            val changedDms = mutableListOf<DirectMessage>()
            synchronized(this) {
                for (i in dialogs.indices) {
                    val dm = dialogs[i]
                    val updated = when {
                        dm.type == CHANNEL_TYPE_DM && dm.otherUserId in onlineUserIds && !dm.isOnline ->
                            dm.copy(isOnline = true)
                        dm.type == CHANNEL_TYPE_DM && dm.otherUserId in offlineUserIds && dm.isOnline ->
                            dm.copy(isOnline = false)
                        else -> null
                    }
                    if (updated != null) {
                        dialogs[i] = updated
                        dialogsDict.put(updated.channelId, updated)
                        changedDms.add(updated)
                    }
                }
            }
            if (changedDms.isNotEmpty()) {
                appScope.launch { changedDms.forEach { directMessageDao.updateOnlineStatus(it.channelId, it.isOnline) } }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.onlineStatusChanged)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_STATUS
                )
            }
        }
    }

    private suspend fun observeMarkAsRead() {
        socketEventDispatcher.markAsRead.collect { event ->
            if (event.channelId == 0L) return@collect
            markDialogAsRead(event.channelId)
        }
    }
}
