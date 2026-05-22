package com.mezon.mobile.util

import com.mezon.mobile.home.chat.MessageEntity
import org.json.JSONArray
import org.json.JSONObject

const val SHARE_CONTACT_KEY = "share_contact"

data class ShareContactData(
    val userId: Long,
    val username: String,
    val displayName: String,
    val avatarUrl: String
)

fun isShareContactMessage(code: Int, content: String): Boolean {
    if (code == MessageEntity.CODE_SHARE_CONTACT) return true
    if (!content.contains(SHARE_CONTACT_KEY)) return false
    return parseShareContactData(content) != null
}

fun parseShareContactData(content: String): ShareContactData? {
    if (content.isBlank() || !content.contains(SHARE_CONTACT_KEY)) return null
    return try {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{")) return null
        val obj = JSONObject(trimmed)
        val embedArr = obj.optJSONArray("embed") ?: return null
        if (embedArr.length() == 0) return null
        val embed = embedArr.optJSONObject(0) ?: return null
        val fields = embed.optJSONArray("fields") ?: return null
        var keyVal: String? = null
        var userIdStr = ""
        var username = ""
        var displayName = ""
        var avatar = ""
        for (i in 0 until fields.length()) {
            val f = fields.optJSONObject(i) ?: continue
            when (f.optString("name", "")) {
                "key" -> keyVal = f.optString("value", "").trim()
                "user_id" -> userIdStr = f.optString("value", "").trim()
                "username" -> username = f.optString("value", "").trim()
                "display_name" -> displayName = f.optString("value", "").trim()
                "avatar" -> avatar = f.optString("value", "").trim()
            }
        }
        if (keyVal != SHARE_CONTACT_KEY) return null
        val userId = userIdStr.toLongOrNull() ?: return null
        if (username.isBlank()) return null
        ShareContactData(
            userId = userId,
            username = username,
            displayName = displayName.ifBlank { username },
            avatarUrl = avatar
        )
    } catch (_: Exception) {
        null
    }
}

fun buildShareContactContent(data: ShareContactData): String {
    val fields = JSONArray().apply {
        put(fieldJson("key", SHARE_CONTACT_KEY))
        put(fieldJson("user_id", data.userId.toString()))
        put(fieldJson("username", data.username))
        put(fieldJson("display_name", data.displayName))
        put(fieldJson("avatar", data.avatarUrl))
    }
    val embed = JSONObject().apply {
        put("fields", fields)
    }
    return JSONObject().apply {
        put("t", "")
        put("embed", JSONArray().put(embed))
    }.toString()
}

fun mergeShareContactEmbedIntoContent(baseContentJson: String, originalContent: String): String {
    val embedArr = try {
        val orig = JSONObject(originalContent.trim())
        orig.optJSONArray("embed") ?: return baseContentJson
    } catch (_: Exception) {
        return baseContentJson
    }
    return try {
        val base = JSONObject(baseContentJson)
        base.put("embed", embedArr)
        base.toString()
    } catch (_: Exception) {
        baseContentJson
    }
}

private fun fieldJson(name: String, value: String): JSONObject =
    JSONObject().apply {
        put("name", name)
        put("value", value)
        put("inline", true)
    }
