package com.mezon.mobile.util

import android.content.Context
import com.mezon.mobile.R
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException


object NetworkErrorMessages {
    fun userMessage(context: Context, throwable: Throwable?, fallback: String): String {
        if (throwable == null) return fallback
        var t: Throwable? = throwable
        val seen = HashSet<Throwable>(8)
        var depth = 0
        while (t != null && depth < 8 && seen.add(t)) {
            messageForThrowable(context, t)?.let { return it }
            t = t.cause
            depth++
        }
        val raw = throwable.message?.trim().orEmpty()
        return raw.ifEmpty { fallback }
    }

    private fun messageForThrowable(context: Context, t: Throwable): String? {
        when (t) {
            is UnknownHostException -> return context.getString(R.string.error_network_no_connection)
            is SocketTimeoutException -> return context.getString(R.string.error_network_timeout)
            is ConnectException -> return context.getString(R.string.error_network_no_connection)
            is SSLException -> return context.getString(R.string.error_network_no_connection)
        }
        if (t !is IOException) return null
        val msg = t.message?.lowercase(Locale.US).orEmpty()
        if (msg.contains("unable to resolve host") ||
            msg.contains("no address associated") ||
            msg.contains("network is unreachable") ||
            msg.contains("failed to connect") ||
            msg.contains("connection refused") ||
            msg.contains("connection reset") ||
            msg.contains("name or service not known") ||
            msg.contains("no route to host")
        ) {
            return context.getString(R.string.error_network_no_connection)
        }
        if (msg.contains("timed out") || msg.contains("timeout")) {
            return context.getString(R.string.error_network_timeout)
        }
        return null
    }
}
