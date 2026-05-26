package com.mezon.mobile.home.clans

import android.util.Log
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.data.db.ClanRoleCacheDao
import com.mezon.mobile.data.db.ClanRoleListMetaDao
import com.mezon.mobile.data.db.ClanRoleListMetaEntity
import com.mezon.mobile.data.db.ClanUserMaxPermissionDao
import com.mezon.mobile.data.db.ClanUserMaxPermissionEntity
import com.mezon.mobile.data.db.PermissionCatalogDao
import com.mezon.mobile.data.db.PermissionCatalogEntity
import com.mezon.mobile.data.db.toCacheEntity
import com.mezon.mobile.data.db.toClanRole
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.di.MainDispatcher
import android.util.LongSparseArray
import com.mezon.mobile.home.ClanMember
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RoleController"

data class UserDisplayRole(
    val color: Int,
    val iconUrl: String,
)

data class MemberProfileRoleChip(
    val roleId: Long,
    val title: String,
    val color: Int,
    val iconUrl: String,
)

@Singleton
class RoleController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val userController: UserController,
    private val userClanController: UserClanController,
    private val notificationCenter: NotificationCenter,
    private val socketEventDispatcher: SocketEventDispatcher,
    private val permissionCatalogDao: PermissionCatalogDao,
    private val clanUserMaxPermissionDao: ClanUserMaxPermissionDao,
    private val clanRoleListMetaDao: ClanRoleListMetaDao,
    private val clanRoleCacheDao: ClanRoleCacheDao,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher
) {
    private val lock = Any()
    private val rolesByClan = HashMap<Long, ArrayList<ClanRole>>()
    private val listRolesSelfMaxLevelByClan = HashMap<Long, Int>()
    private val userMaxFromGetRoleByClan = HashMap<Long, Int>()
    private var maxPermissionUser = 0
    private var permissionsUserClanId = 0L
    private val loadingClans = ConcurrentHashMap<Long, Boolean>()
    private val userMaxLoadLock = Any()
    private val userMaxLoadingClans = HashSet<Long>()
    private val userMaxReloadAfterLoadClans = HashSet<Long>()
    private val clanLoadLocks = ConcurrentHashMap<Long, Any>()
    private val clanRoleLoadWaiters = ConcurrentHashMap<Long, CopyOnWriteArrayList<Runnable>>()
    private var permissionCatalog: List<PermissionCatalogEntry> = emptyList()
    private var permissionCatalogLoading = false
    private val permissionCatalogLoadMutex = Mutex()
    private val displayRoleCacheLock = Any()
    private val displayRoleCacheByClan = HashMap<Long, LongSparseArray<UserDisplayRole>>()
    private val displayRoleCacheBuilt = HashSet<Long>()
    private val persistSnapshotJobs = ConcurrentHashMap<Long, Job>()

    init {
        appScope.launch {
            socketEventDispatcher.roleEvents.collect { ev ->
                if (!ev.hasRole()) return@collect
                val cid = ev.role.clanId
                if (cid != 0L) {
                    loadRolesForClan(cid, force = true)
                }
            }
        }
        appScope.launch {
            socketEventDispatcher.roleAssignEvents.collect { ev ->
                val cid = ev.clanId.toLongOrNull() ?: 0L
                if (cid == 0L) return@collect
                invalidateDisplayRoleCache(cid)
                userClanController.loadClanMembers(cid, noCache = true)
                val selfId = userController.userId
                if (selfId == 0L) return@collect
                val affectsSelf = selfId in ev.userIdsAssignedList || selfId in ev.userIdsRemovedList
                if (affectsSelf) {
                    if (activePermissionsUserClanId() == cid) {
                        loadPermissionsUserForClan(cid, force = true)
                    } else {
                        loadUserMaxPermissionForClan(cid, force = true)
                    }
                    loadRolesForClan(cid, force = true)
                }
            }
        }
        appScope.launch(ioDispatcher) {
            hydratePermissionCacheFromDisk()
        }
    }

    fun getRoles(clanId: Long): List<ClanRole> = synchronized(lock) {
        val all = rolesByClan[clanId] ?: return@synchronized emptyList()
        all.filterNot { it.isEveryoneRole() }
    }

    fun profileRoleChipsForMember(clanId: Long, roleIds: List<Long>): List<MemberProfileRoleChip> {
        if (clanId == 0L || roleIds.isEmpty()) return emptyList()
        val rolesById = HashMap<Long, ClanRole>()
        synchronized(lock) {
            rolesByClan[clanId]?.forEach { rolesById[it.roleId] = it }
        }
        val result = ArrayList<MemberProfileRoleChip>(roleIds.size)
        for (roleId in roleIds) {
            val role = rolesById[roleId] ?: continue
            if (role.isEveryoneRole()) continue
            result.add(
                MemberProfileRoleChip(
                    roleId = role.roleId,
                    title = role.title,
                    color = role.color,
                    iconUrl = role.iconUrl,
                )
            )
        }
        return result
    }

    fun getEveryoneRole(clanId: Long): ClanRole? = synchronized(lock) {
        rolesByClan[clanId]?.firstOrNull { it.isEveryoneRole() }
    }

    fun getRole(clanId: Long, roleId: Long): ClanRole? = synchronized(lock) {
        rolesByClan[clanId]?.firstOrNull { it.roleId == roleId }
    }

    fun effectiveUserMaxPermissionLevel(clanId: Long): Int = synchronized(lock) {
        maxOf(
            userMaxFromGetRoleByClan[clanId] ?: 0,
            listRolesSelfMaxLevelByClan[clanId] ?: 0
        )
    }

    fun userMaxPermissionLevelForClan(clanId: Long): Int = synchronized(lock) {
        userMaxFromGetRoleByClan[clanId] ?: 0
    }

    fun hasUserMaxPermissionForClan(clanId: Long): Boolean = synchronized(lock) {
        userMaxFromGetRoleByClan.containsKey(clanId) || listRolesSelfMaxLevelByClan.containsKey(clanId)
    }

    fun hasPermissionCatalog(): Boolean = synchronized(lock) {
        permissionCatalog.isNotEmpty()
    }

    fun userMaxPermissionSourceLog(clanId: Long): String = synchronized(lock) {
        val api = userMaxFromGetRoleByClan[clanId] ?: 0
        val listMsg = listRolesSelfMaxLevelByClan[clanId] ?: 0
        "getRoleOfUserMax=$api listRolesPayloadMax=$listMsg"
    }

    fun getPermissionCatalog(): List<PermissionCatalogEntry> = synchronized(lock) {
        permissionCatalog.toList()
    }

    fun activeUserMaxPermissionLevel(): Int = synchronized(lock) {
        maxPermissionUser
    }

    fun activePermissionsUserClanId(): Long = synchronized(lock) {
        permissionsUserClanId
    }

    fun loadPermissionsUserForClan(clanId: Long, force: Boolean = false) {
        if (clanId <= 0L) {
            synchronized(lock) {
                permissionsUserClanId = 0L
                maxPermissionUser = 0
            }
            notificationCenter.postNotificationOnMainThread(NotificationCenter.clanRolesDidLoad, clanId)
            return
        }
        appScope.launch(ioDispatcher) {
            hydrateLocalPermissionSnapshotForClan(clanId)
            synchronized(lock) {
                permissionsUserClanId = clanId
                maxPermissionUser = userMaxFromGetRoleByClan[clanId] ?: maxPermissionUser
            }
            val needsRemote =
                force || synchronized(lock) { !userMaxFromGetRoleByClan.containsKey(clanId) }
            if (!needsRemote) return@launch
            if (!beginUserMaxLoad(clanId, force)) return@launch
            try {
                refreshPermissionsUserForClanCoalesced(clanId)
            } catch (e: Exception) {
                Log.e(TAG, "loadPermissionsUserForClan failed for clan $clanId", e)
            }
        }
    }

    fun loadUserMaxPermissionForClan(clanId: Long, force: Boolean = false) {
        if (clanId <= 0L) return
        appScope.launch(ioDispatcher) {
            hydrateLocalPermissionSnapshotForClan(clanId)
            val needsRemote =
                force || synchronized(lock) { !userMaxFromGetRoleByClan.containsKey(clanId) }
            if (!needsRemote) return@launch
            if (!beginUserMaxLoad(clanId, force)) return@launch
            try {
                refreshPermissionsUserForClanCoalesced(clanId)
            } catch (e: Exception) {
                Log.e(TAG, "loadUserMaxPermissionForClan failed for clan $clanId", e)
            }
        }
    }

    suspend fun refreshPermissionsUserForClan(clanId: Long): Result<Int> = withContext(ioDispatcher) {
        try {
            if (clanId <= 0L) return@withContext Result.success(0)
            hydrateLocalPermissionSnapshotForClan(clanId)
            synchronized(lock) {
                permissionsUserClanId = clanId
                maxPermissionUser = userMaxFromGetRoleByClan[clanId] ?: 0
            }
            if (!beginUserMaxLoad(clanId, force = true)) {
                return@withContext Result.success(effectiveUserMaxPermissionLevel(clanId))
            }
            Result.success(refreshPermissionsUserForClanCoalesced(clanId))
        } catch (e: Exception) {
            Log.e(TAG, "refreshPermissionsUserForClan failed for clan $clanId", e)
            Result.failure(e)
        }
    }

    fun maxPermissionRoleIdForSelf(
        clanId: Long,
        userId: Long,
        members: List<ClanMember>,
        clanCreatorId: Long
    ): Long {
        if (userId == 0L) return 0L
        if (clanCreatorId != 0L && userId == clanCreatorId) {
            val all = synchronized(lock) { rolesByClan[clanId]?.toList().orEmpty() }
            val top = all.maxByOrNull { it.maxLevelPermission }
            return top?.roleId ?: 0L
        }
        val all = synchronized(lock) { rolesByClan[clanId]?.toList().orEmpty() }
        val self = members.firstOrNull { it.userId == userId }
        if (self == null) {
            val effectiveLevel = effectiveUserMaxPermissionLevel(clanId)
            if (effectiveLevel <= 0) return 0L
            return all
                .filter { it.maxLevelPermission <= effectiveLevel }
                .maxByOrNull { it.maxLevelPermission }
                ?.roleId ?: 0L
        }
        val byId = all.associateBy { it.roleId }
        var bestLevel = -1
        var bestRoleId = 0L
        for (rid in self.roleIds) {
            val r = byId[rid] ?: continue
            if (r.maxLevelPermission > bestLevel) {
                bestLevel = r.maxLevelPermission
                bestRoleId = r.roleId
            }
        }
        if (bestRoleId != 0L) return bestRoleId
        val effectiveLevel = effectiveUserMaxPermissionLevel(clanId)
        if (effectiveLevel <= 0) return 0L
        return all
            .filter { it.maxLevelPermission <= effectiveLevel }
            .maxByOrNull { it.maxLevelPermission }
            ?.roleId ?: 0L
    }

    fun invalidateDisplayRoleCache(clanId: Long) {
        if (clanId <= 0L) return
        synchronized(displayRoleCacheLock) {
            displayRoleCacheByClan.remove(clanId)
            displayRoleCacheBuilt.remove(clanId)
        }
    }

    fun refreshDisplayRoleCache(clanId: Long, clanCreatorId: Long) {
        if (clanId <= 0L) return
        val allRoles = synchronized(lock) { rolesByClan[clanId]?.toList().orEmpty() }
        val members = userClanController.getClanMembers(clanId)
        val byUser = LongSparseArray<UserDisplayRole>()
        if (allRoles.isNotEmpty()) {
            val byId = HashMap<Long, ClanRole>(allRoles.size)
            for (role in allRoles) {
                byId[role.roleId] = role
            }
            if (clanCreatorId != 0L) {
                val top = allRoles.maxByOrNull { it.maxLevelPermission }
                if (top != null) {
                    byUser.put(clanCreatorId, UserDisplayRole(top.color, top.iconUrl))
                }
            }
            for (member in members) {
                if (member.userId == 0L) continue
                if (clanCreatorId != 0L && member.userId == clanCreatorId) continue
                byUser.put(member.userId, computeDisplayRoleForMember(member, byId))
            }
        }
        synchronized(displayRoleCacheLock) {
            displayRoleCacheByClan[clanId] = byUser
            displayRoleCacheBuilt.add(clanId)
        }
    }

    fun scheduleRefreshDisplayRoleCache(
        clanId: Long,
        clanCreatorId: Long,
        onComplete: (() -> Unit)? = null
    ) {
        if (clanId <= 0L) return
        appScope.launch(ioDispatcher) {
            refreshDisplayRoleCache(clanId, clanCreatorId)
            if (onComplete != null) {
                withContext(mainDispatcher) { onComplete() }
            }
        }
    }

    fun resolveHighestDisplayRole(clanId: Long, userId: Long, clanCreatorId: Long): UserDisplayRole? {
        if (clanId <= 0L || userId == 0L) return null
        val needsBuild = synchronized(displayRoleCacheLock) {
            !displayRoleCacheBuilt.contains(clanId)
        }
        if (needsBuild) {
            scheduleRefreshDisplayRoleCache(clanId, clanCreatorId)
            return null
        }
        synchronized(displayRoleCacheLock) {
            val byUser = displayRoleCacheByClan[clanId] ?: return null
            val ix = byUser.indexOfKey(userId)
            if (ix < 0) return null
            return byUser.valueAt(ix)
        }
    }

    private fun computeDisplayRoleForMember(
        member: ClanMember,
        rolesById: Map<Long, ClanRole>
    ): UserDisplayRole {
        if (member.roleIds.isEmpty()) return UserDisplayRole(0, "")
        var bestLevel = -1
        var bestRole: ClanRole? = null
        for (rid in member.roleIds) {
            val role = rolesById[rid] ?: continue
            if (role.maxLevelPermission > bestLevel) {
                bestLevel = role.maxLevelPermission
                bestRole = role
            }
        }
        if (bestRole == null) return UserDisplayRole(0, "")
        var iconUrl = bestRole.iconUrl
        if (iconUrl.isBlank()) {
            for (rid in member.roleIds) {
                val role = rolesById[rid] ?: continue
                if (role.iconUrl.isNotBlank()) {
                    iconUrl = role.iconUrl
                    break
                }
            }
        }
        return UserDisplayRole(bestRole.color, iconUrl)
    }

    fun forgetClanRoles(clanId: Long) {
        if (clanId == 0L) return
        invalidateDisplayRoleCache(clanId)
        synchronized(lock) {
            rolesByClan.remove(clanId)
            listRolesSelfMaxLevelByClan.remove(clanId)
            userMaxFromGetRoleByClan.remove(clanId)
            if (permissionsUserClanId == clanId) {
                permissionsUserClanId = 0L
                maxPermissionUser = 0
            }
        }
        loadingClans.remove(clanId)
        synchronized(userMaxLoadLock) {
            userMaxLoadingClans.remove(clanId)
            userMaxReloadAfterLoadClans.remove(clanId)
        }
        clanLoadLocks.remove(clanId)
        clanRoleLoadWaiters.remove(clanId)
        appScope.launch(ioDispatcher) {
            try {
                clanUserMaxPermissionDao.deleteByClan(clanId)
                clanRoleListMetaDao.deleteByClan(clanId)
                clanRoleCacheDao.deleteForClan(clanId)
            } catch (e: Exception) {
                Log.w(TAG, "delete clan permission/role cache failed clanId=$clanId", e)
            }
        }
    }

    fun addChannelToRoles(clanId: Long, channelId: Long, roleIds: Collection<Long>) {
        if (clanId == 0L || channelId == 0L || roleIds.isEmpty()) return
        var changed = false
        synchronized(lock) {
            val current = rolesByClan[clanId] ?: return
            val targetIds = roleIds.toHashSet()
            val next = ArrayList<ClanRole>(current.size)
            for (role in current) {
                if (role.roleId in targetIds && channelId !in role.channelIds) {
                    next.add(role.copy(roleChannelActive = 1, channelIds = (role.channelIds + channelId).distinct()))
                    changed = true
                } else {
                    next.add(role)
                }
            }
            if (changed) rolesByClan[clanId] = next
        }
        if (changed) {
            persistRolesSnapshot(clanId)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.clanRolesDidLoad, clanId)
        }
    }

    fun removeChannelFromRole(clanId: Long, channelId: Long, roleId: Long) {
        if (clanId == 0L || channelId == 0L || roleId == 0L) return
        var changed = false
        synchronized(lock) {
            val current = rolesByClan[clanId] ?: return
            val next = ArrayList<ClanRole>(current.size)
            for (role in current) {
                if (role.roleId == roleId) {
                    val nextChannelIds = role.channelIds.filterNot { it == channelId }
                    if (nextChannelIds.size != role.channelIds.size || role.channelIds.isEmpty()) {
                        next.add(
                            role.copy(
                                roleChannelActive = if (nextChannelIds.isEmpty()) 0 else role.roleChannelActive,
                                channelIds = nextChannelIds
                            )
                        )
                        changed = true
                    } else {
                        next.add(role)
                    }
                } else {
                    next.add(role)
                }
            }
            if (changed) rolesByClan[clanId] = next
        }
        if (changed) {
            persistRolesSnapshot(clanId)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.clanRolesDidLoad, clanId)
        }
    }

    private fun persistRolesSnapshot(clanId: Long) {
        if (clanId <= 0L) return
        persistSnapshotJobs.remove(clanId)?.cancel()
        persistSnapshotJobs[clanId] = appScope.launch(ioDispatcher) {
            delay(PERSIST_SNAPSHOT_DEBOUNCE_MS)
            val roles = synchronized(lock) { rolesByClan[clanId]?.toList().orEmpty() }
            if (roles.isEmpty()) {
                persistSnapshotJobs.remove(clanId)
                return@launch
            }
            val selfMax = synchronized(lock) { listRolesSelfMaxLevelByClan[clanId] ?: 0 }
            try {
                clanRoleListMetaDao.upsert(ClanRoleListMetaEntity(clanId, selfMax))
                clanRoleCacheDao.replaceClan(clanId, roles.map { it.toCacheEntity() })
            } catch (e: Exception) {
                Log.e(TAG, "persist clan roles cache failed clanId=$clanId", e)
            } finally {
                persistSnapshotJobs.remove(clanId)
            }
        }
    }

    fun cleanup() {
        persistSnapshotJobs.values.forEach { it.cancel() }
        persistSnapshotJobs.clear()
        synchronized(displayRoleCacheLock) {
            displayRoleCacheByClan.clear()
            displayRoleCacheBuilt.clear()
        }
        synchronized(lock) {
            rolesByClan.clear()
            listRolesSelfMaxLevelByClan.clear()
            userMaxFromGetRoleByClan.clear()
            maxPermissionUser = 0
            permissionsUserClanId = 0L
            permissionCatalog = emptyList()
            permissionCatalogLoading = false
        }
        loadingClans.clear()
        synchronized(userMaxLoadLock) {
            userMaxLoadingClans.clear()
            userMaxReloadAfterLoadClans.clear()
        }
        clanRoleLoadWaiters.clear()
        clanLoadLocks.clear()
        appScope.launch(ioDispatcher) {
            try {
                permissionCatalogDao.deleteAll()
                clanUserMaxPermissionDao.deleteAll()
                clanRoleListMetaDao.deleteAll()
                clanRoleCacheDao.deleteAll()
            } catch (e: Exception) {
                Log.e(TAG, "cleanup permission cache tables failed", e)
            }
        }
    }

    fun loadRolesForClan(clanId: Long, force: Boolean = false) {
        if (clanId <= 0) return
        val lockObj = clanLoadLocks.computeIfAbsent(clanId) { Any() }
        synchronized(lockObj) {
            if (!force && loadingClans[clanId] == true) return
            if (!force && rolesByClan[clanId].isNullOrEmpty().not()) return
            loadingClans[clanId] = true
        }
        appScope.launch(ioDispatcher) {
            try {
                hydrateLocalPermissionSnapshotForClan(clanId)
                loadRolesForClanSync(clanId)
            } finally {
                finalizeAfterRoleLoad(clanId, null)
            }
        }
    }

    fun loadRolesForClanThen(clanId: Long, force: Boolean = true, onComplete: Runnable) {
        if (clanId <= 0) {
            appScope.launch(mainDispatcher) { onComplete.run() }
            return
        }
        val lockObj = clanLoadLocks.computeIfAbsent(clanId) { Any() }
        appScope.launch(ioDispatcher) {
            hydrateLocalPermissionSnapshotForClan(clanId)
            withContext(mainDispatcher) { onComplete.run() }
            synchronized(lockObj) {
                if (!force && rolesByClan[clanId].isNullOrEmpty().not()) {
                    return@launch
                }
                if (loadingClans[clanId] == true) {
                    return@launch
                }
                loadingClans[clanId] = true
            }
            try {
                loadRolesForClanSync(clanId)
            } finally {
                finalizeAfterRoleLoad(clanId, null)
            }
        }
    }

    fun loadPermissionCatalogIfNeeded() {
        val shouldLoad = synchronized(lock) {
            if (permissionCatalog.isNotEmpty() || permissionCatalogLoading) {
                false
            } else {
                permissionCatalogLoading = true
                true
            }
        }
        if (!shouldLoad) return
        appScope.launch(ioDispatcher) {
            ensurePermissionCatalogLoaded()
        }
    }

    suspend fun hydrateLocalPermissionSnapshotForClan(clanId: Long) {
        if (clanId <= 0L) return
        fillPermissionCatalogFromDiskIfEmpty()
        hydrateUserMaxForClanFromDiskIfMissing(clanId)
        hydrateClanRolesFromDiskIfMissing(clanId)
    }

    private suspend fun fillPermissionCatalogFromDiskIfEmpty() {
        val skip = synchronized(lock) { permissionCatalog.isNotEmpty() }
        if (skip) return
        val catalogRows = permissionCatalogDao.getAll()
        if (catalogRows.isEmpty()) return
        val mapped = catalogRows.map {
            PermissionCatalogEntry(
                permissionId = it.permissionId,
                slug = it.slug,
                title = it.title,
                description = it.description,
                level = it.level,
                scope = it.scope,
            )
        }
        val notifyClanId = synchronized(lock) {
            if (permissionCatalog.isEmpty()) {
                permissionCatalog = mapped
                permissionsUserClanId
            } else {
                0L
            }
        }
        if (notifyClanId != 0L) {
            notificationCenter.postNotificationOnMainThread(NotificationCenter.clanRolesDidLoad, notifyClanId)
        }
    }

    private suspend fun hydrateUserMaxForClanFromDiskIfMissing(clanId: Long) {
        if (clanId <= 0L) return
        val hasKey = synchronized(lock) { userMaxFromGetRoleByClan.containsKey(clanId) }
        if (hasKey) return
        val row = clanUserMaxPermissionDao.getForClan(clanId) ?: return
        synchronized(lock) {
            userMaxFromGetRoleByClan[clanId] = row.maxLevel
            if (permissionsUserClanId == clanId) {
                maxPermissionUser = row.maxLevel
            }
        }
    }

    private suspend fun hydrateClanRolesFromDiskIfMissing(clanId: Long) {
        if (clanId <= 0L) return
        val needsRoles = synchronized(lock) { rolesByClan[clanId].isNullOrEmpty() }
        if (!needsRoles) return
        val rows = clanRoleCacheDao.getForClan(clanId)
        if (rows.isEmpty()) return
        val meta = clanRoleListMetaDao.getForClan(clanId)
        val mappedRoles = rows.mapNotNull { it.toClanRole() }
        if (mappedRoles.isEmpty()) return
        synchronized(lock) {
            rolesByClan[clanId] = ArrayList(mappedRoles)
            if (meta != null) {
                listRolesSelfMaxLevelByClan[clanId] = meta.listRolesSelfMaxLevel
            }
        }
        invalidateDisplayRoleCache(clanId)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.clanRolesDidLoad, clanId)
    }

    suspend fun ensurePermissionCatalogLoaded() {
        try {
            permissionCatalogLoadMutex.withLock {
                fillPermissionCatalogFromDiskIfEmpty()
                val empty = synchronized(lock) { permissionCatalog.isEmpty() }
                if (!empty) return@withLock
                synchronized(lock) {
                    permissionCatalogLoading = true
                }
                val pl = sessionManager.withAutoRefresh { session ->
                    api.getListPermission(session.apiUrl, session.token)
                }
                val mapped = pl.permissionsList.map { p ->
                    PermissionCatalogEntry(
                        permissionId = p.id,
                        slug = p.slug,
                        title = p.title,
                        description = p.description,
                        level = p.level,
                        scope = p.scope,
                    )
                }
                val notifyClanId = synchronized(lock) {
                    if (permissionCatalog.isEmpty()) {
                        permissionCatalog = mapped
                        permissionsUserClanId
                    } else {
                        0L
                    }
                }
                try {
                    permissionCatalogDao.replaceAll(
                        mapped.map {
                            PermissionCatalogEntity(
                                permissionId = it.permissionId,
                                slug = it.slug,
                                title = it.title,
                                description = it.description,
                                level = it.level,
                                scope = it.scope,
                            )
                        },
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "persist permission catalog failed", e)
                }
                if (notifyClanId != 0L) {
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.clanRolesDidLoad, notifyClanId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensurePermissionCatalogLoaded failed", e)
        } finally {
            synchronized(lock) {
                permissionCatalogLoading = false
            }
        }
    }

    private suspend fun hydratePermissionCacheFromDisk() {
        try {
            val catalogRows = permissionCatalogDao.getAll()
            if (catalogRows.isNotEmpty()) {
                val mapped = catalogRows.map {
                    PermissionCatalogEntry(
                        permissionId = it.permissionId,
                        slug = it.slug,
                        title = it.title,
                        description = it.description,
                        level = it.level,
                        scope = it.scope,
                    )
                }
                val notifyClanId = synchronized(lock) {
                    if (permissionCatalog.isEmpty()) {
                        permissionCatalog = mapped
                        permissionsUserClanId
                    } else {
                        0L
                    }
                }
                if (notifyClanId != 0L) {
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.clanRolesDidLoad, notifyClanId)
                }
            }
            val maxRows = clanUserMaxPermissionDao.getAll()
            if (maxRows.isNotEmpty()) {
                val activeClan = synchronized(lock) {
                    for (row in maxRows) {
                        if (row.clanId > 0L) {
                            userMaxFromGetRoleByClan[row.clanId] = row.maxLevel
                        }
                    }
                    val cid = permissionsUserClanId
                    if (cid != 0L) {
                        maxPermissionUser = userMaxFromGetRoleByClan[cid] ?: maxPermissionUser
                    }
                    cid
                }
                if (activeClan != 0L) {
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.clanRolesDidLoad, activeClan)
                }
            }
            try {
                val metaRows = clanRoleListMetaDao.getAll()
                synchronized(lock) {
                    for (row in metaRows) {
                        if (row.clanId > 0L) {
                            listRolesSelfMaxLevelByClan[row.clanId] = row.listRolesSelfMaxLevel
                        }
                    }
                }
                val roleRows = clanRoleCacheDao.getAll()
                if (roleRows.isNotEmpty()) {
                    val byClan = roleRows.groupBy { it.clanId }
                    val hydratedClanIds = LinkedHashSet<Long>()
                    synchronized(lock) {
                        for ((cid, list) in byClan) {
                            if (cid <= 0L) continue
                            val mappedRoles = list.mapNotNull { it.toClanRole() }
                            if (mappedRoles.isNotEmpty()) {
                                rolesByClan[cid] = ArrayList(mappedRoles)
                                hydratedClanIds.add(cid)
                            }
                        }
                    }
                    for (cid in hydratedClanIds) {
                        invalidateDisplayRoleCache(cid)
                        notificationCenter.postNotificationOnMainThread(NotificationCenter.clanRolesDidLoad, cid)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "hydrate clan roles from disk failed", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "hydratePermissionCacheFromDisk failed", e)
        }
    }

    private suspend fun refreshPermissionsUserForClanSync(clanId: Long): Int {
        val roles = sessionManager.withAutoRefresh { session ->
            api.getRoleOfUserInTheClan(session.apiUrl, session.token, clanId)
        }
        val userMax = roles.maxLevelPermission
        synchronized(lock) {
            userMaxFromGetRoleByClan[clanId] = userMax
            if (permissionsUserClanId == clanId) {
                maxPermissionUser = userMax
            }
        }
        try {
            clanUserMaxPermissionDao.upsert(
                ClanUserMaxPermissionEntity(
                    clanId = clanId,
                    maxLevel = userMax,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "persist clan user max permission failed clanId=$clanId", e)
        }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.clanRolesDidLoad, clanId)
        return userMax
    }

    private suspend fun refreshPermissionsUserForClanCoalesced(clanId: Long): Int {
        var userMax = 0
        while (true) {
            try {
                userMax = refreshPermissionsUserForClanSync(clanId)
            } catch (e: Exception) {
                abortUserMaxLoad(clanId)
                throw e
            }
            if (!completeUserMaxLoadIteration(clanId)) return userMax
        }
    }

    private fun beginUserMaxLoad(clanId: Long, force: Boolean): Boolean {
        if (clanId <= 0L) return false
        if (!force) {
            val cached = synchronized(lock) { userMaxFromGetRoleByClan.containsKey(clanId) }
            if (cached) return false
        }
        return synchronized(userMaxLoadLock) {
            if (userMaxLoadingClans.add(clanId)) {
                true
            } else {
                if (force) userMaxReloadAfterLoadClans.add(clanId)
                false
            }
        }
    }

    private fun completeUserMaxLoadIteration(clanId: Long): Boolean = synchronized(userMaxLoadLock) {
        if (userMaxReloadAfterLoadClans.remove(clanId)) {
            true
        } else {
            userMaxLoadingClans.remove(clanId)
            false
        }
    }

    private fun abortUserMaxLoad(clanId: Long) {
        synchronized(userMaxLoadLock) {
            userMaxLoadingClans.remove(clanId)
            userMaxReloadAfterLoadClans.remove(clanId)
        }
    }

    suspend fun createRole(
        clanId: Long,
        title: String,
        colorHex: String,
        members: List<ClanMember>,
        clanCreatorId: Long
    ): Result<ClanRole> = withContext(ioDispatcher) {
        try {
            val maxRid = maxPermissionRoleIdForSelf(clanId, userController.userId, members, clanCreatorId)
            val role = sessionManager.withAutoRefresh { session ->
                api.createRole(
                    session.apiUrl,
                    session.token,
                    clanId,
                    title,
                    normalizeRoleColorForApi(colorHex),
                    maxRid,
                    emptyList(),
                    emptyList()
                )
            }
            loadRolesForClanSync(clanId)
            val mapped = mapProtoRoleToClanRole(role)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.clanRolesDidLoad, clanId)
            Result.success(mapped)
        } catch (e: Exception) {
            Log.e(TAG, "createRole failed", e)
            Result.failure(e)
        }
    }

    suspend fun updateRoleSimple(
        clanId: Long,
        roleId: Long,
        title: String?,
        colorHex: String?,
        roleIcon: String?,
        addUserIds: List<Long>,
        removeUserIds: List<Long>,
        addPermissionIds: List<Long>,
        removePermissionIds: List<Long>,
        members: List<ClanMember>,
        clanCreatorId: Long,
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            val maxRid = maxPermissionRoleIdForSelf(clanId, userController.userId, members, clanCreatorId)
            sessionManager.withAutoRefresh { session ->
                api.updateRole(
                    session.apiUrl,
                    session.token,
                    clanId,
                    roleId,
                    title,
                    colorHex?.let { normalizeRoleColorForApi(it) },
                    roleIcon,
                    addUserIds,
                    removeUserIds,
                    addPermissionIds,
                    removePermissionIds,
                    maxRid
                )
            }
            if (addUserIds.isNotEmpty() || removeUserIds.isNotEmpty()) {
                userClanController.applyRoleAssignments(clanId, roleId, addUserIds, removeUserIds)
                invalidateDisplayRoleCache(clanId)
            }
            loadRolesForClanSync(clanId)
            notificationCenter.postNotificationOnMainThread(NotificationCenter.clanRolesDidLoad, clanId)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "updateRole failed", e)
            Result.failure(e)
        }
    }

    suspend fun deleteRole(clanId: Long, roleId: Long, roleTitle: String): Result<Unit> =
        withContext(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
                    api.deleteRole(session.apiUrl, session.token, clanId, roleId, roleTitle)
                }
                loadRolesForClanSync(clanId)
                notificationCenter.postNotificationOnMainThread(NotificationCenter.clanRolesDidLoad, clanId)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "deleteRole failed", e)
                Result.failure(e)
            }
        }

    suspend fun loadAllRoleMemberUserIds(roleId: Long): List<Long> = withContext(ioDispatcher) {
        val ids = LinkedHashSet<Long>()
        var cursor = ""
        while (true) {
            val page = sessionManager.withAutoRefresh { session ->
                api.listRoleUsers(session.apiUrl, session.token, roleId, 100, cursor)
            }
            page.roleUsersList.forEach { ids.add(it.id) }
            val next = page.cursor
            if (next.isNullOrBlank()) break
            cursor = next
        }
        ids.toList()
    }

    private fun normalizeRoleColorForApi(raw: String): String {
        val clean = raw.trim().removePrefix("#").lowercase()
        return if (clean.isEmpty()) "" else "#$clean"
    }

    private suspend fun finalizeAfterRoleLoad(clanId: Long, primary: Runnable?) {
        loadingClans[clanId] = false
        withContext(mainDispatcher) {
            primary?.run()
            clanRoleLoadWaiters.remove(clanId)?.forEach { it.run() }
        }
    }

    private suspend fun loadRolesForClanSync(clanId: Long) {
        try {
            val response = sessionManager.withAutoRefresh { session ->
                api.listRoles(session.apiUrl, session.token, clanId)
            }
            val roleList = response.roles
            val mapped = mergeLocalChannelAssignments(
                clanId,
                roleList.rolesList.map { mapProtoRoleToClanRole(it) }
            )
            var userMax: Int? = null
            if (beginUserMaxLoad(clanId, force = true)) {
                try {
                    userMax = refreshPermissionsUserForClanCoalesced(clanId)
                } catch (e: Exception) {
                    Log.w(TAG, "getRoleOfUserInTheClan failed for clan $clanId", e)
                }
            }
            val loggedUserMax = synchronized(lock) {
                rolesByClan[clanId] = ArrayList(mapped)
                listRolesSelfMaxLevelByClan[clanId] = roleList.maxLevelPermission
                if (userMax != null) {
                    userMaxFromGetRoleByClan[clanId] = userMax
                    if (permissionsUserClanId == clanId) {
                        maxPermissionUser = userMax
                    }
                }
                userMaxFromGetRoleByClan[clanId] ?: 0
            }
            invalidateDisplayRoleCache(clanId)
            try {
                clanRoleListMetaDao.upsert(
                    ClanRoleListMetaEntity(clanId, roleList.maxLevelPermission),
                )
                clanRoleCacheDao.replaceClan(clanId, mapped.map { it.toCacheEntity() })
            } catch (e: Exception) {
                Log.e(TAG, "persist clan roles cache failed clanId=$clanId", e)
            }
            Log.d(
                TAG,
                "clanId=$clanId rolesCount=${mapped.size} getRoleOfUserMax=$loggedUserMax listRolesPayloadMax=${roleList.maxLevelPermission} effective=${effectiveUserMaxPermissionLevel(clanId)}",
            )
            Log.d(TAG, "Loaded ${mapped.size} roles for clan $clanId")
            notificationCenter.postNotificationOnMainThread(NotificationCenter.clanRolesDidLoad, clanId)
        } catch (e: Exception) {
            Log.e(TAG, "loadRolesForClan failed for clan $clanId", e)
        }
    }

    private fun mergeLocalChannelAssignments(clanId: Long, remoteRoles: List<ClanRole>): List<ClanRole> {
        val existingById = synchronized(lock) {
            rolesByClan[clanId]?.associateBy { it.roleId }.orEmpty()
        }
        if (existingById.isEmpty()) return remoteRoles
        return remoteRoles.map { remote ->
            val existing = existingById[remote.roleId]
            if (existing != null && remote.channelIds.isEmpty() && existing.channelIds.isNotEmpty()) {
                remote.copy(roleChannelActive = 1, channelIds = existing.channelIds)
            } else {
                remote
            }
        }
    }

    companion object {
        private const val PERSIST_SNAPSHOT_DEBOUNCE_MS = 300L
    }
}
