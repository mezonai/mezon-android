package com.mezon.mobile.home.clans.settings

import com.mezon.mobile.BuildConfig
import com.mezon.mobile.network.LinkInvitePreview
import com.mezon.mezon.api.LinkInviteUser

data class InvitePeopleUiState(
    val clanId: Long = 0L,
    val clanName: String = "",
    val clanLogo: String = "",
    val welcomeChannelId: Long = 0L,
    val inviteUrl: String = "",
    val inviteToken: String = "",
    val isLoadingLink: Boolean = false,
    val isLoadingTargets: Boolean = false,
    val linkError: String? = null,
    val searchQuery: String = "",
    val dmTargets: List<InviteDmTarget> = emptyList(),
    val sentTargetIds: Set<String> = emptySet(),
    val sendingTargetId: String? = null,
)

data class InviteDmTarget(
    val rowId: String,
    val channelId: Long?,
    val channelType: Int,
    val userId: Long?,
    val title: String,
    val subtitle: String?,
    val avatarUrl: String?,
)

fun LinkInviteUser.toShareableInviteUrl(domainBase: String = BuildConfig.MEZON_DOMAIN_URL): String {
    val base = domainBase.trimEnd('/')
    val token = inviteLink.trim()
    return "$base/invite/$token"
}

fun parseInviteIdFromUrl(url: String): Long? {
    val regex = Regex("""/invite/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
    val raw = regex.find(url)?.groupValues?.getOrNull(1) ?: return null
    return raw.toLongOrNull()
}

private fun String.escapeJson(): String = replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

fun buildInviteLinkContent(
    url: String,
    preview: LinkInvitePreview?,
    memberCountDescription: String? = null,
): String {
    val escaped = url.escapeJson()
    val end = url.length
    if (preview == null) {
        return """{"t":"$escaped","mk":[{"type":"lk","s":0,"e":$end}]}"""
    }
    val title = preview.clanName.escapeJson()
    val description = (memberCountDescription
        ?: preview.channelLabel.ifBlank { preview.clanName }).escapeJson()
    val image = preview.logoUrl.escapeJson()
    return """{"t":"$escaped","mk":[{"type":"lk","s":0,"e":$end},{"type":"lk_ogp","s":$end,"e":${end + 1},"index":0,"title":"$title","description":"$description","image":"$image","url":"$escaped"}]}"""
}
