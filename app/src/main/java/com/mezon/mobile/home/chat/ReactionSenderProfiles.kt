package com.mezon.mobile.home.chat

import com.mezon.mobile.home.ClanMember

internal data class ReactionSenderProfile(
    val name: String,
    val username: String,
    val avatarUrl: String
)

internal class ReactionSenderProfiles(
    private val resolveMembers: (Set<Long>) -> Map<Long, ClanMember>
) {
    private var senderIds: List<Long> = emptyList()
    private var lookupIds: Set<Long> = emptySet()
    private var profiles: List<ReactionSenderProfile> = emptyList()

    fun setSenders(senders: List<ReactionSender>) {
        senderIds = senders.map { it.senderId }
        lookupIds = senderIds.filterTo(HashSet()) { it != 0L }
        profiles = resolveProfiles()
    }

    fun refresh(): List<Int> {
        val next = resolveProfiles()
        val changed = next.indices.filter { profiles[it] != next[it] }
        profiles = next
        return changed
    }

    operator fun get(position: Int): ReactionSenderProfile = profiles[position]

    private fun resolveProfiles(): List<ReactionSenderProfile> {
        val members = if (lookupIds.isEmpty()) emptyMap() else resolveMembers(lookupIds)
        return senderIds.map { senderId ->
            val member = members[senderId]
            ReactionSenderProfile(
                name = member?.let {
                    it.clanNick.ifBlank { it.displayName.ifBlank { it.username } }
                }.orEmpty().ifBlank { "User $senderId" },
                username = member?.username.orEmpty(),
                avatarUrl = member?.let { it.clanAvatar.ifBlank { it.avatarUrl } }.orEmpty()
            )
        }
    }
}
