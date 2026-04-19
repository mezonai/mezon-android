package com.mezon.mobile.navigation

object NavRoutes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val CHAT = "chat/{channelId}/{channelName}/{clanId}/{channelType}"
    const val COMPONENT_PREVIEW = "component_preview"
    const val QR_SCANNER = "qr_scanner"
    const val MY_QR = "my_qr"
    const val CONFIRM_LOGIN = "confirm_login"
    const val CONFIRM_TRANSFER = "confirm_transfer"

    fun chatRoute(
        channelId: Long,
        channelName: String,
        clanId: Long = 0L,
        channelType: Int = 0
    ) = "chat/$channelId/${channelName.encodeUrlParam()}/$clanId/$channelType"

    private fun String.encodeUrlParam() = java.net.URLEncoder.encode(this, "UTF-8")
}
