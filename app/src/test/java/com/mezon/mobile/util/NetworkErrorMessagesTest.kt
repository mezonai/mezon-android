package com.mezon.mobile.util

import android.content.Context
import com.mezon.mobile.R
import com.mezon.mobile.network.HttpRpcStatusException
import com.mezon.mobile.network.SocketRpcServerException
import io.mockk.every
import io.mockk.mockk
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkErrorMessagesTest {

    private val context = mockk<Context>()

    @Test
    fun `unmapped error returns fallback when raw message not allowed`() {
        val error = IllegalStateException("boom")
        val result = NetworkErrorMessages.userMessage(context, error, "fallback", allowRawMessage = false)
        assertEquals("fallback", result)
    }

    @Test
    fun `unmapped error returns raw message by default`() {
        val error = IllegalStateException("boom")
        val result = NetworkErrorMessages.userMessage(context, error, "fallback")
        assertEquals("boom", result)
    }

    @Test
    fun `http error returns server message`() {
        val error = HttpRpcStatusException(
            "RPC UpdateUsername failed (400): {\"code\":3,\"message\":\"Username is invalid\"}",
            400,
            serverMessage = "Username is invalid"
        )
        val result = NetworkErrorMessages.userMessage(context, error, "fallback", allowRawMessage = false)
        assertEquals("Username is invalid", result)
    }

    @Test
    fun `http error without server message returns status code message`() {
        every { context.getString(R.string.error_server_code, 500) } returns "Server error (code 500)"
        val error = HttpRpcStatusException("RPC UpdateUsername failed (500): boom", 500)
        val result = NetworkErrorMessages.userMessage(context, error, "fallback")
        assertEquals("Server error (code 500)", result)
    }

    @Test
    fun `socket error returns server message`() {
        val error = SocketRpcServerException("Server error code=404 msg='Not found.'", 404, serverMessage = "Not found.")
        val result = NetworkErrorMessages.userMessage(context, error, "fallback")
        assertEquals("Not found.", result)
    }

    @Test
    fun `socket error without server message maps grpc code`() {
        every { context.getString(R.string.error_server_permission_denied) } returns "no permission"
        val error = SocketRpcServerException("Server error code=7 msg=''", 7)
        val result = NetworkErrorMessages.userMessage(context, error, "fallback")
        assertEquals("no permission", result)
    }

    @Test
    fun `socket error with unknown grpc code returns code message`() {
        every { context.getString(R.string.error_server_code, 13) } returns "Server error (code 13)"
        val error = SocketRpcServerException("Server error code=13 msg=''", 13)
        val result = NetworkErrorMessages.userMessage(context, error, "fallback")
        assertEquals("Server error (code 13)", result)
    }

    @Test
    fun `network error still maps when raw message not allowed`() {
        every { context.getString(R.string.error_network_no_connection) } returns "no connection"
        val error = UnknownHostException("unable to resolve host")
        val result = NetworkErrorMessages.userMessage(context, error, "fallback", allowRawMessage = false)
        assertEquals("no connection", result)
    }

    @Test
    fun `null throwable returns fallback`() {
        val result = NetworkErrorMessages.userMessage(context, null, "fallback", allowRawMessage = false)
        assertEquals("fallback", result)
    }
}
