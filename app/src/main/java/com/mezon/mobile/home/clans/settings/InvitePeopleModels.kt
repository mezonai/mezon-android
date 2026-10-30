package com.mezon.mobile.home.clans.settings

import com.mezon.mobile.BuildConfig
import com.mezon.mobile.network.LinkInvitePreview
import com.mezon.mezon.api.LinkInviteUser
import org.json.JSONArray
import org.json.JSONObject

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

fun buildInviteLinkContent(
    url: String,
    preview: LinkInvitePreview?,
    memberCountDescription: String? = null,
): String {
    val end = url.length
    val markdown = JSONArray().put(
        JSONObject().put("type", "lk").put("s", 0).put("e", end)
    )
    if (preview != null) {
        markdown.put(
            JSONObject()
                .put("type", "lk_ogp")
                .put("s", end)
                .put("e", end + 1)
                .put("index", 0)
                .put("title", preview.clanName)
                .put("description", memberCountDescription
                    ?: preview.channelLabel.ifBlank { preview.clanName })
                .put("image", preview.logoUrl)
                .put("url", url)
                .put("clanId", preview.clanId)
                .put("member_count", preview.memberCount)
                .put("banner", preview.banner)
                .put("is_community", preview.isCommunity)
        )
    }
    return JSONObject().put("t", url).put("mk", markdown).toString()
}
