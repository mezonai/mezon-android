package com.mezon.mobile.qr

import java.net.URI
import java.util.Base64
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

sealed interface QrAction {
    data class DeepLink(val value: String) : QrAction
    data class Invite(val inviteId: String) : QrAction
    data class Profile(val username: String, val data: String?) : QrAction
    data class LuckyMoney(val luckyMoneyId: String) : QrAction
    data class Transfer(val rawJson: String) : QrAction
    data class Login(val loginId: String) : QrAction
    data object Invalid : QrAction
}

data class ProfilePayload(
    val id: Long,
    val avatar: String,
    val name: String
)

fun parseQrValue(value: String): QrAction {
    if (value.contains("channel-app", ignoreCase = true)) return QrAction.DeepLink(value)
    val uri = runCatching { URI(value) }.getOrNull()
    val path = uri?.path.orEmpty()
    if (path.contains("/invite/")) {
        val inviteId = path.substringAfter("/invite/").substringBefore("/").substringBefore("?")
        if (inviteId.isNotBlank()) return QrAction.Invite(inviteId)
    }
    if (path.contains("/chat/")) {
        val username = path.substringAfter("/chat/").substringBefore("/").substringBefore("?")
        val data = uri?.let { getQueryParam(it, "data") }
        if (username.isNotBlank()) return QrAction.Profile(username, data)
    }
    val json = runCatching { JSONObject(value) }.getOrNull()
    if (json != null) {
        val luckyMoney = json.optString("lucky_money_id")
        if (luckyMoney.isNotBlank()) return QrAction.LuckyMoney(luckyMoney)
        val receiverId = json.optString("receiver_id")
        val walletAddress = json.optString("wallet_address")
        if (receiverId.isNotBlank() || walletAddress.isNotBlank()) return QrAction.Transfer(value)
    }
    if (isSnowflakeLoginId(value)) return QrAction.Login(value)
    return QrAction.Invalid
}

fun buildProfileQrValue(redirectUri: String, username: String, payload: ProfilePayload): String {
    val json = JSONObject()
        .put("id", payload.id)
        .put("avatar", payload.avatar)
        .put("name", payload.name)
        .toString()
    val encoded = URLEncoder.encode(json, "UTF-8")
    val base64 = Base64.getEncoder().encodeToString(encoded.toByteArray(Charsets.UTF_8))
    return "$redirectUri/chat/$username?data=$base64"
}

fun buildTransferPayload(username: String, userId: Long): String {
    return JSONObject()
        .put("receiver_name", username)
        .put("receiver_id", userId)
        .toString()
}

fun decodeProfilePayload(encoded: String?): ProfilePayload? {
    if (encoded.isNullOrBlank()) return null
    val decoded = runCatching {
        val bytes = Base64.getDecoder().decode(encoded)
        val urlDecoded = URLDecoder.decode(String(bytes, Charsets.UTF_8), "UTF-8")
        JSONObject(urlDecoded)
    }.getOrNull() ?: return null
    val id = decoded.optLong("id", 0L)
    val avatar = decoded.optString("avatar")
    val name = decoded.optString("name")
    if (id == 0L || name.isBlank()) return null
    return ProfilePayload(id = id, avatar = avatar, name = name)
}

fun isSnowflakeLoginId(value: String): Boolean {
    if (value.length < 12 || value.length > 22) return false
    val number = value.toLongOrNull() ?: return false
    return number > 0
}

private fun getQueryParam(uri: URI, key: String): String? {
    val query = uri.rawQuery ?: return null
    return query.split("&").firstOrNull { it.startsWith("$key=") }?.substringAfter("=")
}

