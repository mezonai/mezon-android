package com.mezon.mobile.home.clans.channelapp

data class ChannelAppUiModel(
    val channelId: Long,
    val clanId: Long,
    val appId: Long,
    val appUrl: String,
    val appName: String,
    val appLogo: String
) {
    companion object {
        fun fromEntity(entity: ChannelAppEntity): ChannelAppUiModel = ChannelAppUiModel(
            channelId = entity.channelId,
            clanId = entity.clanId,
            appId = entity.appId,
            appUrl = entity.appUrl,
            appName = entity.appName,
            appLogo = entity.appLogo
        )
    }
}

fun ChannelAppUiModel.toEntity(sortOrder: Int): ChannelAppEntity = ChannelAppEntity(
    channelId = channelId,
    clanId = clanId,
    appId = appId,
    appUrl = appUrl,
    appName = appName,
    appLogo = appLogo,
    sortOrder = sortOrder
)
