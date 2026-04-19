package ai.mezon.app.domain.usecase

import org.json.JSONObject
import java.util.Base64
import java.net.URLDecoder

sealed interface QrAction {
    data class DeepLink(val value: String) : QrAction
    data class Invite(val inviteId: String) : QrAction
    data class Profile(val username: String, val data: String?) : QrAction
    data class LuckyMoney(val luckyMoneyId: String) : QrAction
    data class Transfer(val rawJson: String) : QrAction
    data class Login(val loginId: String) : QrAction
    object Invalid : QrAction
}

class ParseQrValueUseCase {
    fun invoke(value: String): QrAction {
        if (value.contains("channel-app")) return QrAction.DeepLink(value)
        if (value.contains("/invite/")) {
            val id = value.substringAfterLast("/invite/")
            return QrAction.Invite(id)
        }
        if (value.contains("/chat/")) {
            val username = value.substringAfterLast("/chat/").substringBefore("?")
            val data = Regex("[?&]data=([^&]+)").find(value)?.groupValues?.getOrNull(1)
            return QrAction.Profile(username, data)
        }
        val json = try {
            JSONObject(value)
        } catch (e: Exception) { null }
        if (json != null) {
            if (json.has("lucky_money_id")) return QrAction.LuckyMoney(json.optString("lucky_money_id"))
            if (json.has("receiver_id") || json.has("wallet_address")) return QrAction.Transfer(value)
        }
        if (isSnowflakeLoginId(value)) return QrAction.Login(value)
        return QrAction.Invalid
    }

    private fun isSnowflakeLoginId(value: String): Boolean {
        return value.length in 16..20 && value.all { it.isDigit() }
    }
}

