package com.mezon.mobile.network

import com.google.protobuf.InvalidProtocolBufferException
import com.mezon.mezon.api.GenerateMeetTokenResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class MeetTokenDecoderTest {
    private val token = "eyJhbGciOiJIUzI1NiJ9.eyJyb29tIjoiMTIzIn0.test-signature"

    @Test fun acceptsRawJwt() {
        assertEquals(token, decodeMeetTokenResponse(token.toByteArray()).token)
    }

    @Test fun acceptsQuotedJwtWithWhitespace() {
        assertEquals(token, decodeMeetTokenResponse("  \"$token\"\n".toByteArray()).token)
    }

    @Test fun acceptsProtobuf() {
        val bytes = GenerateMeetTokenResponse.newBuilder().setToken(token).build().toByteArray()
        assertEquals(token, decodeMeetTokenResponse(bytes).token)
    }

    @Test(expected = InvalidProtocolBufferException::class)
    fun rejectsMalformedPayload() {
        decodeMeetTokenResponse(byteArrayOf(0x0a, 0x7f))
    }

    @Test(expected = InvalidProtocolBufferException::class)
    fun rejectsEmptyPayload() {
        decodeMeetTokenResponse(byteArrayOf())
    }

    @Test(expected = InvalidProtocolBufferException::class)
    fun rejectsBlankProtobufToken() {
        decodeMeetTokenResponse(GenerateMeetTokenResponse.newBuilder().setToken("  ").build().toByteArray())
    }
}
