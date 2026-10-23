package com.mezon.mobile.home.voice

import android.net.Uri
import android.util.Base64
import com.mezon.mobile.BuildConfig

data class ExternalMeetingRoom(
    val code: String,
    val title: String,
    val roomId: Long
) {
    companion object {
        fun fromExternalLink(externalLink: String, title: String): ExternalMeetingRoom? {
            val link = externalLink.trim()
            if (link.isEmpty()) return null
            val absolute = if (link.startsWith("http://", ignoreCase = true) || link.startsWith("https://", ignoreCase = true)) {
                link
            } else {
                "${BuildConfig.MEZON_REDIRECT_URI.trimEnd('/')}/${link.trimStart('/')}"
            }
            val uri = runCatching { Uri.parse(absolute) }.getOrNull() ?: return null
            if (uri.host.isNullOrEmpty()) return null
            return fromUri(uri, title)
        }

        fun fromMeetingUrl(url: String): ExternalMeetingRoom? {
            val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "https" && scheme != "http") return null
            val host = uri.host?.lowercase() ?: return null
            val configuredHost = runCatching { Uri.parse(BuildConfig.MEZON_REDIRECT_URI).host?.lowercase() }.getOrNull()
            if (host != "mezon.ai" && !host.endsWith(".mezon.ai") && host != configuredHost) return null
            return fromUri(uri, "")
        }

        fun fromCode(code: String, title: String): ExternalMeetingRoom? {
            if (code.isBlank()) return null
            return ExternalMeetingRoom(code, title.trim(), roomIdFor(code))
        }

        fun sfuIdentity(username: String?, displayName: String?, userId: Long): String {
            for (candidate in listOf(username, displayName)) {
                val identity = candidate.orEmpty().lowercase()
                    .filter { it in 'a'..'z' || it in '0'..'9' }
                    .take(12)
                if (identity.isNotEmpty()) return identity
            }
            if (userId > 0L) return userId.toString(36).take(12)
            return "guest"
        }

        private fun fromUri(uri: Uri, title: String): ExternalMeetingRoom? {
            val scheme = uri.scheme?.lowercase()
            if (scheme != "https" && scheme != "http") return null
            val segments = uri.pathSegments
            if (segments.size != 2 || !segments[0].equals("meeting", ignoreCase = true)) return null
            return fromCode(segments[1], title)
        }

        private fun roomIdFor(code: String): Long {
            val payload = code.substringBefore('.')
            val decoded = runCatching {
                String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrNull()
            val meetId = decoded?.substringAfterLast(':')?.toLongOrNull()
            if (meetId != null && meetId > 0L) return meetId
            var hash = 0xcbf29ce484222325uL
            for (byte in code.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (byte.toInt() and 0xff).toULong()
                hash *= 0x100000001b3uL
            }
            return (hash and Long.MAX_VALUE.toULong()).toLong() or 1L
        }
    }
}
