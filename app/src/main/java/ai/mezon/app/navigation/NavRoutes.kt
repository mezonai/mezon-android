package ai.mezon.app.navigation

object NavRoutes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val CHAT = "chat/{channelId}/{channelName}/{clanId}/{channelType}"

    fun chatRoute(
        channelId: Long,
        channelName: String,
        clanId: Long = 0L,
        channelType: Int = 0
    ) = "chat/$channelId/${channelName.encodeUrlParam()}/$clanId/$channelType"

    private fun String.encodeUrlParam() = java.net.URLEncoder.encode(this, "UTF-8")
}
