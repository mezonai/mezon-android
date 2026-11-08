package com.mezon.mobile.home.clans

import com.mezon.mobile.home.ClanMember
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class DisplayRoleCache(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val readInputs: (Long) -> Inputs,
    private val onChanged: (Long) -> Unit,
) {
    data class Inputs(val roles: List<ClanRole>, val members: List<ClanMember>) {
        fun sameSnapshot(other: Inputs): Boolean = roles === other.roles && members === other.members
    }

    private class Entry {
        var inputs: Inputs? = null
        var byUser: Map<Long, UserDisplayRole> = emptyMap()
        var refreshing = false
    }

    private val lock = Any()
    private val entries = HashMap<Long, Entry>()

    fun get(clanId: Long, userId: Long): UserDisplayRole? =
        synchronized(lock) { entries[clanId]?.byUser?.get(userId) }

    fun refreshIfNeeded(clanId: Long) {
        if (clanId <= 0L) return
        val entry: Entry
        val inputs: Inputs
        synchronized(lock) {
            entry = entries.getOrPut(clanId) { Entry() }
            if (entry.refreshing) return
            inputs = readInputs(clanId)
            if (entry.inputs?.sameSnapshot(inputs) == true) return
            entry.refreshing = true
        }
        scope.launch(dispatcher) {
            if (refresh(clanId, entry, inputs)) onChanged(clanId)
        }
    }

    private fun refresh(clanId: Long, entry: Entry, initial: Inputs): Boolean {
        var inputs = initial
        while (true) {
            val byUser = if (inputs == entry.inputs) entry.byUser else buildDisplayRoles(inputs)
            val changed = byUser != entry.byUser
            synchronized(lock) {
                if (entries[clanId] !== entry) return false
                val latest = readInputs(clanId)
                if (inputs.sameSnapshot(latest)) {
                    entry.inputs = inputs
                    entry.byUser = byUser
                    entry.refreshing = false
                    return changed
                }
                inputs = latest
            }
        }
    }

    fun remove(clanId: Long) {
        synchronized(lock) { entries.remove(clanId) }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    private fun buildDisplayRoles(inputs: Inputs): Map<Long, UserDisplayRole> {
        if (inputs.roles.isEmpty()) return emptyMap()
        val byId = inputs.roles.associateBy { it.roleId }
        val byUser = HashMap<Long, UserDisplayRole>()
        for (member in inputs.members) {
            if (member.userId == 0L) continue
            byUser[member.userId] = computeDisplayRole(member.roleIds, byId)
        }
        val roleIdsByListedUser = HashMap<Long, ArrayList<Long>>()
        for (role in inputs.roles) {
            for (uid in role.userIds) {
                if (uid == 0L || uid in byUser) continue
                roleIdsByListedUser.getOrPut(uid) { ArrayList() }.add(role.roleId)
            }
        }
        for ((uid, roleIds) in roleIdsByListedUser) {
            byUser[uid] = computeDisplayRole(roleIds, byId)
        }
        return byUser
    }

    private fun computeDisplayRole(
        roleIds: List<Long>,
        rolesById: Map<Long, ClanRole>
    ): UserDisplayRole {
        if (roleIds.isEmpty()) return UserDisplayRole(0, "")
        var bestOrder = Int.MAX_VALUE
        var bestRole: ClanRole? = null
        for (rid in roleIds) {
            val role = rolesById[rid] ?: continue
            if (role.orderRole < bestOrder) {
                bestOrder = role.orderRole
                bestRole = role
            }
        }
        if (bestRole == null) return UserDisplayRole(0, "")
        var iconUrl = bestRole.iconUrl
        if (iconUrl.isBlank()) {
            var bestIconOrder = Int.MAX_VALUE
            for (rid in roleIds) {
                val role = rolesById[rid] ?: continue
                if (role.iconUrl.isNotBlank() && role.orderRole < bestIconOrder) {
                    bestIconOrder = role.orderRole
                    iconUrl = role.iconUrl
                }
            }
        }
        return UserDisplayRole(bestRole.color, iconUrl)
    }
}
