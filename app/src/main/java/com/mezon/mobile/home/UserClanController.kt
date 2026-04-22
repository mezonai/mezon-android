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

    // --- Per-clan members (RN: clan.members.ts / fetchUsersClan) ---
    private val membersByClan = LongSparseArray<ArrayList<ClanMember>>()

    @Synchronized
    fun getClanMembers(clanId: Long): List<ClanMember> {
        return membersByClan[clanId]?.let { ArrayList(it) } ?: emptyList()
    }

    @Synchronized
    fun getClanMemberCount(clanId: Long): Int {
        return membersByClan[clanId]?.size ?: 0
    }

    @Synchronized
    fun hasClanMembersCache(clanId: Long): Boolean {
        return membersByClan.indexOfKey(clanId) >= 0
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

    private val membersByChannel = LongSparseArray<ArrayList<ClanMember>>()

    @Synchronized
    fun getChannelMembers(channelId: Long): List<ClanMember> {
        return membersByChannel[channelId]?.let { ArrayList(it) } ?: emptyList()
    }

    fun loadChannelMembers(clanId: Long, channelId: Long, channelType: Int, noCache: Boolean = false) {
        if (channelId == 0L) return
        appScope.launch(ioDispatcher) {
            try {
                val cacheKey = apiCacheKey("listChannelUsers", clanId.toString(), channelId.toString())
                val hasCache: Boolean
                synchronized(this@UserClanController) { hasCache = membersByChannel[channelId] != null }

                if (hasCache && cacheTracker.shouldCall(cacheKey, noCache = noCache) == ApiCacheTracker.ShouldCall.SKIP) return@launch

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
