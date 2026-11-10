package com.mezon.mobile.home.friends

import com.mezon.mezon.api.Friend
import com.mezon.mezon.api.User
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.os.SystemClock
import android.widget.Toast
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.mezon.mobile.R
import com.mezon.mobile.home.notifications.toNotificationEntity
import javax.inject.Inject
import javax.inject.Singleton

private const val FRIEND_RELATIONS_FOREGROUND_THROTTLE_MS = 30_000L
private const val FRIEND_RELATIONS_NOTIFICATION_DEBOUNCE_MS = 5_000L
private const val NOTIFICATION_CODE_FRIEND_ACCEPT = -3

@Singleton
class FriendController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val userController: UserController,
    private val dispatcher: SocketEventDispatcher,
    private val notificationCenter: NotificationCenter,
    private val cacheTracker: ApiCacheTracker,
    @ApplicationContext private val appContext: Context,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val _blockedUsers = MutableStateFlow<List<Friend>>(emptyList())
    val blockedUsers: StateFlow<List<Friend>> = _blockedUsers.asStateFlow()
    private val blockedRelations = MutableStateFlow<List<Friend>>(emptyList())

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
    private val friendRelationsMutex = Mutex()
    private var lastFriendRelationsForegroundRefreshElapsedMs = 0L
    private var lastFriendRelationsNotificationRefreshElapsedMs = 0L

    private val listFriendsCombinedCacheKey = apiCacheKey("listFriends", "all")
    private val friendsCacheKey = apiCacheKey("listFriends", 0)
    private val friendInviteSentCacheKey = apiCacheKey("listFriends", 1)
    private val friendInviteReceivedCacheKey = apiCacheKey("listFriends", 2)
    private val blockedCacheKey = apiCacheKey("listFriends", 3)

    init {
        appScope.launch { observeFriendRelationNotifications() }
        appScope.launch {
            dispatcher.friendBlockChanges.collect {
                loadFriendRelations(noCache = true)
            }
        }
    }

    val currentUsername: String
        get() = userController.username

    private val activeUserId: Long
        get() = userController.userId.takeIf { it > 0L } ?: StartupCache.userId.toLongOrNull() ?: 0L

    private suspend fun observeFriendRelationNotifications() {
        dispatcher.notifications.collect { notification ->
            val subject = notification.subject.lowercase()
            val content = notification.content.toStringUtf8().lowercase()
            val hasFriendSignal = notificationSuggestsFriendRelationRefresh(subject, content) || notification.code == NOTIFICATION_CODE_FRIEND_ACCEPT
            if (hasFriendSignal) {
                if (notification.code == NOTIFICATION_CODE_FRIEND_ACCEPT) {
                    val name = notification.toNotificationEntity().senderName.ifEmpty { "Someone" }
                    withContext(Dispatchers.Main) {
                        val activity = com.mezon.mobile.MainActivity.instance ?: return@withContext
                        activity.drawerLayoutContainer.post {
                            com.mezon.mobile.ui.cells.ToastOverlay(activity, activity.themeColors).show(
                                activity.drawerLayoutContainer,
                                com.mezon.mobile.ui.cells.ToastOverlay.ToastType.INFO,
                                appContext.getString(R.string.friend_request_accepted, name)
                            )
                        }
                    }
                }
                val now = SystemClock.elapsedRealtime()
                val shouldRefresh = synchronized(friendRelationsForegroundThrottleLock) {
                    val last = lastFriendRelationsNotificationRefreshElapsedMs
                    if (last != 0L && now - last < FRIEND_RELATIONS_NOTIFICATION_DEBOUNCE_MS) {
                        false
                    } else {
                        lastFriendRelationsNotificationRefreshElapsedMs = now
                        true
                    }
                }
                if (shouldRefresh) {
                    loadFriendRelations(noCache = true)
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
            friendRelationsMutex.withLock {
                if (cacheTracker.shouldCall(listFriendsCombinedCacheKey, noCache = noCache) == ApiCacheTracker.ShouldCall.SKIP) {
                    return@withLock
                }
                try {
                    refreshFriendRelations()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun applyFriendListsPartitioned(all: List<Friend>, currentUserId: Long) {
        val blocks = all.asSequence().filter { it.state == FRIEND_STATE_BLOCKED && it.user.id > 0L }
            .mapNotNull { relation ->
                if (relation.user.id != currentUserId) {
                    relation
                } else if (relation.sourceId > 0L && relation.sourceId != currentUserId) {
                    // An incoming block can carry our profile. Its source is the peer.
                    relation.toBuilder().setUser(User.newBuilder().setId(relation.sourceId).build()).build()
                } else {
                    null
                }
            }
            .distinctBy { it.user.id to it.sourceId }
            .toList()
        val blockedIds = blocks.mapTo(HashSet()) { it.user.id }
        val available = all.filter { it.user.id !in blockedIds }
        _friends.value = available.filter { it.state == FRIEND_STATE_FRIEND }
        _sentFriendRequests.value = available.filter { it.state == FRIEND_STATE_INVITE_SENT }
        _receivedFriendRequests.value = available.filter { it.state == FRIEND_STATE_INVITE_RECEIVED }
        applyBlockedRelations(blocks, currentUserId)
    }

    private fun applyBlockedRelations(relations: List<Friend>, currentUserId: Long) {
        blockedRelations.value = relations
        _blockedUsers.value = relations.filter { it.sourceId == currentUserId }
    }

    fun sendFriendRequest(
        userId: Long = 0L,
        username: String = "",
        onError: (Throwable) -> Unit = {},
        onResult: (success: Boolean) -> Unit
    ) {
        requestFriendRelationUpdate(
            userId = userId,
            username = username,
            onRequest = { session, ids, usernames ->
                api.addFriends(session.apiUrl, session.token, ids, usernames)
            },
            onError = onError,
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

    fun deleteFriendRelation(
        userId: Long = 0L,
        username: String = "",
        onError: (Throwable) -> Unit = {},
        onResult: (success: Boolean) -> Unit
    ) {
        requestFriendRelationUpdate(
            userId = userId,
            username = username,
            onRequest = { session, ids, usernames ->
                api.deleteFriends(session.apiUrl, session.token, ids, usernames)
            },
            onError = onError,
            onResult = onResult
        )
    }

    fun canBlockUser(userId: Long): Boolean =
        activeUserId.let { it > 0L && userId > 0L && userId != it }

    fun setUserBlocked(user: User, blocked: Boolean, onResult: (success: Boolean) -> Unit) {
        val currentUserId = activeUserId
        appScope.launch {
            var success = false
            try {
                success = friendRelationsMutex.withLock {
                    if (!canBlockUser(user.id) || activeUserId != currentUserId) return@withLock false
                    try {
                        sessionManager.withAutoRefresh { session ->
                            check(session.userId.toLongOrNull() == currentUserId)
                            withContext(ioDispatcher) {
                                val ids = listOf(user.id)
                                if (blocked) {
                                    api.blockFriends(session.apiUrl, session.token, ids, emptyList())
                                } else {
                                    api.unblockFriends(session.apiUrl, session.token, ids, emptyList())
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        return@withLock false
                    }
                    if (activeUserId != currentUserId) return@withLock false
                    updateLocalBlock(user, blocked, currentUserId)
                    success = true
                    try {
                        invalidateFriendRelationCachesAndRefresh()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // The server accepted the mutation. Keep the confirmed local state.
                    }
                    activeUserId == currentUserId
                }
            } finally {
                // Complete the UI callback even if the request coroutine was cancelled.
                withContext(NonCancellable + Dispatchers.Main) { onResult(success) }
            }
        }
    }

    private fun updateLocalBlock(user: User, blocked: Boolean, currentUserId: Long) {
        val updated = blockedRelations.value.filterNot { it.user.id == user.id && it.sourceId == currentUserId }
        if (blocked) {
            val cachedUser = findFriendByUserId(user.id)?.user
            val profile = user.toBuilder().apply {
                if (username.isBlank()) username = cachedUser?.username.orEmpty()
                if (displayName.isBlank()) displayName = cachedUser?.displayName.orEmpty()
                if (avatarUrl.isBlank()) avatarUrl = cachedUser?.avatarUrl.orEmpty()
            }.build()
            val relation = Friend.newBuilder()
                .setUser(profile).setState(FRIEND_STATE_BLOCKED).setSourceId(currentUserId).build()
            applyBlockedRelations(updated + relation, currentUserId)
            _friends.value = _friends.value.filterNot { it.user.id == user.id }
            _sentFriendRequests.value = _sentFriendRequests.value.filterNot { it.user.id == user.id }
            _receivedFriendRequests.value = _receivedFriendRequests.value.filterNot { it.user.id == user.id }
        } else {
            // Only ListFriends can tell if there is still a friendship or an incoming block.
            applyBlockedRelations(updated, currentUserId)
        }
        publishFriendRelations()
    }

    fun isUserBlockedByMe(userId: Long): Boolean {
        if (userId == 0L) return false
        return _blockedUsers.value.any { it.user.id == userId }
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

        refreshFriendRelations()
    }

    private suspend fun refreshFriendRelations() {
        val (currentUserId, all) = sessionManager.withAutoRefresh { session ->
            val currentUserId = session.userId.toLong()
            val all = withContext(ioDispatcher) { api.listFriendsAll(session.apiUrl, session.token).friendsList }
            currentUserId to all
        }
        if (activeUserId != currentUserId) return
        applyFriendListsPartitioned(all, currentUserId)
        cacheTracker.markCalled(listFriendsCombinedCacheKey)
        publishFriendRelations()
    }

    private fun publishFriendRelations() {
        publishAllFriendRelations()
        notificationCenter.postNotificationOnMainThread(NotificationCenter.friendsLoaded)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.blockedUsersLoaded)
    }

    private fun requestFriendRelationUpdate(
        userId: Long,
        username: String,
        onRequest: suspend (session: com.mezon.mobile.session.StoredSession, ids: List<Long>, usernames: List<String>) -> Any,
        onError: (Throwable) -> Unit = {},
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

                friendRelationsMutex.withLock { invalidateFriendRelationCachesAndRefresh() }
                withContext(Dispatchers.Main) { onResult(true) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e)
                    onResult(false)
                }
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
        for (friend in blockedRelations.value) {
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

    fun isUserBlocked(userId: Long): Boolean =
        blockedRelations.value.any { it.user.id == userId }

    fun isFriend(userId: Long): Boolean =
        _friends.value.any { it.user.id == userId }

    fun cleanup() {
        _blockedUsers.value = emptyList()
        blockedRelations.value = emptyList()
        _friends.value = emptyList()
        _sentFriendRequests.value = emptyList()
        _receivedFriendRequests.value = emptyList()
        _allFriendRelations.value = emptyList()
        _pendingReceivedCount.value = 0
        synchronized(friendRelationsForegroundThrottleLock) {
            lastFriendRelationsForegroundRefreshElapsedMs = 0L
        }
    }
}
