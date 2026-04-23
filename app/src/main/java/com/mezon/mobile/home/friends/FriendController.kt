package com.mezon.mobile.home.friends

import com.mezon.mezon.api.Friend
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

private const val FRIEND_LOG = "FriendController"
private const val FRIEND_RELATIONS_FOREGROUND_THROTTLE_MS = 30_000L

@Singleton
class FriendController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val userController: UserController,
    private val dispatcher: SocketEventDispatcher,
    private val notificationCenter: NotificationCenter,
    private val cacheTracker: ApiCacheTracker,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val _blockedUsers = MutableStateFlow<List<Friend>>(emptyList())
    val blockedUsers: StateFlow<List<Friend>> = _blockedUsers.asStateFlow()

    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    val friends: StateFlow<List<Friend>> = _friends.asStateFlow()

    private val _sentFriendRequests = MutableStateFlow<List<Friend>>(emptyList())
    val sentFriendRequests: StateFlow<List<Friend>> = _sentFriendRequests.asStateFlow()

    private val _receivedFriendRequests = MutableStateFlow<List<Friend>>(emptyList())
    val receivedFriendRequests: StateFlow<List<Friend>> = _receivedFriendRequests.asStateFlow()

    private val _allFriendRelations = MutableStateFlow<List<Friend>>(emptyList())
    val allFriendRelations: StateFlow<List<Friend>> = _allFriendRelations.asStateFlow()

    private val _pendingReceivedCount = MutableStateFlow(0)
    val pendingReceivedCount: StateFlow<Int> = _pendingReceivedCount.asStateFlow()

    private val friendRelationsForegroundThrottleLock = Any()
    private var lastFriendRelationsForegroundRefreshElapsedMs = 0L

    private val listFriendsCombinedCacheKey = apiCacheKey("listFriends", "all")
    private val friendsCacheKey = apiCacheKey("listFriends", 0)
    private val friendInviteSentCacheKey = apiCacheKey("listFriends", 1)
    private val friendInviteReceivedCacheKey = apiCacheKey("listFriends", 2)
    private val blockedCacheKey = apiCacheKey("listFriends", 3)

    init {
        appScope.launch { observeFriendRelationNotifications() }
    }

    val currentUsername: String
        get() = userController.username

    private suspend fun observeFriendRelationNotifications() {
        dispatcher.notifications.collect { notification ->
            val subject = notification.subject.lowercase()
            val content = notification.content.toStringUtf8().lowercase()
            val hasFriendSignal = notificationSuggestsFriendRelationRefresh(subject, content)
            if (hasFriendSignal) {
                loadFriendRelations(noCache = true)
            } else if (BuildConfig.DEBUG) {
                val subj = notification.subject
                if (subj.isNotBlank() || content.isNotBlank()) {
                    Log.d(FRIEND_LOG, "notification skip friend-refresh subj=${subj.take(80)}")
                }
            }
        }
    }

    private fun notificationSuggestsFriendRelationRefresh(subject: String, content: String): Boolean {
        val haystacks = listOf(subject, content)
        val needles = listOf(
            "friend",
            "bạn bè",
            "ban be",
            "kết bạn",
            "ket ban",
            "lời mời",
            "loi moi",
            "invite",
            "friend_request",
            "friendrequest",
            "add friend",
            "addfriend"
        )
        for (h in haystacks) {
            for (n in needles) {
                if (n in h) return true
            }
        }
        return false
    }

    fun loadFriendRelationsOnForegroundThrottled(minIntervalMs: Long = FRIEND_RELATIONS_FOREGROUND_THROTTLE_MS) {
        val now = SystemClock.elapsedRealtime()
        synchronized(friendRelationsForegroundThrottleLock) {
            val last = lastFriendRelationsForegroundRefreshElapsedMs
            if (last != 0L && now - last < minIntervalMs) return
            lastFriendRelationsForegroundRefreshElapsedMs = now
        }
        loadFriendRelations(noCache = true)
    }

    fun loadBlockedUsers(noCache: Boolean = false) {
        loadFriendRelations(noCache)
    }

    fun loadFriends(noCache: Boolean = false) {
        loadFriendRelations(noCache)
    }

    fun loadFriendRelations(noCache: Boolean = false) {
        appScope.launch {
            if (cacheTracker.shouldCall(listFriendsCombinedCacheKey, noCache = noCache) == ApiCacheTracker.ShouldCall.SKIP) {
                if (BuildConfig.DEBUG) {
                    Log.d(FRIEND_LOG, "loadFriendRelations skipped (cache TTL) noCache=$noCache")
                }
                return@launch
            }

            try {
                sessionManager.withAutoRefresh { session ->
                    val all = withContext(ioDispatcher) { api.listFriendsAll(session.apiUrl, session.token).friendsList }
                    applyFriendListsPartitioned(all)
                    cacheTracker.markCalled(listFriendsCombinedCacheKey)
                    publishAllFriendRelations()
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.friendsLoaded)
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.blockedUsersLoaded)
                    if (BuildConfig.DEBUG) {
                        val recv = _receivedFriendRequests.value
                        val preview = recv.take(5).joinToString { "${it.user.id}:${it.state}" }
                        Log.d(
                            FRIEND_LOG,
                            "listFriends combined total=${all.size} received=${recv.size} sample=[$preview]"
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun applyFriendListsPartitioned(all: List<Friend>) {
        _friends.value = all.filter { it.state == FRIEND_STATE_FRIEND }
        _sentFriendRequests.value = all.filter { it.state == FRIEND_STATE_INVITE_SENT }
        _receivedFriendRequests.value = all.filter { it.state == FRIEND_STATE_INVITE_RECEIVED }
        _blockedUsers.value = all.filter { it.state == FRIEND_STATE_BLOCKED }
    }

    fun sendFriendRequest(userId: Long = 0L, username: String = "", onResult: (success: Boolean) -> Unit) {
        requestFriendRelationUpdate(
            userId = userId,
            username = username,
            onRequest = { session, ids, usernames ->
                api.addFriends(session.apiUrl, session.token, ids, usernames)
            },
            onResult = onResult
        )
    }

    fun acceptFriendRequest(userId: Long = 0L, username: String = "", onResult: (success: Boolean) -> Unit) {
        requestFriendRelationUpdate(
            userId = userId,
            username = username,
            onRequest = { session, ids, usernames ->
                api.addFriends(session.apiUrl, session.token, ids, usernames)
            },
            onResult = onResult
        )
    }

    fun deleteFriendRelation(userId: Long = 0L, username: String = "", onResult: (success: Boolean) -> Unit) {
        requestFriendRelationUpdate(
            userId = userId,
            username = username,
            onRequest = { session, ids, usernames ->
                api.deleteFriends(session.apiUrl, session.token, ids, usernames)
            },
            onResult = onResult
        )
    }

    fun unblockUser(userId: Long, username: String, onResult: (success: Boolean) -> Unit) {
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.unblockFriends(session.apiUrl, session.token, listOf(userId), listOf(username))
                    }
                }
                _blockedUsers.value = _blockedUsers.value.filter { it.user.id != userId }
                publishAllFriendRelations()
                notificationCenter.postNotificationOnMainThread(NotificationCenter.blockedUsersLoaded)
                withContext(Dispatchers.Main) { onResult(true) }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    fun findFriendByUserId(userId: Long): Friend? {
        return _allFriendRelations.value.firstOrNull { it.user.id == userId }
    }

    fun findFriendByUsername(username: String): Friend? {
        if (username.isBlank()) return null
        return _allFriendRelations.value.firstOrNull { it.user.username.equals(username, ignoreCase = true) }
    }

    private suspend fun invalidateFriendRelationCachesAndRefresh() {
        cacheTracker.invalidate(listFriendsCombinedCacheKey)
        cacheTracker.invalidate(friendsCacheKey)
        cacheTracker.invalidate(friendInviteSentCacheKey)
        cacheTracker.invalidate(friendInviteReceivedCacheKey)
        cacheTracker.invalidate(blockedCacheKey)

        val all = sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) { api.listFriendsAll(session.apiUrl, session.token).friendsList }
        }
        applyFriendListsPartitioned(all)
        publishAllFriendRelations()

        cacheTracker.markCalled(listFriendsCombinedCacheKey)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.friendsLoaded)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.blockedUsersLoaded)
    }

    private fun requestFriendRelationUpdate(
        userId: Long,
        username: String,
        onRequest: suspend (session: com.mezon.mobile.session.StoredSession, ids: List<Long>, usernames: List<String>) -> Any,
        onResult: (success: Boolean) -> Unit
    ) {
        appScope.launch {
            try {
                val ids = if (userId > 0L) listOf(userId) else emptyList()
                val usernames = if (username.isNotBlank()) listOf(username) else emptyList()
                if (ids.isEmpty() && usernames.isEmpty()) {
                    withContext(Dispatchers.Main) { onResult(false) }
                    return@launch
                }

                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        onRequest(session, ids, usernames)
                    }
                }

                invalidateFriendRelationCachesAndRefresh()
                withContext(Dispatchers.Main) { onResult(true) }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    private fun publishAllFriendRelations() {
        val merged = LinkedHashMap<Long, Friend>()
        for (friend in _friends.value) {
            merged[friend.user.id] = friend
        }
        for (friend in _sentFriendRequests.value) {
            merged[friend.user.id] = friend
        }
        for (friend in _receivedFriendRequests.value) {
            merged[friend.user.id] = friend
        }
        for (friend in _blockedUsers.value) {
            merged[friend.user.id] = friend
        }
        _allFriendRelations.value = merged.values.toList()
        _pendingReceivedCount.value = incomingFriendRequestsForUi(
            _receivedFriendRequests.value,
            _friends.value
        ).size
    }
}
