package com.mezon.mobile.util

import android.content.Context
import com.mezon.mobile.R
import com.mezon.mobile.network.HttpRpcStatusException
import com.mezon.mobile.network.SocketConnectionLostException
import com.mezon.mobile.network.SocketRequestTimeoutException
import com.mezon.mobile.network.SocketRpcServerException
import com.mezon.mobile.network.SocketRpcTransportException
import com.mezon.mobile.network.UnauthorizedException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

class UserFacingException(message: String) : RuntimeException(message)

object NetworkErrorMessages {
    private const val GRPC_INVALID_ARGUMENT = 3
    private const val GRPC_DEADLINE_EXCEEDED = 4
    private const val GRPC_NOT_FOUND = 5
    private const val GRPC_ALREADY_EXISTS = 6
    private const val GRPC_PERMISSION_DENIED = 7
    private const val GRPC_RESOURCE_EXHAUSTED = 8
    private const val GRPC_UNAVAILABLE = 14
    private const val GRPC_UNAUTHENTICATED = 16

    fun userMessage(
        context: Context,
        throwable: Throwable?,
        fallback: String = context.getString(R.string.error_request_failed)
    ): String = userMessageOrNull(context, throwable) ?: fallback

    fun userMessageOrNull(context: Context, throwable: Throwable?): String? {
        var t: Throwable? = throwable
        val seen = HashSet<Throwable>(8)
        var depth = 0
        while (t != null && depth < 8 && seen.add(t)) {
            messageForThrowable(context, t)?.let { return it }
            t = t.cause
            depth++
        }
        return null
    }

    private fun messageForThrowable(context: Context, t: Throwable): String? {
        when (t) {
            is UserFacingException -> return t.message?.trim()?.ifEmpty { null }
            is UnknownHostException -> return context.getString(R.string.error_network_no_connection)
            is SocketTimeoutException -> return context.getString(R.string.error_network_timeout)
            is ConnectException -> return context.getString(R.string.error_network_no_connection)
            is SSLException -> return context.getString(R.string.error_network_no_connection)
            is HttpRpcStatusException -> return httpStatusMessage(context, t.code)
            is UnauthorizedException -> return context.getString(R.string.common_session_expired_content)
            is SocketRpcServerException ->
                return grpcStatusMessage(context, t.code) ?: httpStatusMessage(context, t.code)
            is SocketRequestTimeoutException -> return context.getString(R.string.error_network_timeout)
            is SocketConnectionLostException, is SocketRpcTransportException ->
                return context.getString(R.string.error_network_no_connection)
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

    private fun httpStatusMessage(context: Context, code: Int): String? = when (code) {
        400 -> context.getString(R.string.error_server_invalid_argument)
        403 -> context.getString(R.string.error_server_permission_denied)
        404 -> context.getString(R.string.error_server_not_found)
        408 -> context.getString(R.string.error_network_timeout)
        409 -> context.getString(R.string.error_server_already_exists)
        429 -> context.getString(R.string.error_server_rate_limited)
        else -> null
    }

    private fun grpcStatusMessage(context: Context, code: Int): String? = when (code) {
        GRPC_INVALID_ARGUMENT -> context.getString(R.string.error_server_invalid_argument)
        GRPC_NOT_FOUND -> context.getString(R.string.error_server_not_found)
        GRPC_ALREADY_EXISTS -> context.getString(R.string.error_server_already_exists)
        GRPC_PERMISSION_DENIED -> context.getString(R.string.error_server_permission_denied)
        GRPC_RESOURCE_EXHAUSTED -> context.getString(R.string.error_server_rate_limited)
        GRPC_UNAUTHENTICATED -> context.getString(R.string.common_session_expired_content)
        GRPC_DEADLINE_EXCEEDED -> context.getString(R.string.error_network_timeout)
        GRPC_UNAVAILABLE -> context.getString(R.string.error_network_no_connection)
        else -> null
    }
}
