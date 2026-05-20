package com.mezon.mobile.home.clans.settings

import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.clans.ClanRole
import com.mezon.mobile.home.clans.PermissionCatalogEntry
import com.mezon.mobile.home.profile.UserController

data class ClanSettingsPermissionState(
    val hasAdminPermission: Boolean,
    val hasManageClanPermission: Boolean,
    val isClanOwner: Boolean
) {
    val isShowOverviewOption: Boolean
        get() = hasAdminPermission || isClanOwner

    val isCanEditRole: Boolean
        get() = isShowOverviewOption || hasManageClanPermission

    companion object {
        private const val SLUG_ADMIN = "administrator"
        private const val SLUG_MANAGE_CLAN = "manage-clan"

        fun evaluateForClanSettings(
            userController: UserController,
            clanId: Long,
            members: List<ClanMember>,
            roles: List<ClanRole>,
            clanCreatorId: Long = 0L,
            permissionCatalog: List<PermissionCatalogEntry> = emptyList(),
            maxPermissionUser: Int = 0,
        ): ClanSettingsPermissionState {
            if (clanId <= 0L) {
                return ClanSettingsPermissionState(false, false, false)
            }
            val userId = userController.userId
            if (userId != 0L && clanCreatorId != 0L && userId == clanCreatorId) {
                return ClanSettingsPermissionState(
                    hasAdminPermission = true,
                    hasManageClanPermission = true,
                    isClanOwner = true,
                )
            }
            if (permissionCatalog.isNotEmpty()) {
                fun hasLevel(slug: String): Boolean {
                    val level = permissionCatalog.firstOrNull { it.slug == slug }?.level ?: return false
                    return level <= maxPermissionUser
                }
                return ClanSettingsPermissionState(
                    hasAdminPermission = hasLevel(SLUG_ADMIN),
                    hasManageClanPermission = hasLevel(SLUG_MANAGE_CLAN),
                    isClanOwner = false
                )
            }
            val self = members.firstOrNull { it.userId == userId }
                ?: return ClanSettingsPermissionState(false, false, false)
            val rolesById = roles.associateBy { it.roleId }
            val slugsFromPermissions = HashSet<String>()
            for (rid in self.roleIds) {
                val role = rolesById[rid] ?: continue
                for (p in role.permissionSlugs) {
                    slugsFromPermissions.add(p)
                }
            }
            val admin = SLUG_ADMIN in slugsFromPermissions
            val manage = SLUG_MANAGE_CLAN in slugsFromPermissions
            return ClanSettingsPermissionState(
                hasAdminPermission = admin,
                hasManageClanPermission = manage,
                isClanOwner = false
            )
        }
    }
}
