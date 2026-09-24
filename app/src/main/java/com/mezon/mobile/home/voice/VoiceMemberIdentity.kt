package com.mezon.mobile.home.voice

import com.mezon.mobile.BuildConfig
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.ClanUser

object VoiceAgent {
    const val DISPLAY_NAME = "KOMU Agent"
    const val AVATAR_URL =
        "https://cdn.mezon.vn/0/0/1779484387973271600/1737423959329_undefined173740153013517374015248704886401586613166392.png"

    private val ids: Set<String> = BuildConfig.MEZON_VOICE_AGENT_ID
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    fun isAgent(userId: String): Boolean = userId.isNotEmpty() && userId in ids

    fun isAgent(userId: Long): Boolean = isAgent(userId.toString())
}

data class VoiceMemberIdentity(
    val displayName: String,
    val username: String,
    val avatarUrl: String?
)

fun resolveVoiceMemberIdentity(
    userId: Long,
    member: ClanMember?,
    user: ClanUser?,
    fallbackName: String
): VoiceMemberIdentity {
    if (VoiceAgent.isAgent(userId)) {
        return VoiceMemberIdentity(
            VoiceAgent.DISPLAY_NAME,
            VoiceAgent.DISPLAY_NAME,
            VoiceAgent.AVATAR_URL
        )
    }

    val name = member?.clanNick?.ifBlank { null }
        ?: member?.displayName?.ifBlank { null }
        ?: member?.username?.ifBlank { null }
        ?: user?.displayName?.ifBlank { null }
        ?: user?.username?.ifBlank { null }
    val username = member?.username?.ifBlank { null } ?: user?.username.orEmpty()
    val avatarUrl = member?.clanAvatar?.ifBlank { null }
        ?: member?.avatarUrl?.ifBlank { null }
        ?: user?.avatarUrl?.ifBlank { null }

    return VoiceMemberIdentity(name ?: fallbackName, username, avatarUrl)
}
