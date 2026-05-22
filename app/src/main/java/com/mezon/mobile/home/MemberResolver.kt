package com.mezon.mobile.home

import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.messages.DmParticipant
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.network.STREAM_MODE_CHANNEL
import com.mezon.mobile.network.STREAM_MODE_THREAD
import com.mezon.mobile.network.channelTypeToStreamMode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemberResolver @Inject constructor(
    private val userController: UserController,
    private val userClanController: UserClanController,
    private val dialogsController: DialogsController,
    private val channelController: ChannelController
) {

    fun resolveMember(
        userId: Long,
        clanId: Long,
        channelId: Long,
        channelType: Int
    ): ClanMember? {
        if (userId == 0L) return null

        if (userId == userController.userId) {
            return buildSelfMemberInContext(clanId, channelId, channelType)
        }

        if (clanId != 0L) {
            val member = findClanOrChannelMember(userId, clanId, channelId, channelType)
            if (member != null) return member
        } else {
            val participant = findDmParticipant(userId, channelId)
            if (participant != null) return participant.toClanMember()
        }

        return userClanController.getUserById(userId)?.toClanMember()
    }

    fun resolveMembers(
        userIds: Collection<Long>,
        clanId: Long,
        channelId: Long,
        channelType: Int
    ): Map<Long, ClanMember> {
        if (userIds.isEmpty()) return emptyMap()
        val result = HashMap<Long, ClanMember>(userIds.size)

        val selfId = userController.userId
        if (selfId != 0L && selfId in userIds) {
            result[selfId] = buildSelfMemberInContext(clanId, channelId, channelType)
        }

        if (clanId != 0L) {
            val members = resolveChannelMemberList(clanId, channelId, channelType)
            for (m in members) {
                if (m.userId in userIds) result[m.userId] = m
            }
        } else {
            val participants = dialogsController.getParticipants(channelId)
            for (p in participants) {
                if (p.userId in userIds) result[p.userId] = p.toClanMember()
            }
        }

        for (id in userIds) {
            if (id in result) continue
            val user = userClanController.getUserById(id)
            if (user != null) result[id] = user.toClanMember()
        }

        return result
    }

    fun resolveChannelMembers(
        clanId: Long,
        channelId: Long,
        channelType: Int
    ): List<ClanMember> {
        if (clanId != 0L) return resolveChannelMemberList(clanId, channelId, channelType)
        val participants = dialogsController.getParticipants(channelId)
        return participants.map { it.toClanMember() }
    }

    fun resolveMentionMembers(
        clanId: Long,
        channelId: Long,
        channelType: Int
    ): List<ClanMember> {
        if (clanId == 0L) {
            return dialogsController.getParticipants(channelId).map { it.toClanMember() }
        }
        val ch = channelController.findChannelById(channelId, clanId)
        if (ch != null && (ch.isPrivate || ch.parentId != 0L)) {
            val targetChannelId = if (ch.parentId != 0L) ch.parentId else channelId
            val channelMembers = userClanController.getChannelMembers(targetChannelId)
            if (channelMembers.isNotEmpty()) return enrichMembers(clanId, channelMembers)
        }
        return userClanController.getClanMembers(clanId)
    }

    private fun buildSelfMemberInContext(clanId: Long, channelId: Long, channelType: Int): ClanMember {
        val uc = userController
        val selfId = uc.userId
        val globalFallback = ClanMember(
            userId = selfId,
            username = uc.username,
            displayName = uc.displayName,
            avatarUrl = uc.avatarUrl,
            isOnline = true,
            clanNick = "",
            clanAvatar = "",
            clanId = if (clanId != 0L) clanId else 0L,
            roleIds = emptyList()
        )
        if (clanId == 0L) return globalFallback
        val mode = channelTypeToStreamMode(channelType)
        val useClanPersona = mode == STREAM_MODE_CHANNEL || mode == STREAM_MODE_THREAD
        if (!useClanPersona) return globalFallback
        val member = resolveChannelMemberList(clanId, channelId, channelType).firstOrNull { it.userId == selfId }
        return member ?: globalFallback
    }

    private fun resolveChannelMemberList(clanId: Long, channelId: Long, channelType: Int): List<ClanMember> {
        val isThreadType = channelTypeToStreamMode(channelType) == STREAM_MODE_THREAD
        if (channelTypeToStreamMode(channelType) == STREAM_MODE_THREAD) {
            val directMembers = userClanController.getChannelMembers(channelId)
            if (directMembers.isNotEmpty()) return enrichMembers(clanId, directMembers)
        }
        val ch = channelController.findChannelById(channelId)
        val isThreadLike = isThreadType || (ch?.parentId ?: 0L) != 0L
        val isPrivateLike = ch?.isPrivate == true
        if (ch != null && (ch.isPrivate || ch.parentId != 0L)) {
            val directMembers = userClanController.getChannelMembers(channelId)
            if (directMembers.isNotEmpty()) return enrichMembers(clanId, directMembers)
            if (ch.parentId != 0L) {
                val parentMembers = userClanController.getChannelMembers(ch.parentId)
                if (parentMembers.isNotEmpty()) return enrichMembers(clanId, parentMembers)
            }
        }
        if (isThreadLike || isPrivateLike) return emptyList()
        return userClanController.getClanMembers(clanId)
    }

    private fun enrichMembers(clanId: Long, members: List<ClanMember>): List<ClanMember> {
        if (members.isEmpty()) return members
        val clanMap = HashMap<Long, ClanMember>()
        for (cm in userClanController.getClanMembers(clanId)) {
            clanMap[cm.userId] = cm
        }
        val enriched = ArrayList<ClanMember>(members.size)
        for (m in members) {
            val clanMember = clanMap[m.userId]
            val global = userClanController.getUserById(m.userId)
            val next = m.copy(
                username = m.username.ifBlank { clanMember?.username ?: global?.username.orEmpty() },
                displayName = m.displayName.ifBlank { clanMember?.displayName ?: global?.displayName.orEmpty() },
                avatarUrl = m.avatarUrl.ifBlank { clanMember?.avatarUrl ?: global?.avatarUrl.orEmpty() },
                clanNick = m.clanNick.ifBlank { clanMember?.clanNick.orEmpty() },
                clanAvatar = m.clanAvatar.ifBlank { clanMember?.clanAvatar?.ifBlank { clanMember.avatarUrl }.orEmpty() },
                roleIds = if (m.roleIds.isNotEmpty()) m.roleIds else clanMember?.roleIds.orEmpty()
            )
            enriched.add(next)
        }
        return enriched
    }

    private fun findClanOrChannelMember(userId: Long, clanId: Long, channelId: Long, channelType: Int): ClanMember? {
        val members = resolveChannelMemberList(clanId, channelId, channelType)
        return members.firstOrNull { it.userId == userId }
    }

    private fun findDmParticipant(userId: Long, channelId: Long): DmParticipant? {
        return dialogsController.getParticipants(channelId).firstOrNull { it.userId == userId }
    }
}

private fun DmParticipant.toClanMember(): ClanMember = ClanMember(
    userId = userId,
    username = username,
    displayName = displayName,
    avatarUrl = avatarUrl,
    isOnline = false,
    clanNick = "",
    clanAvatar = "",
    clanId = 0L,
    roleIds = emptyList()
)

private fun ClanUser.toClanMember(): ClanMember = ClanMember(
    userId = id,
    username = username,
    displayName = displayName,
    avatarUrl = avatarUrl,
    isOnline = isOnline,
    clanNick = "",
    clanAvatar = "",
    clanId = 0L,
    roleIds = emptyList()
)
