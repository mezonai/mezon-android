package com.mezon.mobile.home.clans.settings

data class CommunityClanState(
    val clanId: Long = 0L,
    val isCommunityEnabled: Boolean = false,
    val communityBannerUrl: String = "",
    val about: String = "",
    val description: String = "",
    val shortUrl: String = "",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
)

data class CommunityFormDraft(
    val about: String = "",
    val description: String = "",
    val shortUrl: String = "",
    val bannerPreviewUrl: String? = null,
    val pendingBannerBytes: ByteArray? = null,
    val pendingBannerMime: String? = null,
    val pendingBannerExt: String = "jpg",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CommunityFormDraft) return false
        return about == other.about &&
            description == other.description &&
            shortUrl == other.shortUrl &&
            bannerPreviewUrl == other.bannerPreviewUrl &&
            pendingBannerBytes.contentEquals(other.pendingBannerBytes) &&
            pendingBannerMime == other.pendingBannerMime &&
            pendingBannerExt == other.pendingBannerExt
    }

    override fun hashCode(): Int {
        var result = about.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + shortUrl.hashCode()
        result = 31 * result + (bannerPreviewUrl?.hashCode() ?: 0)
        result = 31 * result + (pendingBannerBytes?.contentHashCode() ?: 0)
        result = 31 * result + (pendingBannerMime?.hashCode() ?: 0)
        result = 31 * result + pendingBannerExt.hashCode()
        return result
    }
}

private fun ByteArray?.contentEquals(other: ByteArray?): Boolean {
    if (this == null && other == null) return true
    if (this == null || other == null) return false
    return this.contentEquals(other)
}

enum class CommunityScreenMode {
    LOADING,
    LANDING,
    ENABLE_FORM,
    ENABLED_EDITOR,
}

data class CommunityUiState(
    val mode: CommunityScreenMode = CommunityScreenMode.LOADING,
    val server: CommunityClanState = CommunityClanState(),
    val draft: CommunityFormDraft = CommunityFormDraft(),
    val initial: CommunityFormDraft = CommunityFormDraft(),
    val showSaveBar: Boolean = false,
    val fieldErrors: CommunityFieldErrors = CommunityFieldErrors(),
)

data class CommunityFieldErrors(
    val banner: Boolean = false,
    val about: Boolean = false,
    val description: Boolean = false,
    val shortUrl: Boolean = false,
)

fun CommunityFormDraft.hasChangesComparedTo(initial: CommunityFormDraft): Boolean =
    about != initial.about ||
        description != initial.description ||
        shortUrl != initial.shortUrl ||
        bannerPreviewUrl != initial.bannerPreviewUrl ||
        pendingBannerBytes != null

fun sanitizeVanity(input: String): String =
    input.lowercase().replace(Regex("[^a-z0-9-]"), "")
