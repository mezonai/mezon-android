package com.mezon.mobile.home

import android.util.Log
import android.util.LongSparseArray
import com.mezon.mezon.api.ClanUserList
import com.mezon.mezon.api.User
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.session.SessionManager
import com.mezon.mezon.rtapi.UserChannelAdded
import com.mezon.mezon.rtapi.UserChannelRemoved
import com.mezon.mezon.rtapi.UserProfileRedis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UserClanController"

data class ClanUser(
    val id: Long,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val isOnline: Boolean
)

data class ClanMember(
    val userId: Long,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val isOnline: Boolean,
    val clanNick: String,
    val clanAvatar: String,
    val clanId: Long,
    val roleIds: List<Long>
)

@Singleton
class UserClanController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val notificationCenter: NotificationCenter,
    private val cacheTracker: ApiCacheTracker,
    private val socketEventDispatcher: SocketEventDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    init {
        appScope.launch { observeUserChannelAdded() }
        appScope.launch { observeUserChannelRemoved() }
    }

    // --- All users across all clans (for search) ---
    private val users = ArrayList<ClanUser>()
    private val usersDict = LongSparseArray<ClanUser>()
    var loaded = false
        private set

    @Synchronized
    fun getUsers(): List<ClanUser> = ArrayList(users)

    @Synchronized
    fun getUserById(id: Long): ClanUser? = usersDict[id]

    @Synchronized
    fun getUserCount(): Int = users.size

    private fun UserProfileRedis.toClanUser(): ClanUser = ClanUser(
        id = userId,
        username = username,
        displayName = displayName.ifBlank { username },
        avatarUrl = avatar,
        isOnline = online
    )

    private fun UserProfileRedis.toClanMember(clanId: Long): ClanMember = ClanMember(
        userId = userId,
        username = username,
        displayName = displayName.ifBlank { username },
        avatarUrl = avatar,
        isOnline = online,
        clanNick = "",
        clanAvatar = "",
        clanId = clanId,
        roleIds = emptyList()
    )

    fun loadUsers(noCache: Boolean = false) {
        appScope.launch(ioDispatcher) {
            try {
                val cacheKey = apiCacheKey("listUserClansByUserId")
                val hasCache: Boolean
                synchronized(this@UserClanController) { hasCache = users.isNotEmpty() }

                if (hasCache && cacheTracker.shouldCall(cacheKey, noCache = noCache) == ApiCacheTracker.ShouldCall.SKIP) return@launch

                sessionManager.withAutoRefresh { session ->
                    val response = api.listUserClansByUserId(session.apiUrl, session.token)
                    val apiUsers = response.usersList

                    val deduped = ArrayList<ClanUser>(apiUsers.size)
                    val seen = HashSet<Long>(apiUsers.size)
                    for (u in apiUsers) {
                        if (u.id in seen) continue
                        seen.add(u.id)
                        deduped.add(u.toClanUser())
                    }

                    synchronized(this@UserClanController) {
                        users.clear()
                        users.addAll(deduped)
                        usersDict.clear()
                        for (u in deduped) usersDict.put(u.id, u)
                        loaded = true
                    }

                    cacheTracker.markCalled(cacheKey)
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.userClansDidLoad)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadUsers failed", e)
            }
        }
    }

    private val membersByClan = LongSparseArray<List<ClanMember>>()

    fun getClanMembers(clanId: Long): List<ClanMember> {
        synchronized(this) { return membersByClan[clanId] ?: emptyList() }
    }

    fun getClanMemberCount(clanId: Long): Int {
        synchronized(this) { return membersByClan[clanId]?.size ?: 0 }
    }

    fun hasClanMembersCache(clanId: Long): Boolean {
        synchronized(this) { return membersByClan.indexOfKey(clanId) >= 0 }
    }

    fun clearClanMembersCache(clanId: Long) {
        synchronized(this) {
            val ix = membersByClan.indexOfKey(clanId)
            if (ix >= 0) membersByClan.removeAt(ix)
        }
    }

    fun loadClanMembers(clanId: Long, noCache: Boolean = false) {
        if (clanId == 0L) return
        appScope.launch(ioDispatcher) {
            try {
                val cacheKey = apiCacheKey("listClanUsers", clanId.toString())
                val hasCache: Boolean
                synchronized(this@UserClanController) { hasCache = membersByClan[clanId] != null }

                if (hasCache && cacheTracker.shouldCall(cacheKey, noCache = noCache) == ApiCacheTracker.ShouldCall.SKIP) return@launch

                sessionManager.withAutoRefresh { session ->
                    val response = api.listClanUsers(session.apiUrl, session.token, clanId)
                    val clanUsers = response.clanUsersList

                    val members = ArrayList<ClanMember>(clanUsers.size)
                    val seen = HashSet<Long>(clanUsers.size)
                    for (cu in clanUsers) {
                        val user = cu.user ?: continue
                        if (user.id in seen) continue
                        seen.add(user.id)
                        members.add(cu.toClanMember())
                    }

                    synchronized(this@UserClanController) {
                        membersByClan.put(clanId, members)
                    }

                    cacheTracker.markCalled(cacheKey)
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.clanMembersDidLoad, clanId
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadClanMembers failed for clan $clanId", e)
                synchronized(this@UserClanController) {
                    if (membersByClan[clanId] == null) {
                        membersByClan.put(clanId, ArrayList())
                    }
                }
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.clanMembersDidLoad, clanId
                )
            }
        }
    }

    private val membersByChannel = LongSparseArray<List<ClanMember>>()

    fun getChannelMembers(channelId: Long): List<ClanMember> {
        synchronized(this) { return membersByChannel[channelId] ?: emptyList() }
    }

    fun loadChannelMembers(clanId: Long, channelId: Long, channelType: Int, noCache: Boolean = false) {
        if (channelId == 0L) return
        appScope.launch(ioDispatcher) {
            try {
                val cacheKey = apiCacheKey("listChannelUsers", clanId.toString(), channelId.toString())
                val hasCache: Boolean
                synchronized(this@UserClanController) { hasCache = membersByChannel[channelId] != null }

                if (hasCache && cacheTracker.shouldCall(cacheKey, noCache = noCache) == ApiCacheTracker.ShouldCall.SKIP) {
                    return@launch
                }

                sessionManager.withAutoRefresh { session ->
                    val response = api.listChannelUsers(session.apiUrl, session.token, clanId, channelId, channelType)
                    val channelUsers = response.channelUsersList

                    val clanMembers = getClanMembers(clanId)
                    val clanMemberDict = HashMap<Long, ClanMember>(clanMembers.size)
                    for (m in clanMembers) clanMemberDict[m.userId] = m

                    val members = ArrayList<ClanMember>(channelUsers.size)
                    val seen = HashSet<Long>(channelUsers.size)
                    for (cu in channelUsers) {
                        if (cu.userId in seen) continue
                        seen.add(cu.userId)
                        val existing = clanMemberDict[cu.userId]
                        if (existing != null) {
                            members.add(existing)
                        } else {
                            members.add(ClanMember(
                                userId = cu.userId,
                                username = "",
                                displayName = cu.clanNick.ifBlank { "" },
                                avatarUrl = cu.clanAvatar,
                                isOnline = false,
                                clanNick = cu.clanNick,
                                clanAvatar = cu.clanAvatar,
                                clanId = clanId,
                                roleIds = cu.roleIdList
                            ))
                        }
                    }

                    synchronized(this@UserClanController) {
                        membersByChannel.put(channelId, members)
                    }

                    cacheTracker.markCalled(cacheKey)
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.channelMembersDidLoad, channelId
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadChannelMembers failed for channel $channelId", e)
            }
        }
    }

    private suspend fun observeUserChannelAdded() {
        socketEventDispatcher.userChannelAddedEvents.collect { event ->
            applyUserChannelAdded(event)
        }
    }

    private suspend fun observeUserChannelRemoved() {
        socketEventDispatcher.userChannelRemovedEvents.collect { event ->
            applyUserChannelRemoved(event)
        }
    }

    private fun applyUserChannelAdded(event: UserChannelAdded) {
        if (!event.hasChannelDesc()) return
        val channelId = event.channelDesc.channelId
        if (channelId == 0L) return
        val clanId = event.clanId.takeIf { it != 0L } ?: event.channelDesc.clanId
        val incomingUsers = event.usersList
        if (incomingUsers.isEmpty()) return
        val incomingMembers = incomingUsers.map { it.toClanMember(clanId) }
        var channelMembersChanged = false
        var clanMembersChanged = false
        synchronized(this) {
            for (user in incomingUsers) {
                val clanUser = user.toClanUser()
                usersDict.put(clanUser.id, clanUser)
                val idx = users.indexOfFirst { it.id == clanUser.id }
                if (idx >= 0) users[idx] = clanUser else users.add(clanUser)
            }
            loaded = users.isNotEmpty()
            val channelMembers = membersByChannel[channelId]
            if (channelMembers != null) {
                val merged = LinkedHashMap<Long, ClanMember>(channelMembers.size + incomingMembers.size)
                for (m in channelMembers) merged[m.userId] = m
                for (m in incomingMembers) merged[m.userId] = m
                membersByChannel.put(channelId, ArrayList(merged.values))
                channelMembersChanged = true
            }
            val clanMembers = membersByClan[clanId]
            if (clanId != 0L && clanMembers != null) {
                val merged = LinkedHashMap<Long, ClanMember>(clanMembers.size + incomingMembers.size)
                for (m in clanMembers) merged[m.userId] = m
                for (m in incomingMembers) merged[m.userId] = m
                membersByClan.put(clanId, ArrayList(merged.values))
                clanMembersChanged = true
            }
        }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.userClansDidLoad)
        if (channelMembersChanged) {
            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelMembersDidLoad, channelId)
        }
        if (clanMembersChanged) {
            notificationCenter.postNotificationOnMainThread(NotificationCenter.clanMembersDidLoad, clanId)
        }
    }

    private fun applyUserChannelRemoved(event: UserChannelRemoved) {
        val channelId = event.channelId
        if (channelId == 0L) return
        val removedIds = event.userIdsList.toSet()
        if (removedIds.isEmpty()) return
        var changed = false
        synchronized(this) {
            val members = membersByChannel[channelId] ?: return@synchronized
            val updated = members.filter { it.userId !in removedIds }
            if (updated.size != members.size) {
                membersByChannel.put(channelId, updated)
                changed = true
            }
        }
        if (changed) {
            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelMembersDidLoad, channelId)
        }
    }

    fun cleanup() {
        synchronized(this) {
            users.clear()
            usersDict.clear()
            loaded = false
            membersByClan.clear()
            membersByChannel.clear()
        }
    }
}

private fun User.toClanUser(): ClanUser = ClanUser(
    id = id,
    username = username,
    displayName = displayName,
    avatarUrl = avatarUrl,
    isOnline = online
)

private fun ClanUserList.ClanUser.toClanMember(): ClanMember = ClanMember(
    userId = user.id,
    username = user.username,
    displayName = user.displayName,
    avatarUrl = user.avatarUrl,
    isOnline = user.online,
    clanNick = clanNick,
    clanAvatar = clanAvatar,
    clanId = clanId,
    roleIds = roleIdList
)
