package com.mezon.mobile.home.voice.sfu

import org.json.JSONObject

data class SfuPeerProfile(
    val name: String?,
    val avatarUrl: String?
) {
    companion object {
        fun fromMetadata(metadata: String?): SfuPeerProfile? {
            val raw = metadata?.trim().orEmpty()
            if (raw.isEmpty()) return null
            var name: String? = null
            var avatar: String? = null
            if (raw.startsWith("{")) {
                val json = runCatching { JSONObject(raw) }.getOrNull()
                if (json != null) {
                    name = field(json.opt("username"))
                    avatar = field(json.opt("avatar"))
                }
            } else {
                val separator = raw.lastIndexOf(';')
                if (separator >= 0) {
                    name = field(raw.substring(0, separator))
                    avatar = field(raw.substring(separator + 1))
                } else {
                    name = field(raw)
                }
            }
            if (name == null && avatar == null) return null
            return SfuPeerProfile(name, avatar)
        }

        private fun field(value: Any?): String? {
            val text = (value as? String)?.trim() ?: return null
            return text.takeIf { it.isNotEmpty() && it != "undefined" }
        }
    }
}
