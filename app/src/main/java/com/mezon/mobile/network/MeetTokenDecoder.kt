package com.mezon.mobile.network

import com.google.protobuf.InvalidProtocolBufferException
import com.mezon.mezon.api.GenerateMeetTokenResponse
import java.net.URI

private const val MEET_TOKEN_PROTOBUF_FIELD_TAG: Byte = 0x0A

internal fun decodeMeetTokenResponse(bytes: ByteArray): GenerateMeetTokenResponse {
    if (bytes.firstOrNull() == MEET_TOKEN_PROTOBUF_FIELD_TAG) {
        val response = runCatching { GenerateMeetTokenResponse.parseFrom(bytes) }.getOrNull()
        if (response != null && response.token.isNotBlank()) return response
    }
    val text = bytes.toString(Charsets.UTF_8).trim().trim('"')
    if (text.startsWith("eyJ") && text.count { it == '.' } == 2) {
        return GenerateMeetTokenResponse.newBuilder().setToken(text).build()
    }
    throw InvalidProtocolBufferException("GenerateMeetToken response is neither a protobuf token nor a JWT")
}

internal fun normalizedSfuWsUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val uri = runCatching { URI(if (trimmed.contains("://")) trimmed else "wss://$trimmed") }.getOrNull() ?: return null
    val scheme = when (uri.scheme?.lowercase()) {
        "ws", "http" -> "ws"
        "wss", "https" -> "wss"
        else -> return null
    }
    val authority = uri.rawAuthority?.takeIf { uri.host != null } ?: return null
    val path = uri.rawPath?.takeUnless { it.isEmpty() || it == "/" } ?: "/ws"
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    return "$scheme://$authority$path$query"
}
