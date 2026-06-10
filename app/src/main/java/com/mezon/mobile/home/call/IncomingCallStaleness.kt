package com.mezon.mobile.home.call

import org.json.JSONObject

object IncomingCallStaleness {
    const val MAX_AGE_MS = 30_000L

    fun resolveSentTimeMs(offerJson: String, fcmSentTimeMs: Long = 0L): Long? {
        val sentAt = parseSentAtFromPayload(offerJson)
        return when {
            sentAt != null -> sentAt
            fcmSentTimeMs > 0L -> fcmSentTimeMs
            else -> null
        }
    }

    fun isStaleOffer(offerJson: String, fcmSentTimeMs: Long = 0L): Boolean {
        val sentTimeMs = resolveSentTimeMs(offerJson, fcmSentTimeMs) ?: return false
        return System.currentTimeMillis() - sentTimeMs > MAX_AGE_MS
    }

    private fun parseSentAtFromPayload(offerJson: String): Long? =
        extractSentAt(offerJson) ?: run {
            try {
                extractSentAt(SdpCompressor.decompress(offerJson))
            } catch (_: Exception) {
                null
            }
        }

    private fun extractSentAt(json: String): Long? =
        try {
            JSONObject(json).optString("sentAt", "").toLongOrNull()?.takeIf { it > 0L }
        } catch (_: Exception) {
            null
        }
}
