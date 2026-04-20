package com.mezon.mobile.home.qr

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder

sealed class QrAction {
    data class DeepLink(val url: String) : QrAction()
    data class Invite(val code: String) : QrAction()
    data class Profile(val username: String, val data: String?) : QrAction()
    data class LuckyMoney(val id: String) : QrAction()
    data class Transfer(val rawJson: String) : QrAction()
    data class Login(val loginId: Long) : QrAction()
    object Invalid : QrAction()
}

data class ProfilePayload(
    val id: Long,
    val avatar: String,
    val name: String
)

data class TransferPayload(
    val receiverName: String,
    val receiverId: Long
)

object QrPayloadParser {

    fun parse(value: String): QrAction {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return QrAction.Invalid

        if (trimmed.contains("channel-app")) return QrAction.DeepLink(trimmed)

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
        if (uri != null) {
            val path = uri.path.orEmpty()
            if (path.contains("/invite/")) {
                val invite = path.substringAfter("/invite/").substringBefore("/")
                if (invite.isNotBlank()) return QrAction.Invite(invite)
            }
            if (path.contains("/chat/")) {
                val username = path.substringAfter("/chat/").substringBefore("/")
                if (username.isNotBlank()) return QrAction.Profile(username, uri.getQueryParameter("data"))
            }
        }

        val json = runCatching { JSONObject(trimmed) }.getOrNull()
        if (json != null) {
            if (json.has("lucky_money_id")) return QrAction.LuckyMoney(json.optString("lucky_money_id"))
            if (json.has("receiver_id") || json.has("wallet_address")) return QrAction.Transfer(trimmed)
        }

        val loginId = parseLoginId(trimmed)
        if (loginId != null) return QrAction.Login(loginId)

        return QrAction.Invalid
    }

    fun decodeProfilePayload(encoded: String?): ProfilePayload? {
        if (encoded.isNullOrBlank()) return null
        return runCatching {
            val decodedBase64 = Base64.decode(encoded, Base64.NO_WRAP)
            val urlDecoded = URLDecoder.decode(String(decodedBase64), "UTF-8")
            val obj = JSONObject(urlDecoded)
            ProfilePayload(
                id = obj.optLong("id"),
                avatar = obj.optString("avatar"),
                name = obj.optString("name")
            )
        }.getOrNull()
    }

    private fun parseLoginId(raw: String): Long? {
        if (raw.length !in 10..22) return null
        if (!raw.all { it.isDigit() }) return null
        return runCatching { raw.toLong() }.getOrNull()
    }
}

