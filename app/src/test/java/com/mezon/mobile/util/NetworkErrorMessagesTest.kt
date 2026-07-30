package com.mezon.mobile.util

import android.content.Context
import com.mezon.mobile.R
import com.mezon.mobile.network.HttpRpcStatusException
import io.mockk.every
import io.mockk.mockk
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkErrorMessagesTest {

    private val context = mockk<Context>()

    @Test
    fun `unmapped error returns fallback when raw message not allowed`() {
        val error = HttpRpcStatusException("RPC UpdateUsername failed (500): boom", 500)
        val result = NetworkErrorMessages.userMessage(context, error, "fallback", allowRawMessage = false)
        assertEquals("fallback", result)
    }

    @Test
    fun `unmapped error returns raw message by default`() {
        val error = HttpRpcStatusException("RPC UpdateUsername failed (500): boom", 500)
        val result = NetworkErrorMessages.userMessage(context, error, "fallback")
        assertEquals("RPC UpdateUsername failed (500): boom", result)
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
