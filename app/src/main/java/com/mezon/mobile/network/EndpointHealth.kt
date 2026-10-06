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

private val nodeIdsByHost = mapOf(
    "sock.mezon.ai" to 1,
    "sock2.mezon.ai" to 2,
    "sock3.mezon.ai" to 3
)

internal fun nodeIdOfHost(host: String): Int = nodeIdsByHost[host.lowercase()] ?: 0

internal fun realtimeEndpointOf(tcpUrl: String?, wsUrl: String?): RealtimeEndpoint? {
    val host = resolveHost(tcpUrl)
        ?: if (BuildConfig.MEZON_ABRIDGED_FALLBACK) resolveHost(wsUrl) else null
    if (host.isNullOrBlank()) return null
    return RealtimeEndpoint(
        id = nodeIdOfHost(host),
        host = host,
        port = resolvePort(tcpUrl) ?: BuildConfig.MEZON_TCP_PORT
    )
}

class EndpointHealth {
    companion object {
        const val SLOW_RTT_MS = 500L
        const val SLOW_STREAK_REQUIRED = 3
        const val PROBE_WARMUP_MS = 15_000L
        const val API_TIMEOUTS_REQUIRED = 2
        const val API_TIMEOUT_WINDOW_MS = 30_000L
        const val PONG_OVERDUE_AFTER_MS = 12_000L
        const val WEAK_REPORT_SPACING_MS = 60_000L
    }

    private var endpoint: RealtimeEndpoint? = null
    private var connectedSinceMs: Long? = null
    private var slowStreak = 0
    private val apiTimeoutsAtMs = mutableListOf<Long>()
    private var lastWeakReportAtMs: Long? = null
    private var slowReportsDisabled = false

    @Synchronized
    fun setEndpoint(next: RealtimeEndpoint?) {
        if (isOnSameNodeAs(next)) {
            endpoint = next
            return
        }
        endpoint = next
        forgetConnection()
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
        apiTimeoutsAtMs.clear()
        slowReportsDisabled = false
    }

    @Synchronized
    fun recordDisconnected() {
        forgetConnection()
    }

    @Synchronized
    fun recordActiveProbe(rttMs: Long, nowMs: Long): Boolean {
        val connectedSince = connectedSinceMs ?: return false
        if (nowMs - connectedSince < PROBE_WARMUP_MS) return false
        slowStreak = if (rttMs >= SLOW_RTT_MS) slowStreak + 1 else 0
        if (slowStreak < SLOW_STREAK_REQUIRED) return false
        return claimWeakReport(nowMs)
    }

    @Synchronized
    fun recordApiTimeout(nowMs: Long): Boolean {
        if (connectedSinceMs == null) return false
        apiTimeoutsAtMs.removeAll { nowMs - it > API_TIMEOUT_WINDOW_MS }
        apiTimeoutsAtMs.add(nowMs)
        if (apiTimeoutsAtMs.size < API_TIMEOUTS_REQUIRED) return false
        return claimWeakReport(nowMs)
    }

    @Synchronized
    fun recordHeartbeat(sinceLastPongMs: Long, nowMs: Long): Boolean {
        if (connectedSinceMs == null || sinceLastPongMs <= PONG_OVERDUE_AFTER_MS) return false
        return claimWeakReport(nowMs)
    }

    @Synchronized
    fun disableSlowReports() {
        slowReportsDisabled = true
        slowStreak = 0
        apiTimeoutsAtMs.clear()
    }

    private fun claimWeakReport(nowMs: Long): Boolean {
        if (slowReportsDisabled) return false
        val lastReportAt = lastWeakReportAtMs
        if (lastReportAt != null && nowMs - lastReportAt < WEAK_REPORT_SPACING_MS) return false
        lastWeakReportAtMs = nowMs
        slowStreak = 0
        apiTimeoutsAtMs.clear()
        return true
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
        apiTimeoutsAtMs.clear()
    }
}
