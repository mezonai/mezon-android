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

sealed class PrivateChannelOpenResolution {
    data object Proceed : PrivateChannelOpenResolution()
    data object WaitPermissionData : PrivateChannelOpenResolution()
    data object WaitChannelEvidence : PrivateChannelOpenResolution()
    data object NeedNetworkForPermission : PrivateChannelOpenResolution()
    data object NeedNetworkForEvidence : PrivateChannelOpenResolution()
    data object DeniedNoView : PrivateChannelOpenResolution()
}

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

    fun privateChannelViewEvidenceLoaded(channelId: Long): Boolean {
        if (channelId == 0L) return true
        val hasPerm = channelPermissionController.hasCachedChannelUserPermissions(channelId)
        val direct = userClanController.hasDirectChannelMembersLoaded(channelId)
        val channel = userClanController.hasChannelMembersLoaded(channelId)
        val ok = hasPerm && (direct || channel)
        Log.d(
            TAG_PRIVATE_CHANNEL,
            "privateChannelViewEvidenceLoaded channelId=$channelId hasPermCache=$hasPerm directLoaded=$direct channelMembersLoaded=$channel ready=$ok",
        )
        if (!hasPerm) return false
        return direct || channel
    }

    fun canViewClanChannel(clanId: Long, channelId: Long, isPrivate: Boolean): Boolean {
        if (clanId == 0L || channelId == 0L) return true
        if (!isPrivate) {
            val v = checkPermission(VIEW_CHANNEL, null, clanId)
            Log.d(TAG_PRIVATE_CHANNEL, "canViewClanChannel public channelId=$channelId clanId=$clanId allow=$v (clan view-channel)")
            return v
        }
        if (checkPermission(VIEW_CHANNEL, null, clanId)) {
            Log.d(TAG_PRIVATE_CHANNEL, "canViewClanChannel private channelId=$channelId clanId=$clanId allow=true (clan view-channel)")
            return true
        }
        if (channelPermissionController.hasUserPermissionInChannel(channelId, VIEW_CHANNEL)) {
            Log.d(TAG_PRIVATE_CHANNEL, "canViewClanChannel private channelId=$channelId clanId=$clanId allow=true (channel override view-channel)")
            return true
        }
        val self = userController.userId
        if (self != 0L) {
            val inDirect = userClanController.getDirectChannelMembers(channelId).any { it.userId == self }
            val inChannel = userClanController.getChannelMembers(channelId).any { it.userId == self }
            if (inDirect || inChannel) {
                Log.d(
                    TAG_PRIVATE_CHANNEL,
                    "canViewClanChannel private channelId=$channelId clanId=$clanId allow=true selfId=$self inDirect=$inDirect inChannelUsers=$inChannel",
                )
                return true
            }
        }
        Log.d(TAG_PRIVATE_CHANNEL, "canViewClanChannel private channelId=$channelId clanId=$clanId allow=false")
        return false
    }

    fun resolvePrivateChannelListTap(
        clanIdForJoin: Long,
        channel: ClanChannelEntity,
        selectedClanId: Long,
        networkOnline: Boolean,
    ): PrivateChannelOpenResolution? {
        if (clanIdForJoin == 0L || !channel.isPrivate) return null
        if (selectedClanId != clanIdForJoin) return null
        ensurePrivateChannelAccessPrefetch(clanIdForJoin, channel.channelId, channel.type)
        if (!isPermissionDataReadyForClan(clanIdForJoin)) {
            return if (networkOnline) PrivateChannelOpenResolution.WaitPermissionData
            else PrivateChannelOpenResolution.NeedNetworkForPermission
        }
        if (!privateChannelViewEvidenceLoaded(channel.channelId)) {
            return if (networkOnline) PrivateChannelOpenResolution.WaitChannelEvidence
            else PrivateChannelOpenResolution.NeedNetworkForEvidence
        }
        return if (canViewClanChannel(clanIdForJoin, channel.channelId, channel.isPrivate)) {
            PrivateChannelOpenResolution.Proceed
        } else {
            PrivateChannelOpenResolution.DeniedNoView
        }
    }
}
