package com.mezon.mobile.home

import android.util.Log
import android.util.LongSparseArray
import com.mezon.mezon.api.AllUsersAddChannelResponse
import com.mezon.mezon.api.ClanUserList
import com.mezon.mezon.api.User
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.session.SessionManager
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
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

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
            }
        }
    }

    private val membersByChannel = LongSparseArray<List<ClanMember>>()
    private val directMembersByChannel = LongSparseArray<List<ClanMember>>()

    fun getChannelMembers(channelId: Long): List<ClanMember> {
        synchronized(this) { return membersByChannel[channelId] ?: emptyList() }
    }

    fun getDirectChannelMembers(channelId: Long): List<ClanMember> {
        synchronized(this) { return directMembersByChannel[channelId] ?: emptyList() }
    }

    fun hasDirectChannelMembersLoaded(channelId: Long): Boolean = synchronized(this) {
        directMembersByChannel.indexOfKey(channelId) >= 0
    }

    fun hasChannelMembersLoaded(channelId: Long): Boolean = synchronized(this) {
        membersByChannel.indexOfKey(channelId) >= 0
    }

    fun loadChannelMembers(clanId: Long, channelId: Long, channelType: Int, noCache: Boolean = false) {
        if (channelId == 0L) return
        appScope.launch(ioDispatcher) {
            try {
                val cacheKey = apiCacheKey("listChannelUsers", clanId.toString(), channelId.toString())
                val hasCache: Boolean
                synchronized(this@UserClanController) { hasCache = membersByChannel[channelId] != null }

                if (hasCache && cacheTracker.shouldCall(cacheKey, noCache = noCache) == ApiCacheTracker.ShouldCall.SKIP) {
                    Log.d(TAG, "loadChannelMembers skip cache clanId=$clanId channelId=$channelId type=$channelType")
                    return@launch
                }
                Log.d(TAG, "loadChannelMembers request clanId=$clanId channelId=$channelId type=$channelType")

                sessionManager.withAutoRefresh { session ->
                    val response = api.listChannelUsers(session.apiUrl, session.token, clanId, channelId, channelType)
                    val channelUsers = response.channelUsersList
                    Log.d(TAG, "loadChannelMembers response clanId=$clanId channelId=$channelId type=$channelType count=${channelUsers.size}")

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

    fun loadDirectChannelMembers(clanId: Long, channelId: Long, noCache: Boolean = false) {
        if (clanId == 0L || channelId == 0L) return
        appScope.launch(ioDispatcher) {
            try {
                val cacheKey = apiCacheKey("listChannelUsersUC", channelId.toString())
                val hasCache: Boolean
                synchronized(this@UserClanController) { hasCache = directMembersByChannel[channelId] != null }

                if (hasCache && cacheTracker.shouldCall(cacheKey, noCache = noCache) == ApiCacheTracker.ShouldCall.SKIP) {
                    return@launch
                }

                sessionManager.withAutoRefresh { session ->
                    val response = api.listChannelUsersUC(session.apiUrl, session.token, channelId)
                    val members = response.toDirectChannelMembers(clanId)
                    synchronized(this@UserClanController) {
                        directMembersByChannel.put(channelId, members)
                    }

                    cacheTracker.markCalled(cacheKey)
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.channelMembersDidLoad, channelId
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadDirectChannelMembers failed for channel $channelId", e)
            }
        }
    }

    fun removeDirectChannelMembers(channelId: Long, userIds: Collection<Long>) {
        if (channelId == 0L || userIds.isEmpty()) return
        var changed = false
        synchronized(this) {
            val current = directMembersByChannel[channelId] ?: return
            val removed = userIds.toHashSet()
            val next = current.filterNot { it.userId in removed }
            if (next.size != current.size) {
                directMembersByChannel.put(channelId, next)
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
            directMembersByChannel.clear()
        }
    }

    private fun AllUsersAddChannelResponse.toDirectChannelMembers(clanId: Long): List<ClanMember> {
        val clanMembers = getClanMembers(clanId)
        val clanMemberDict = HashMap<Long, ClanMember>(clanMembers.size)
        for (member in clanMembers) clanMemberDict[member.userId] = member

        val members = ArrayList<ClanMember>(userIdsCount)
        val seen = HashSet<Long>(userIdsCount)
        for (i in 0 until userIdsCount) {
            val userId = getUserIds(i)
            if (userId == 0L || userId in seen) continue
            seen.add(userId)
            val existing = clanMemberDict[userId]
            if (existing != null) {
                members.add(existing)
                continue
            }
            val username = usernamesList.getOrElse(i) { "" }
            val displayName = displayNamesList.getOrElse(i) { "" }
            val avatar = avatarsList.getOrElse(i) { "" }
            members.add(
                ClanMember(
                    userId = userId,
                    username = username,
                    displayName = displayName,
                    avatarUrl = avatar,
                    isOnline = onlinesList.getOrElse(i) { false },
                    clanNick = displayName,
                    clanAvatar = avatar,
                    clanId = clanId,
                    roleIds = emptyList()
                )
            )
        }
        return members
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
