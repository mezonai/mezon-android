package com.mezon.mobile.network

import com.google.protobuf.InvalidProtocolBufferException
import com.mezon.mezon.api.GenerateMeetTokenResponse

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
