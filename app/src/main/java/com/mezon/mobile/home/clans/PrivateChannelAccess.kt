package com.mezon.mobile.home.clans

internal fun privateChannelAccessAfterUpdate(
    userId: Long,
    creatorId: Long,
    memberIds: List<Long>,
    roleIds: List<Long>,
    selfRoleIds: List<Long>?,
    isOwnerOrAdmin: Boolean,
    permissionsLoaded: Boolean,
): Boolean? {
    if (userId == 0L) return null
    if (isOwnerOrAdmin || userId == creatorId || userId in memberIds) return true
    if (selfRoleIds?.any { it in roleIds } == true) return true
    if (!permissionsLoaded || (roleIds.isNotEmpty() && selfRoleIds == null)) return null
    return false
}

internal fun channelEventTargetsUser(userId: Long, targetIds: List<Long>): Boolean =
    userId != 0L && userId in targetIds
