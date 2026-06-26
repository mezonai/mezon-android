package com.mezon.mobile.home.clans

import com.mezon.mezon.api.Permission
import com.mezon.mezon.api.Role
import com.mezon.mobile.util.absoluteResourceUrl

data class RolePermissionInfo(
    val permissionId: Long,
    val slug: String,
    val title: String,
    val active: Boolean,
    val level: Int,
)

data class ClanRole(
    val roleId: Long,
    val clanId: Long,
    val title: String,
    val color: Int,
    val colorHexRaw: String,
    val iconUrl: String,
    val slug: String,
    val permissionSlugs: List<String>,
    val maxLevelPermission: Int,
    val memberCount: Int,
    val rolePermissions: List<RolePermissionInfo>,
    val roleChannelActive: Int,
    val channelIds: List<Long>,
    val orderRole: Int,
)

data class PermissionCatalogEntry(
    val permissionId: Long,
    val slug: String,
    val title: String,
    val description: String,
    val level: Int,
    val scope: Int = 0,
)

fun ClanRole.isEveryoneRole(): Boolean = slug == everyoneSlugForClan(clanId)

fun everyoneSlugForClan(clanId: Long): String = "everyone-$clanId"

fun mapProtoRoleToClanRole(proto: Role): ClanRole {
    val cid = proto.clanId
    val rawColor = proto.color
    val colorInt = parseRoleHexColor(rawColor)
    val permList = if (proto.hasPermissionList()) {
        proto.permissionList.permissionsList
    } else {
        emptyList()
    }
    val rolePerms = permList.map { mapProtoPermission(it) }
    val activeSlugs = rolePerms.filter { it.active }.map { it.slug }
    val memberCount = if (proto.hasRoleUserList()) {
        proto.roleUserList.roleUsersCount
    } else {
        0
    }
    return ClanRole(
        roleId = proto.id,
        clanId = cid,
        title = proto.title,
        color = colorInt,
        colorHexRaw = rawColor,
        iconUrl = absoluteResourceUrl(proto.roleIcon),
        slug = proto.slug,
        permissionSlugs = activeSlugs,
        maxLevelPermission = proto.maxLevelPermission,
        memberCount = memberCount,
        rolePermissions = rolePerms,
        roleChannelActive = proto.roleChannelActive,
        channelIds = proto.channelIdsList.toList(),
        orderRole = proto.orderRole,
    )
}

private fun mapProtoPermission(p: Permission): RolePermissionInfo =
    RolePermissionInfo(
        permissionId = p.id,
        slug = p.slug,
        title = p.title,
        active = p.active != 0,
        level = p.level,
    )

private fun parseRoleHexColor(raw: String): Int {
    if (raw.isBlank()) return 0
    val hex = if (raw.startsWith("#")) raw else "#$raw"
    return try {
        android.graphics.Color.parseColor(hex)
    } catch (_: Exception) {
        0
    }
}
