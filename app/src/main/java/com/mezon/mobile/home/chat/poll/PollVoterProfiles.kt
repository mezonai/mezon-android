package com.mezon.mobile.home.chat.poll

import com.mezon.mobile.home.ClanMember

internal data class PollVoterProfile(
    val userId: Long,
    val display: String,
    val username: String,
    val avatarUrl: String
)

internal class PollVoterProfiles(
    private val resolveMembers: (Set<Long>) -> Map<Long, ClanMember>
) {
    private var voterIds: List<Long> = emptyList()
    private var lookupIds: Set<Long> = emptySet()
    private var profiles: List<PollVoterProfile> = emptyList()

    val size: Int get() = profiles.size

    fun setVoters(ids: List<Long>) {
        voterIds = ids
        lookupIds = ids.filterTo(HashSet()) { it != 0L }
        profiles = resolveProfiles()
    }

    fun refresh(): List<Int> {
        val next = resolveProfiles()
        val changed = next.indices.filter { profiles[it] != next[it] }
        profiles = next
        return changed
    }

    operator fun get(position: Int): PollVoterProfile = profiles[position]

    private fun resolveProfiles(): List<PollVoterProfile> {
        val members = if (lookupIds.isEmpty()) emptyMap() else resolveMembers(lookupIds)
        return voterIds.map { id ->
            val member = members[id]
            PollVoterProfile(
                userId = id,
                display = member?.let {
                    it.clanNick.ifBlank { it.displayName.ifBlank { it.username } }
                }.orEmpty().ifEmpty { id.toString() },
                username = member?.username.orEmpty().ifEmpty { id.toString() },
                avatarUrl = member?.let { it.clanAvatar.ifBlank { it.avatarUrl } }.orEmpty()
            )
        }
    }
}
