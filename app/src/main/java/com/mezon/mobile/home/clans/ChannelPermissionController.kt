package com.mezon.mobile.home.clans

import android.os.SystemClock
import android.util.Log
import com.mezon.mezon.api.PermissionUpdate
import com.mezon.mezon.api.permissionUpdate
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ChannelPermissionCtrl"
private const val ADD_ROLE_CHANNEL_STATUS = "Add Role Channel"
private const val PERMISSION_SET_REFETCH_DELAY_MS = 700L
private const val RECENT_PERMISSION_CHANGED_WINDOW_MS = 1_500L

const val CHANNEL_PERMISSION_TARGET_ROLE = 0
const val CHANNEL_PERMISSION_TARGET_MEMBER = 1
const val CHANNEL_PERMISSION_STATUS_NONE = 0
const val CHANNEL_PERMISSION_STATUS_ALLOW = 1
const val CHANNEL_PERMISSION_STATUS_DENY = 2

data class ChannelPermissionOverride(
    val permissionId: Long,
    val active: Boolean,
)

data class ChannelPermissionUpdate(
    val permissionId: Long,
    val slug: String,
    val type: Int,
)

@Singleton
class ChannelPermissionController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val roleController: RoleController,
    private val userClanController: UserClanController,
    private val channelController: ChannelController,
    private val clansController: ClansController,
    private val userController: UserController,
    private val notificationCenter: NotificationCenter,
    private val socketEventDispatcher: SocketEventDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val userPermissionsByChannel = HashMap<Long, Map<String, Boolean>>()
    private val userPermissionLoadingChannels = ConcurrentHashMap.newKeySet<Long>()
    private val permissionSetRefetchJobs = ConcurrentHashMap<Long, Job>()
    private val lastPermissionChangedAtMs = ConcurrentHashMap<Long, Long>()

    init {
        observeRealtimeEvents()
    }

    fun ensureUserPermissionsInChannel(clanId: Long, channelId: Long, force: Boolean = false) {
        if (channelId == 0L) return
        val hasCached = synchronized(this) { userPermissionsByChannel.containsKey(channelId) }
        if (!force && hasCached) return
        if (!userPermissionLoadingChannels.add(channelId)) return
        appScope.launch(ioDispatcher) {
            try {
                val permissions = fetchUserPermissionsInChannel(clanId, channelId)
                synchronized(this@ChannelPermissionController) {
                    userPermissionsByChannel[channelId] = permissions
                }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelPermissionsDidLoad, channelId)
            } catch (e: Exception) {
                Log.e(TAG, "ensureUserPermissionsInChannel failed", e)
            } finally {
                userPermissionLoadingChannels.remove(channelId)
            }
        }
    }

    suspend fun refreshUserPermissionsInChannel(clanId: Long, channelId: Long): Result<Map<String, Boolean>> =
        withContext(ioDispatcher) {
            try {
                if (channelId == 0L) return@withContext Result.success(emptyMap())
                val permissions = fetchUserPermissionsInChannel(clanId, channelId)
                synchronized(this@ChannelPermissionController) {
                    userPermissionsByChannel[channelId] = permissions
                }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelPermissionsDidLoad, channelId)
                Result.success(permissions)
            } catch (e: Exception) {
                Log.e(TAG, "refreshUserPermissionsInChannel failed", e)
                Result.failure(e)
            }
        }

    fun hasUserPermissionInChannel(channelId: Long, slug: String): Boolean = synchronized(this) {
        userPermissionsByChannel[channelId]?.get(slug) == true
    }

    fun hasCachedChannelUserPermissions(channelId: Long): Boolean = synchronized(this) {
        userPermissionsByChannel.containsKey(channelId)
    }

    fun invalidateUserPermissionsInChannel(channelId: Long): Boolean = synchronized(this) {
        userPermissionsByChannel.remove(channelId) != null
    }

    fun cleanup() {
        synchronized(this) {
            userPermissionsByChannel.clear()
        }
        userPermissionLoadingChannels.clear()
        permissionSetRefetchJobs.values.forEach { it.cancel() }
        permissionSetRefetchJobs.clear()
        lastPermissionChangedAtMs.clear()
    }

    fun loadChannelPermissionData(clanId: Long, channelId: Long, channelType: Int, force: Boolean = false) {
        if (channelId == 0L) return
        roleController.loadPermissionCatalogIfNeeded()
        if (clanId != 0L) {
            userClanController.loadClanMembers(clanId, noCache = force)
            userClanController.loadDirectChannelMembers(clanId, channelId, noCache = force)
            roleController.loadRolesForClan(clanId, force = force)
            roleController.loadUserMaxPermissionForClan(clanId, force = force)
        }
        ensureUserPermissionsInChannel(clanId, channelId, force = force)
    }

    fun getChannelRoles(clanId: Long, channelId: Long): List<ClanRole> {
        return roleController.getRoles(clanId).filter { role ->
            !role.isEveryoneRole() && role.isAssignedToChannel(channelId)
        }
    }

    fun getAvailableRoles(clanId: Long, channelId: Long): List<ClanRole> {
        return roleController.getRoles(clanId).filter { role ->
            !role.isEveryoneRole() && !role.isAssignedToChannel(channelId)
        }
    }

    fun maxPermissionIdForCurrentUser(clanId: Long): Long {
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId } ?: return 0L
        val members = userClanController.getClanMembers(clanId)
        return roleController.maxPermissionRoleIdForSelf(clanId, userController.userId, members, clan.creatorId)
    }

    suspend fun updateChannelPrivate(
        clanId: Long,
        channelId: Long,
        channelType: Int,
        isPrivate: Boolean,
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            sessionManager.withAutoRefresh { session ->
                api.updateChannelPrivate(
                    session.apiUrl,
                    session.token,
                    clanId,
                    channelId,
                    if (isPrivate) 1 else 0,
                    listOfNotNull(userController.userId.takeIf { it != 0L }),
                    emptyList(),
                )
            }
            channelController.findChannelById(channelId, clanId)?.let { existing ->
                channelController.upsertChannel(existing.copy(isPrivate = isPrivate))
            }
            loadChannelPermissionData(clanId, channelId, channelType, force = true)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelPermissionsDidLoad, channelId)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "updateChannelPrivate failed", e)
            Result.failure(e)
        }
    }

    suspend fun addMembers(clanId: Long, channelId: Long, channelType: Int, userIds: List<Long>): Result<Unit> =
        withContext(ioDispatcher) {
            try {
                if (userIds.isEmpty()) return@withContext Result.success(Unit)
                sessionManager.withAutoRefresh { session ->
                    api.addChannelUsers(session.apiUrl, session.token, channelId, userIds)
                }
                userClanController.loadDirectChannelMembers(clanId, channelId, noCache = true)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelPermissionsDidLoad, channelId)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "addMembers failed", e)
                Result.failure(e)
            }
        }

    suspend fun removeMember(clanId: Long, channelId: Long, channelType: Int, userId: Long): Result<Unit> =
        withContext(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
                    api.removeChannelUsers(session.apiUrl, session.token, channelId, listOf(userId))
                }
                userClanController.removeDirectChannelMembers(channelId, listOf(userId))
                userClanController.loadDirectChannelMembers(clanId, channelId, noCache = true)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelPermissionsDidLoad, channelId)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "removeMember failed", e)
                Result.failure(e)
            }
        }

    suspend fun addRoles(clanId: Long, channelId: Long, roleIds: List<Long>): Result<Unit> =
        withContext(ioDispatcher) {
            try {
                if (roleIds.isEmpty()) return@withContext Result.success(Unit)
                sessionManager.withAutoRefresh { session ->
                    api.addRoleChannelDesc(session.apiUrl, session.token, channelId, roleIds)
                }
                roleController.addChannelToRoles(clanId, channelId, roleIds)
                roleController.loadRolesForClan(clanId, force = true)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelPermissionsDidLoad, channelId)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "addRoles failed", e)
                Result.failure(e)
            }
        }

    suspend fun removeRole(clanId: Long, channelId: Long, role: ClanRole): Result<Unit> =
        withContext(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
                    api.deleteRoleChannelDesc(session.apiUrl, session.token, clanId, channelId, role.roleId, role.title)
                }
                roleController.removeChannelFromRole(clanId, channelId, role.roleId)
                roleController.loadRolesForClan(clanId, force = true)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelPermissionsDidLoad, channelId)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "removeRole failed", e)
                Result.failure(e)
            }
        }

    suspend fun fetchOverrides(channelId: Long, roleId: Long, userId: Long): Result<List<ChannelPermissionOverride>> =
        withContext(ioDispatcher) {
            try {
                val response = sessionManager.withAutoRefresh { session ->
                    api.getPermissionByRoleIdChannelId(session.apiUrl, session.token, roleId, channelId, userId)
                }
                val overrides = response.permissionRoleChannelList.map {
                    ChannelPermissionOverride(it.permissionId, it.active)
                }
                Result.success(overrides)
            } catch (e: Exception) {
                Log.e(TAG, "fetchOverrides failed", e)
                Result.failure(e)
            }
        }

    suspend fun setOverrides(
        channelId: Long,
        roleId: Long,
        userId: Long,
        maxPermissionId: Long,
        updates: List<ChannelPermissionUpdate>,
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            val protoUpdates = updates.map { item ->
                permissionUpdate {
                    permissionId = item.permissionId
                    slug = item.slug
                    type = item.type
                }
            }
            sessionManager.withAutoRefresh { session ->
                api.setRoleChannelPermission(
                    session.apiUrl,
                    session.token,
                    channelId,
                    roleId,
                    userId,
                    maxPermissionId,
                    protoUpdates,
                )
            }
            invalidateAndRefetchUserPermissions(channelId, force = true)
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.channelPermissionOverridesDidLoad,
                channelId,
                roleId,
                userId,
                updates,
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "setOverrides failed", e)
            Result.failure(e)
        }
    }

    private suspend fun fetchUserPermissionsInChannel(clanId: Long, channelId: Long): Map<String, Boolean> {
        val response = sessionManager.withAutoRefresh { session ->
            api.listUserPermissionInChannel(session.apiUrl, session.token, clanId, channelId)
        }
        val result = HashMap<String, Boolean>()
        response.permissions.permissionsList.forEach { permission ->
            result[permission.slug] = permission.active != 0
        }
        return result
    }

    private fun invalidateAndRefetchUserPermissions(channelId: Long, force: Boolean) {
        if (channelId == 0L) return
        val hadCache = invalidateUserPermissionsInChannel(channelId)
        val clanId = channelController.findChannelById(channelId)?.clanId?.takeIf { it != 0L }
            ?: clansController.selectedClanId.value
        if (force || hadCache) {
            ensureUserPermissionsInChannel(clanId, channelId, force = true)
        } else {
            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelPermissionsDidLoad, channelId)
        }
    }

    private fun List<PermissionUpdate>.toChannelPermissionUpdates(): List<ChannelPermissionUpdate> =
        map { item ->
            ChannelPermissionUpdate(
                permissionId = item.permissionId,
                slug = item.slug,
                type = item.type,
            )
        }

    private fun applyPermissionChangedEventToCache(
        channelId: Long,
        addPermissions: List<PermissionUpdate>,
        removePermissions: List<PermissionUpdate>,
        defaultPermissions: List<PermissionUpdate>,
    ) {
        synchronized(this) {
            val existing = userPermissionsByChannel[channelId]
            val updated = HashMap(existing.orEmpty())
            addPermissions.forEach { item ->
                if (item.slug.isNotBlank()) updated[item.slug] = true
            }
            removePermissions.forEach { item ->
                if (item.slug.isNotBlank()) updated[item.slug] = false
            }
            defaultPermissions.forEach { item ->
                if (item.slug.isNotBlank()) updated[item.slug] = item.slug == PermissionPolicy.SEND_MESSAGE
            }
            userPermissionsByChannel[channelId] = updated
        }
    }

    private fun schedulePermissionSetRefetch(channelId: Long) {
        permissionSetRefetchJobs.remove(channelId)?.cancel()
        val scheduledAt = SystemClock.elapsedRealtime()
        val job = appScope.launch(ioDispatcher) {
            delay(PERMISSION_SET_REFETCH_DELAY_MS)
            val lastChangedAt = lastPermissionChangedAtMs[channelId] ?: 0L
            if (lastChangedAt >= scheduledAt || SystemClock.elapsedRealtime() - lastChangedAt < RECENT_PERMISSION_CHANGED_WINDOW_MS) {
                permissionSetRefetchJobs.remove(channelId)
                return@launch
            }
            invalidateAndRefetchUserPermissions(channelId, force = true)
            permissionSetRefetchJobs.remove(channelId)
        }
        permissionSetRefetchJobs[channelId] = job
    }

    private fun ClanRole.isAssignedToChannel(channelId: Long): Boolean {
        if (channelId == 0L) return false
        return if (channelIds.isNotEmpty()) channelId in channelIds else roleChannelActive == 1
    }

    private fun observeRealtimeEvents() {
        appScope.launch {
            socketEventDispatcher.userChannelAddedEvents.collect { event ->
                val channelId = event.channelDesc.channelId
                val clanId = event.clanId.takeIf { it != 0L } ?: event.channelDesc.clanId
                if (channelId == 0L || clanId == 0L) return@collect
                Log.d(
                    TAG,
                    "userChannelAdded channelId=$channelId clanId=$clanId status=${event.status} refetchPerm=true",
                )
                invalidateAndRefetchUserPermissions(channelId, force = true)
                if (event.status == ADD_ROLE_CHANNEL_STATUS) {
                    roleController.loadRolesForClan(clanId, force = true)
                    userClanController.loadDirectChannelMembers(clanId, channelId, noCache = true)
                } else {
                    userClanController.loadDirectChannelMembers(clanId, channelId, noCache = true)
                }
            }
        }
        appScope.launch {
            socketEventDispatcher.userChannelRemovedEvents.collect { event ->
                val channelId = event.channelId
                if (channelId == 0L) return@collect
                userClanController.removeDirectChannelMembers(channelId, event.userIdsList)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelPermissionsDidLoad, channelId)
            }
        }
        appScope.launch {
            socketEventDispatcher.permissionSetEvents.collect { event ->
                if (event.channelId == 0L) return@collect
                val updates = event.permissionUpdatesList.toChannelPermissionUpdates()
                val shouldRefetchCurrentUser = event.roleId != 0L || event.userId == 0L || event.userId == userController.userId
                Log.d(
                    TAG,
                    "permissionSet channelId=${event.channelId} roleId=${event.roleId} userId=${event.userId} updates=${updates.size} scheduleRefetch=$shouldRefetchCurrentUser",
                )
                if (shouldRefetchCurrentUser) {
                    schedulePermissionSetRefetch(event.channelId)
                }
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.channelPermissionOverridesDidLoad,
                    event.channelId,
                    event.roleId,
                    event.userId,
                    updates,
                )
            }
        }
        appScope.launch {
            socketEventDispatcher.permissionChangedEvents.collect { event ->
                if (event.channelId == 0L || event.userId != userController.userId) return@collect
                lastPermissionChangedAtMs[event.channelId] = SystemClock.elapsedRealtime()
                permissionSetRefetchJobs.remove(event.channelId)?.cancel()
                Log.d(
                    TAG,
                    "permissionChanged channelId=${event.channelId} add=${event.addPermissionsCount} remove=${event.removePermissionsCount} default=${event.defaultPermissionsCount}",
                )
                applyPermissionChangedEventToCache(
                    event.channelId,
                    event.addPermissionsList,
                    event.removePermissionsList,
                    event.defaultPermissionsList,
                )
                notificationCenter.postNotificationOnMainThread(NotificationCenter.channelPermissionsDidLoad, event.channelId)
            }
        }
    }
}
