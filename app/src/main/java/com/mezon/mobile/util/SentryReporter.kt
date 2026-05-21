package com.mezon.mobile.util

import android.app.Application
import android.net.Uri
import com.mezon.mobile.BuildConfig
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.User
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SentryReporter @Inject constructor() {

    @Volatile
    private var initialized = false

    fun init(application: Application) {
        if (initialized) return
        val dsn = BuildConfig.MEZON_SENTRY_DSN.trim()
        if (dsn.isEmpty()) return
        SentryAndroid.init(application) { options ->
            options.dsn = dsn
            options.isEnabled = true
            options.logs.isEnabled = true
            options.environment = if (BuildConfig.DEBUG) "debug" else "production"
        }
        initialized = true
    }

    fun syncUser(userId: Long, userIdStr: String, displayName: String, username: String) {
        if (!initialized) return
        val id = userIdStr.trim().ifBlank {
            if (userId != 0L) userId.toString() else ""
        }
        if (id.isEmpty()) {
            clearUser()
            return
        }
        val label = displayName.trim().ifBlank { username.trim() }
        val user = User().apply {
            this.id = id
            this.username = label.ifBlank { null }
            val extras = linkedMapOf<String, String>()
            if (displayName.isNotBlank()) extras["display_name"] = displayName.trim()
            if (username.isNotBlank()) extras["mezon_username"] = username.trim()
            if (extras.isNotEmpty()) data = extras
        }
        Sentry.setUser(user)
    }

    fun clearUser() {
        if (!initialized) return
        Sentry.setUser(null)
    }

    fun captureException(throwable: Throwable, message: String? = null) {
        if (!initialized) return
        Sentry.captureException(throwable) { scope ->
            message?.let { scope.setExtra("message", it) }
        }
    }

    fun captureMessage(message: String, level: SentryLevel = SentryLevel.ERROR) {
        if (!initialized) return
        Sentry.captureMessage(message, level)
    }

    fun logError(message: String, throwable: Throwable? = null) {
        if (!initialized) return
        if (throwable != null) {
            Sentry.logger().error("%s: %s", message, throwable.message ?: throwable.javaClass.simpleName)
            captureException(throwable, message)
        } else {
            Sentry.logger().error(message)
            captureMessage(message, SentryLevel.ERROR)
        }
    }

    fun logWarning(message: String) {
        if (!initialized) return
        Sentry.logger().warn(message)
        addBreadcrumb("app", message, SentryLevel.WARNING)
    }

    fun logInfo(message: String) {
        if (!initialized) return
        Sentry.logger().info(message)
    }

    fun logRpcFailure(
        method: String,
        transport: String,
        throwable: Throwable? = null,
        detail: String? = null,
        httpCode: Int? = null
    ) {
        if (!initialized) return
        val summary = buildString {
            append("rpc fail method=").append(method)
            append(" transport=").append(transport)
            httpCode?.let { append(" http=").append(it) }
            detail?.let { append(" detail=").append(it.take(500)) }
            throwable?.message?.let { append(" err=").append(it.take(300)) }
        }
        Sentry.logger().error(summary)
        addBreadcrumb("rpc", summary, SentryLevel.ERROR)
        val t = throwable ?: RuntimeException(summary)
        Sentry.captureException(t) { scope ->
            scope.setTag("rpc.method", method)
            scope.setTag("rpc.transport", transport)
            httpCode?.let { scope.setTag("rpc.http_code", it.toString()) }
            detail?.let { scope.setExtra("rpc.detail", it.take(1500)) }
        }
    }

    fun logRpcWarning(method: String, transport: String, detail: String) {
        if (!initialized) return
        val summary = "rpc warn method=$method transport=$transport $detail"
        Sentry.logger().warn(summary)
        addBreadcrumb("rpc", summary, SentryLevel.WARNING)
    }

    fun logSocketFailure(action: String, throwable: Throwable?, detail: String? = null) {
        if (!initialized) return
        val summary = buildString {
            append("socket fail action=").append(action)
            detail?.let { append(" ").append(it.take(500)) }
            throwable?.message?.let { append(" err=").append(it.take(300)) }
        }
        Sentry.logger().error(summary)
        addBreadcrumb("socket", summary, SentryLevel.ERROR)
        val t = throwable ?: RuntimeException(summary)
        Sentry.captureException(t) { scope ->
            scope.setTag("socket.action", action)
            detail?.let { scope.setExtra("socket.detail", it.take(1500)) }
        }
    }

    fun logSocketWarning(action: String, detail: String) {
        if (!initialized) return
        val summary = "socket warn action=$action $detail"
        Sentry.logger().warn(summary)
        addBreadcrumb("socket", summary, SentryLevel.WARNING)
    }

    fun logImageLoadFailure(url: String, httpCode: Int?, throwable: Throwable?) {
        if (!initialized) return
        val target = sanitizeUrlForLog(url)
        val summary = buildString {
            append("image fail ").append(target)
            httpCode?.let { append(" http=").append(it) }
            throwable?.message?.let { append(" err=").append(it.take(300)) }
        }
        Sentry.logger().error(summary)
        addBreadcrumb("image", summary, SentryLevel.ERROR)
        val t = throwable ?: RuntimeException(summary)
        Sentry.captureException(t) { scope ->
            scope.setTag("image.target", target)
            httpCode?.let { scope.setTag("image.http_code", it.toString()) }
        }
    }

    fun logChatFailure(
        action: String,
        channelId: Long,
        clanId: Long,
        throwable: Throwable,
        detail: String? = null
    ) {
        if (!initialized) return
        val summary = buildString {
            append("chat fail action=").append(action)
            append(" channelId=").append(channelId)
            append(" clanId=").append(clanId)
            detail?.let { append(" ").append(it.take(300)) }
            append(" err=").append(throwable.message?.take(300) ?: throwable.javaClass.simpleName)
        }
        Sentry.logger().error(summary)
        addBreadcrumb("chat", summary, SentryLevel.ERROR)
        Sentry.captureException(throwable) { scope ->
            scope.setTag("chat.action", action)
            scope.setTag("chat.channel_id", channelId.toString())
            scope.setTag("chat.clan_id", clanId.toString())
            detail?.let { scope.setExtra("chat.detail", it.take(1500)) }
        }
    }

    private fun addBreadcrumb(category: String, message: String, level: SentryLevel) {
        Sentry.addBreadcrumb(
            Breadcrumb().apply {
                this.category = category
                this.message = message.take(1000)
                this.level = level
            }
        )
    }

    private fun sanitizeUrlForLog(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return "invalid_url"
        val path = uri.encodedPath.orEmpty().ifEmpty { "/" }
        val host = uri.host?.replace('.', '|') ?: "?"
        return "host=$host path=$path"
    }
}
