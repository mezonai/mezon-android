package com.mezon.mobile.network

import com.mezon.mobile.BuildConfig

data class RealtimeEndpoint(
    val id: Int,
    val host: String,
    val port: Int
) {
    fun isSameNode(other: RealtimeEndpoint): Boolean = host == other.host && port == other.port

    fun label(): String = if (id > 0) "$id ($host:$port)" else "$host:$port"
}

enum class HealthyEndpointReason(val code: Int) {
    UNREACHABLE(1),
    HIGH_LATENCY(2)
}

data class HealthyEndpoint(
    val apiUrl: String,
    val wsUrl: String,
    val tcpUrl: String
)

internal fun doubledBackoffMs(currentMs: Long, capMs: Long): Long {
    if (currentMs >= capMs) return capMs
    val doubled = currentMs * 2
    if (doubled < currentMs) return capMs
    return doubled.coerceAtMost(capMs)
}

internal fun resolveHost(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    var s = raw.trim()
    val scheme = s.indexOf("://")
    if (scheme >= 0) s = s.substring(scheme + 3)
    s = s.substringBefore('/').substringBefore('?')
    val host = s.substringBefore(':')
    return host.ifBlank { null }
}

internal fun resolvePort(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    var s = raw.trim()
    val scheme = s.indexOf("://")
    if (scheme >= 0) s = s.substring(scheme + 3)
    s = s.substringBefore('/').substringBefore('?')
    val colon = s.indexOf(':')
    if (colon < 0) return null
    return s.substring(colon + 1).toIntOrNull()
}

internal fun realtimeEndpointOf(tcpUrl: String?, wsUrl: String?): RealtimeEndpoint? {
    val host = resolveHost(tcpUrl)
        ?: if (BuildConfig.MEZON_ABRIDGED_FALLBACK) resolveHost(wsUrl) else null
    if (host.isNullOrBlank()) return null
    return RealtimeEndpoint(
        id = 0,
        host = host,
        port = resolvePort(tcpUrl) ?: BuildConfig.MEZON_TCP_PORT
    )
}

class EndpointHealth {
    companion object {
        const val SLOW_RTT_MS = 800L
        const val SLOW_STREAK_REQUIRED = 5
        const val SLOW_SWITCH_COOLDOWN_MS = 120_000L
    }

    private var endpoint: RealtimeEndpoint? = null
    private var connectedSinceMs: Long? = null
    private var slowStreak = 0
    private var slowReportSuppressedUntilMs: Long? = null
    private var slowReportsDisabled = false

    @Synchronized
    fun setEndpoint(next: RealtimeEndpoint?) {
        if (isOnSameNodeAs(next)) {
            endpoint = next
            return
        }
        endpoint = next
        forgetConnection()
        slowReportSuppressedUntilMs = null
        slowReportsDisabled = false
    }

    @Synchronized
    fun connectedEndpoint(): RealtimeEndpoint? {
        if (connectedSinceMs == null) return null
        return endpoint
    }

    @Synchronized
    fun recordConnected(nowMs: Long) {
        connectedSinceMs = nowMs
        slowStreak = 0
        slowReportSuppressedUntilMs = null
        slowReportsDisabled = false
    }

    @Synchronized
    fun recordDisconnected() {
        forgetConnection()
    }

    @Synchronized
    fun recordActiveProbe(rttMs: Long, nowMs: Long): Boolean {
        val connectedSince = connectedSinceMs ?: return false
        val suppressedUntil = slowReportSuppressedUntilMs
        if (slowReportsDisabled || (suppressedUntil != null && suppressedUntil > nowMs)) {
            slowStreak = 0
            return false
        }
        val settledOnThisNode = nowMs - connectedSince >= SLOW_SWITCH_COOLDOWN_MS
        if (settledOnThisNode && rttMs >= SLOW_RTT_MS) slowStreak++ else slowStreak = 0
        if (slowStreak < SLOW_STREAK_REQUIRED) return false
        slowStreak = 0
        slowReportSuppressedUntilMs = nowMs + SLOW_SWITCH_COOLDOWN_MS
        return true
    }

    @Synchronized
    fun disableSlowReports() {
        slowReportsDisabled = true
        slowStreak = 0
    }

    private fun isOnSameNodeAs(other: RealtimeEndpoint?): Boolean {
        val current = endpoint
        return when {
            current != null && other != null -> current.isSameNode(other)
            current == null && other == null -> true
            else -> false
        }
    }

    private fun forgetConnection() {
        connectedSinceMs = null
        slowStreak = 0
    }
}
