package com.mezon.mobile.home.notifications

import org.json.JSONObject

private const val SHARE_CONTACT_VALUE = "share_contact"

internal fun topicPreviewDisplayText(
    content: String,
    fileBracketLabel: String,
    contactBracketLabel: String
): String {
    if (content.isEmpty()) return ""
    val trimmed = content.trimStart()
    if (!trimmed.startsWith("{")) return content
    return try {
        val obj = JSONObject(content)
        val t = obj.optString("t", "")
        if (t.isNotEmpty()) return t
        if (isShareContactEmbed(obj)) return contactBracketLabel
        fileBracketLabel
    } catch (_: Exception) {
        content
    }
}

private fun isShareContactEmbed(obj: JSONObject): Boolean {
    val embed = obj.optJSONArray("embed") ?: return false
    val first = embed.optJSONObject(0) ?: return false
    val fields = first.optJSONArray("fields") ?: return false
    val field0 = fields.optJSONObject(0) ?: return false
    return field0.optString("value", "") == SHARE_CONTACT_VALUE
}
