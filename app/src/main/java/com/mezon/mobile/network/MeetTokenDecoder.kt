package com.mezon.mobile.network

import com.google.protobuf.InvalidProtocolBufferException
import com.mezon.mezon.api.GenerateMeetTokenResponse

internal fun decodeMeetTokenResponse(bytes: ByteArray): GenerateMeetTokenResponse {
    val text = bytes.toString(Charsets.UTF_8).trim().trim('"')
    val response = if (text.startsWith("eyJ") && text.count { it == '.' } == 2) {
        GenerateMeetTokenResponse.newBuilder().setToken(text).build()
    } else {
        GenerateMeetTokenResponse.parseFrom(bytes)
    }
    if (response.token.isBlank()) {
        throw InvalidProtocolBufferException("GenerateMeetToken returned an empty token")
    }
    return response
}
