package com.mezon.mobile.deeplink

import android.net.Uri

sealed class DeepLinkRoute {
    data class ChannelApp(
        val channelId: Long,
        val clanId: Long,
        val code: String?,
        val subpath: String?
    ) : DeepLinkRoute()

    data class Invite(val inviteId: Long) : DeepLinkRoute()
    data class Profile(val username: String, val data: String?) : DeepLinkRoute()
    data class BotInstall(val appId: Long) : DeepLinkRoute()
    data class AppInstall(val appId: Long) : DeepLinkRoute()
    data class Login(val loginId: Long) : DeepLinkRoute()
}

enum class InstallKind {
    BOT,
    APP,
}

object DeepLinkParser {

    private val CHANNEL_APP_REGEX = Regex("""channel-app/(\d+)/(\d+)(?:\?[^#]*)?""")
    private val INVITE_REGEX = Regex("""invite/(?:chat/)?(\d+)""")
    private val APP_INSTALL_REGEX = Regex("""(?:^|/)app/install/(\d+)""")
    private val BOT_INSTALL_REGEX = Regex("""(?:^|/)bot/install/(\d+)""")
    private val CHAT_USERNAME_REGEX = Regex("""(?:^|/)chat/([^/?#]+)""")
    private val CODE_REGEX = Regex("""[?&]code=([^&]+)""")
    private val SUBPATH_REGEX = Regex("""[?&]subpath=([^&]+)""")
    private val LOGIN_REGEX = Regex("""(?:^|/)login/(\d+)""")
    private val LOGIN_ID_QUERY_REGEX = Regex("""[?&](?:login_id|loginId)=(\d+)""")

    fun parse(uri: Uri): DeepLinkRoute? {
        val builder = StringBuilder()
        val host = uri.host
        if (!host.isNullOrBlank()) {
            builder.append(host)
            if (!uri.path.isNullOrBlank()) builder.append(uri.path)
        } else {
            builder.append(uri.schemeSpecificPart.removePrefix("//"))
        }
        val query = uri.encodedQuery
        if (!query.isNullOrBlank()) {
            builder.append('?').append(query)
        }
        return parse(builder.toString()) ?: parse(uri.toString())
    }

    fun parse(input: String): DeepLinkRoute? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.contains("login/") || trimmed.contains("login_id=") || trimmed.contains("loginId=")) {
            LOGIN_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let {
                return DeepLinkRoute.Login(it)
            }
            LOGIN_ID_QUERY_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let {
                return DeepLinkRoute.Login(it)
            }
        }

        if (trimmed.contains("channel-app/")) {
            val match = CHANNEL_APP_REGEX.find(trimmed) ?: return null
            val channelId = match.groupValues[1].toLongOrNull() ?: return null
            val clanId = match.groupValues[2].toLongOrNull() ?: return null
            val code = CODE_REGEX.find(trimmed)?.groupValues?.getOrNull(1)
            val subpath = SUBPATH_REGEX.find(trimmed)?.groupValues?.getOrNull(1)
            return DeepLinkRoute.ChannelApp(channelId, clanId, code, subpath)
        }

        if (trimmed.contains("/invite/")) {
            val match = INVITE_REGEX.find(trimmed) ?: return null
            val inviteId = match.groupValues[1].toLongOrNull() ?: return null
            return DeepLinkRoute.Invite(inviteId)
        }

        if (trimmed.contains("/chat/")) {
            val match = CHAT_USERNAME_REGEX.find(trimmed) ?: return null
            val username = match.groupValues[1]
            if (username.isBlank()) return null
            val data = runCatching { Uri.parse(trimmed).getQueryParameter("data") }.getOrNull()
            return DeepLinkRoute.Profile(username, data)
        }

        if (trimmed.contains("app/install/")) {
            val match = APP_INSTALL_REGEX.find(trimmed) ?: return null
            val appId = match.groupValues[1].toLongOrNull() ?: return null
            return DeepLinkRoute.AppInstall(appId)
        }

        if (trimmed.contains("bot/install/")) {
            val match = BOT_INSTALL_REGEX.find(trimmed) ?: return null
            val appId = match.groupValues[1].toLongOrNull() ?: return null
            return DeepLinkRoute.BotInstall(appId)
        }

        return null
    }

    fun installKindFromUrl(input: String?): InstallKind? {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains("app/install/")) return InstallKind.APP
        if (trimmed.contains("bot/install/")) return InstallKind.BOT
        return null
    }
}
