package ai.mezon.app.home.messages

import ai.mezon.app.data.db.DirectMessageDao
import ai.mezon.app.di.ApplicationScope
import ai.mezon.app.network.CHANNEL_TYPE_DM
import ai.mezon.app.network.SocketEventDispatcher
import ai.mezon.app.session.SessionManager
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectStore @Inject constructor(
    private val directMessageDao: DirectMessageDao,
    private val socketEventDispatcher: SocketEventDispatcher,
    private val sessionManager: SessionManager,
    @ApplicationScope private val appScope: CoroutineScope
) {
    companion object {
        private const val TAG = "DirectStore"
        private const val STREAM_MODE_DM = 4
        private const val STREAM_MODE_GROUP = 2
        private const val CODE_CHAT_UPDATE = 2
        private const val CODE_CHAT_REMOVE = 3
    }

    private val _directs = MutableStateFlow<List<DirectMessage>>(emptyList())
    val directs: StateFlow<List<DirectMessage>> = _directs.asStateFlow()

    private val _currentChannelId = MutableStateFlow<Long?>(null)
    private val dbLoaded = CompletableDeferred<Unit>()

    init {
        appScope.launch {
            loadFromDb()
            dbLoaded.complete(Unit)
        }
        appScope.launch { dbLoaded.await(); observeNewMessages() }
        appScope.launch { dbLoaded.await(); observePresenceChanges() }
        appScope.launch { dbLoaded.await(); observeMarkAsRead() }
    }

    fun getDirectFlow(channelId: Long): Flow<DirectMessage?> =
        _directs.map { list -> list.firstOrNull { it.channelId == channelId } }

    fun setCurrentChannel(channelId: Long) {
        _currentChannelId.value = channelId
        markAsRead(channelId)
    }

    fun clearCurrentChannel() {
        _currentChannelId.value = null
    }

    fun markAsRead(channelId: Long) {
        _directs.update { current ->
            current.map { dm ->
                if (dm.channelId == channelId) dm.copy(unreadCount = 0) else dm
            }
        }
        appScope.launch { directMessageDao.updateUnreadCount(channelId, 0) }
    }

    fun setDirects(list: List<DirectMessage>) {
        _directs.value = list
            .distinctBy { it.channelId }
            .sortedByDescending { it.lastMessageTimestamp }
        appScope.launch { directMessageDao.upsertAll(list) }
    }

    fun upsertDirect(dm: DirectMessage) {
        _directs.update { current ->
            val idx = current.indexOfFirst { it.channelId == dm.channelId }
            val updated = if (idx >= 0) current.toMutableList().also { it[idx] = dm }
                          else current + dm
            updated.sortedByDescending { it.lastMessageTimestamp }
        }
        appScope.launch { directMessageDao.upsert(dm) }
    }

    private suspend fun loadFromDb() {
        val cached = directMessageDao.getAll()
        if (cached.isNotEmpty()) {
            _directs.value = cached.distinctBy { it.channelId }
            Log.d(TAG, "Loaded ${cached.size} DMs from DB cache")
        }
    }

    private suspend fun observeNewMessages() {
        val currentUserId = sessionManager.sessionFlow
            .first { it != null }
            ?.userId
            ?: ""

        socketEventDispatcher.channelMessages.collect { msg ->
            if (msg.mode != STREAM_MODE_DM && msg.mode != STREAM_MODE_GROUP) return@collect

            var updatedDm: DirectMessage? = null

            _directs.update { current ->
                val idx = current.indexOfFirst { it.channelId == msg.channelId }
                if (idx < 0) {
                    Log.d(TAG, "Received message for unknown channelId=${msg.channelId}, skipping")
                    return@update current
                }

                val dm = current[idx]
                val isContentMutation = msg.code == CODE_CHAT_UPDATE || msg.code == CODE_CHAT_REMOVE
                val isFromMe = msg.senderId.toString() == currentUserId
                val isCurrentlyOpen = _currentChannelId.value == msg.channelId

                val newUnread = when {
                    isContentMutation -> dm.unreadCount
                    isCurrentlyOpen -> 0
                    isFromMe -> dm.unreadCount
                    else -> dm.unreadCount + 1
                }

                val newPreview = if (!isContentMutation || msg.code == CODE_CHAT_UPDATE) {
                    parseMessagePreview(msg.content)
                } else {
                    dm.lastMessageContent
                }

                val newTimestamp = if (!isContentMutation) {
                    msg.createTimeSeconds.toLong()
                } else {
                    dm.lastMessageTimestamp
                }

                val result = dm.copy(
                    lastMessageContent = newPreview.ifBlank { dm.lastMessageContent },
                    lastMessageTimestamp = newTimestamp.takeIf { it > 0 } ?: dm.lastMessageTimestamp,
                    unreadCount = newUnread
                )
                updatedDm = result

                val mutable = current.toMutableList()
                mutable[idx] = result
                mutable.sortedByDescending { it.lastMessageTimestamp }
            }

            updatedDm?.let { appScope.launch { directMessageDao.upsert(it) } }
        }
    }

    private suspend fun observePresenceChanges() {
        socketEventDispatcher.statusPresenceEvents.collect { event ->
            val onlineUserIds = event.joinsList.map { it.userId }.toSet()
            val offlineUserIds = event.leavesList.map { it.userId }.toSet()

            if (onlineUserIds.isEmpty() && offlineUserIds.isEmpty()) return@collect

            val changedOnline = mutableListOf<Long>()
            val changedOffline = mutableListOf<Long>()

            _directs.update { current ->
                current.map { dm ->
                    when {
                        dm.type == CHANNEL_TYPE_DM && dm.channelId in onlineUserIds && !dm.isOnline -> {
                            changedOnline.add(dm.channelId)
                            dm.copy(isOnline = true)
                        }
                        dm.type == CHANNEL_TYPE_DM && dm.channelId in offlineUserIds && dm.isOnline -> {
                            changedOffline.add(dm.channelId)
                            dm.copy(isOnline = false)
                        }
                        else -> dm
                    }
                }
            }

            if (changedOnline.isNotEmpty() || changedOffline.isNotEmpty()) {
                appScope.launch {
                    changedOnline.forEach { directMessageDao.updateOnlineStatus(it, true) }
                    changedOffline.forEach { directMessageDao.updateOnlineStatus(it, false) }
                }
            }
        }
    }

    private suspend fun observeMarkAsRead() {
        socketEventDispatcher.markAsRead.collect { event ->
            if (event.channelId == 0L) return@collect
            markAsRead(event.channelId)
            Log.d(TAG, "markAsRead channelId=${event.channelId}")
        }
    }
}
