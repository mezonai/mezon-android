package com.mezon.mobile.home.clans

import android.util.Log
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.clans.settings.ClanSettingsPermissionState
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG_PRIVATE_CHANNEL = "PermissionPolicy"

@Singleton
class PermissionPolicy @Inject constructor(
    private val roleController: RoleController,
    private val channelPermissionController: ChannelPermissionController,
    private val clansController: ClansController,
    private val userController: UserController,
    private val userClanController: UserClanController,
) {
    companion object {
        const val CLAN_OWNER = "clan-owner"
        const val ADMINISTRATOR = "administrator"
        const val VIEW_CHANNEL = "view-channel"
        const val MANAGE_CHANNEL = "manage-channel"
        const val MANAGE_CLAN = "manage-clan"
        const val MANAGE_THREAD = "manage-thread"
        const val SEND_MESSAGE = "send-message"
        const val DELETE_MESSAGE = "delete-message"

        private val OVERRIDDEN_PERMISSIONS = setOf(
            MANAGE_THREAD,
            SEND_MESSAGE,
            DELETE_MESSAGE
        )
    }

    private fun effectiveClanId(clanId: Long?): Long = when {
        clanId != null && clanId != 0L -> clanId
        else -> clansController.selectedClanId.value
    }

    fun clanSettingsPermissionState(clanId: Long): ClanSettingsPermissionState {
        if (clanId <= 0L) {
            return ClanSettingsPermissionState(false, false, false)
        }
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId }
            ?: return ClanSettingsPermissionState(false, false, false)
        val members = userClanController.getClanMembers(clanId)
        val roles = roleController.getRoles(clanId) + listOfNotNull(roleController.getEveryoneRole(clanId))
        return ClanSettingsPermissionState.evaluateForClanSettings(
            userController,
            clanId,
            members,
            roles,
            clan.creatorId,
            roleController.getPermissionCatalog(),
            roleController.effectiveUserMaxPermissionLevel(clanId),
        )
    }

    fun isActiveClanPermissionDataReady(): Boolean {
        val activeClanId = clansController.selectedClanId.value
        return isPermissionDataReadyForClan(activeClanId)
    }

    fun isPermissionDataReadyForClan(clanId: Long): Boolean {
        if (clanId == 0L) return false
        return roleController.hasPermissionCatalog() && roleController.hasUserMaxPermissionForClan(clanId)
    }

    fun ensurePermissionChecker(permissions: Collection<String>, channelId: Long? = null, clanId: Long? = null) {
        val resolvedClanId = effectiveClanId(clanId)
        roleController.loadPermissionCatalogIfNeeded()
        if (resolvedClanId != 0L) {
            roleController.loadPermissionsUserForClan(resolvedClanId)
        }
        val needsChannel =
            channelId != null && channelId != 0L && permissions.any { it in OVERRIDDEN_PERMISSIONS }
        if (needsChannel) {
            val clanForChannelApi = when {
                clanId != null && clanId != 0L -> clanId
                else -> clansController.selectedClanId.value
            }
            channelPermissionController.ensureUserPermissionsInChannel(clanForChannelApi, channelId!!)
        }
    }

    fun checkPermissions(permissions: Collection<String>, channelId: Long? = null, clanId: Long? = null): Boolean {
        ensurePermissionChecker(permissions, channelId, clanId)
        return permissions.all { checkPermission(it, channelId, clanId) }
    }

    fun checkAnyPermission(permissions: Collection<String>, channelId: Long? = null, clanId: Long? = null): Boolean {
        ensurePermissionChecker(permissions, channelId, clanId)
        return permissions.any { checkPermission(it, channelId, clanId) }
    }

    fun checkPermission(permission: String, channelId: Long? = null, clanId: Long? = null): Boolean {
        if (permission in OVERRIDDEN_PERMISSIONS) {
            val cid = channelId ?: return false
            if (cid == 0L) return false
            return channelPermissionController.hasUserPermissionInChannel(cid, permission)
        }
        val activeClanId = effectiveClanId(clanId)
        if (permission == CLAN_OWNER) {
            if (activeClanId == 0L) return false
            return isClanOwner(activeClanId)
        }
        if (activeClanId == 0L) {
            return false
        }
        if (isClanOwner(activeClanId)) {
            return true
        }
        val level = roleController.getPermissionCatalog().firstOrNull { it.slug == permission }?.level ?: return false
        val maxUser = roleController.effectiveUserMaxPermissionLevel(activeClanId)
        return level <= maxUser
    }

    fun canManageChannelForClan(clanId: Long): Boolean {
        val selected = clansController.selectedClanId.value
        if (selected == 0L || clanId == 0L || clanId != selected) return false
        return checkAnyPermission(listOf(ADMINISTRATOR, MANAGE_CHANNEL), null, null)
    }

    fun canDeleteChannelFromMenu(channel: ClanChannelEntity, clanId: Long): Boolean {
        if (clanId == 0L || channel.channelId == 0L) return false
        if (isWelcomeChannel(clanId, channel.channelId)) return false
        return if (channel.isThread) {
            ensurePermissionChecker(
                listOf(CLAN_OWNER, MANAGE_THREAD, ADMINISTRATOR, MANAGE_CHANNEL),
                channel.channelId,
                clanId,
            )
            val isOwner = checkPermission(CLAN_OWNER, channel.channelId, clanId)
            val isAdministrator = checkPermission(ADMINISTRATOR, null, clanId)
            val isCreator = channel.creatorId != 0L && channel.creatorId == userController.userId
            val canManageThread = checkPermission(MANAGE_THREAD, channel.channelId, clanId) ||
                checkPermission(MANAGE_CHANNEL, null, clanId)
            isOwner || isAdministrator || (isCreator && canManageThread)
        } else {
            ensurePermissionChecker(listOf(ADMINISTRATOR, MANAGE_CHANNEL), null, clanId)
            canManageChannelForClan(clanId)
        }
    }

    private fun isWelcomeChannel(clanId: Long, channelId: Long): Boolean {
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId } ?: return true
        return clan.welcomeChannelId != 0L && clan.welcomeChannelId == channelId
    }

    fun canCreateThreadFromMessage(channelId: Long, clanId: Long): Boolean {
        if (channelId == 0L || clanId == 0L) return false
        ensurePermissionChecker(listOf(CLAN_OWNER, MANAGE_THREAD, MANAGE_CHANNEL), channelId, clanId)
        if (checkPermission(CLAN_OWNER, channelId, clanId)) return true
        return checkPermission(MANAGE_THREAD, channelId, clanId) &&
            checkPermission(MANAGE_CHANNEL, channelId, clanId)
    }

    fun canCreateThreadFromThreadList(channelId: Long, clanId: Long): Boolean {
        if (channelId == 0L || clanId == 0L) return false
        ensurePermissionChecker(listOf(CLAN_OWNER, MANAGE_THREAD, MANAGE_CHANNEL), channelId, clanId)
        if (checkPermission(CLAN_OWNER, channelId, clanId)) return true
        return checkPermission(MANAGE_THREAD, channelId, clanId) ||
            checkPermission(MANAGE_CHANNEL, channelId, clanId)
    }

    fun canOpenChannelSettings(channelId: Long, clanId: Long, channelType: Int, parentId: Long = 0L): Boolean {
        if (channelId == 0L || clanId == 0L) return false
        val selected = clansController.selectedClanId.value
        if (selected == 0L || clanId != selected) return false
        val isThread = channelType == CHANNEL_TYPE_THREAD || parentId != 0L
        val isChannel = channelType == CHANNEL_TYPE_CHANNEL && !isThread
        if (isChannel) {
            ensurePermissionChecker(listOf(ADMINISTRATOR, MANAGE_CHANNEL), null, clanId)
            return checkPermission(ADMINISTRATOR, null, clanId) ||
                checkPermission(MANAGE_CHANNEL, null, clanId)
        }
        ensurePermissionChecker(listOf(CLAN_OWNER, MANAGE_THREAD), channelId, clanId)
        return checkPermission(CLAN_OWNER, channelId, clanId) ||
            checkPermission(MANAGE_THREAD, channelId, clanId)
    }

    private fun isClanOwner(forClanId: Long): Boolean {
        if (forClanId == 0L) return false
        val userId = userController.userId
        if (userId == 0L) return false
        val clan = clansController.clans.value.firstOrNull { it.clanId == forClanId } ?: return false
        return clan.creatorId == userId
    }

    fun ensurePrivateChannelAccessPrefetch(clanId: Long, channelId: Long, channelType: Int) {
        if (clanId == 0L || channelId == 0L) return
        Log.d(
            TAG_PRIVATE_CHANNEL,
            "ensurePrivateChannelAccessPrefetch clanId=$clanId channelId=$channelId channelType=$channelType",
        )
        ensurePermissionChecker(listOf(VIEW_CHANNEL), null, clanId)
        channelPermissionController.ensureUserPermissionsInChannel(clanId, channelId, force = false)
        userClanController.loadDirectChannelMembers(clanId, channelId, noCache = false)
        userClanController.loadChannelMembers(clanId, channelId, channelType, noCache = false)
    }

    fun hasCachedChannelUserPermissions(channelId: Long): Boolean =
        channelPermissionController.hasCachedChannelUserPermissions(channelId)
}
