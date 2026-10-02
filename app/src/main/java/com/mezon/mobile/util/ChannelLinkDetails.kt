package com.mezon.mobile.util

import com.mezon.mobile.home.clans.ClanChannelEntity
import org.json.JSONObject

fun addChannelLinkDetails(content: String, findChannel: (Long) -> ClanChannelEntity?): String {
    val obj = runCatching { JSONObject(content) }.getOrNull() ?: return content
    val text = obj.optString("t")
    if (text.isEmpty() || (obj.optJSONArray("mk") == null && obj.optJSONArray("hg") == null)) return content
    var enriched = false
    for (key in listOf("mk", "hg")) {
        val items = obj.optJSONArray(key) ?: continue
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val link = if (key == "mk") {
                val s = item.optInt("s", -1)
                val e = item.optInt("e", -1)
                if (s < 0 || e <= s || e > text.length) continue
                parseMezonChannelLink(text.substring(s, e)) ?: continue
            } else null
            val id = (link?.channelId ?: item.optString("channelId")).toLongOrNull() ?: continue
            val channel = findChannel(id) ?: continue
            if (channel.isPrivate || channel.clanId == 0L || channel.channelLabel.isBlank()) continue
            if (link != null && link.clanId != channel.clanId.toString()) continue
            enriched = true
            item.put("channelId", channel.channelId.toString())
            item.put("clanId", channel.clanId.toString())
            item.put("channelLabel", channel.channelLabel)
            item.put("channelType", channel.type)
            if (channel.parentId != 0L) item.put("parentId", channel.parentId.toString())
            else item.remove("parentId")
        }
    }
    return if (enriched) obj.toString() else content
}

internal fun ContentElement.matchesChannelLink(link: MezonChannelLink): Boolean =
    (clanId.isNullOrEmpty() || clanId == link.clanId) &&
        (channelId.isNullOrEmpty() || channelId == link.channelId)
