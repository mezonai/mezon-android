package com.mezon.mobile.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointHealthTest {

    private val slow = EndpointHealth.SLOW_RTT_MS + 100
    private val fast = 40L
    private val overdue = EndpointHealth.PONG_OVERDUE_AFTER_MS + 1

    private fun node(id: Int, host: String, port: Int = 443) = RealtimeEndpoint(id, host, port)

    private fun warmedHealth(now: Long = 1_000_000L): Pair<EndpointHealth, Long> {
        val health = EndpointHealth()
        health.setEndpoint(node(1, "sock.mezon.ai"))
        health.recordConnected(now)
        return health to now + EndpointHealth.PROBE_WARMUP_MS
    }

    private fun EndpointHealth.probeSlowly(times: Int, now: Long) {
        repeat(times) { assertFalse(recordActiveProbe(slow, now)) }
    }

    @Test
    fun `a slow link is reported only after the warmup and a full streak`() {
        val now = 1_000_000L
        val (health, warmed) = warmedHealth(now)

        assertFalse(health.recordActiveProbe(slow, now))

        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED - 1, warmed)
        assertTrue(health.recordActiveProbe(slow, warmed))
    }

    @Test
    fun `weak reports are spaced out`() {
        val (health, warmed) = warmedHealth()
        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED - 1, warmed)
        assertTrue(health.recordActiveProbe(slow, warmed))

        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED * 2, warmed)

        val afterSpacing = warmed + EndpointHealth.WEAK_REPORT_SPACING_MS
        assertTrue(health.recordActiveProbe(slow, afterSpacing))
    }

    @Test
    fun `one fast sample breaks the streak`() {
        val (health, warmed) = warmedHealth()

        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED - 1, warmed)
        assertFalse(health.recordActiveProbe(fast, warmed))

        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED - 1, warmed)
        assertTrue(health.recordActiveProbe(slow, warmed))
    }

    @Test
    fun `a probe before the warmup never counts`() {
        val now = 1_000_000L
        val health = EndpointHealth()
        health.setEndpoint(node(1, "sock.mezon.ai"))
        health.recordConnected(now)

        val justBeforeWarmed = now + EndpointHealth.PROBE_WARMUP_MS - 1
        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED * 3, justBeforeWarmed)
    }

    @Test
    fun `a gateway confirmed slow node stops reporting until the next connect`() {
        val (health, warmed) = warmedHealth()
        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED - 1, warmed)
        assertTrue(health.recordActiveProbe(slow, warmed))

        health.disableSlowReports()
        val muchLater = warmed + EndpointHealth.WEAK_REPORT_SPACING_MS * 10
        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED * 3, muchLater)
        assertFalse(health.recordHeartbeat(overdue, muchLater))

        health.recordConnected(muchLater)
        val rewarmed = muchLater + EndpointHealth.PROBE_WARMUP_MS
        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED - 1, rewarmed)
        assertTrue(health.recordActiveProbe(slow, rewarmed))
    }

    @Test
    fun `a reconnect does not reset the spacing between weak reports`() {
        val (health, warmed) = warmedHealth()
        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED - 1, warmed)
        assertTrue(health.recordActiveProbe(slow, warmed))

        health.recordDisconnected()
        health.recordConnected(warmed)

        val rewarmed = warmed + EndpointHealth.PROBE_WARMUP_MS
        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED, rewarmed)

        val afterSpacing = warmed + EndpointHealth.WEAK_REPORT_SPACING_MS
        assertTrue(health.recordActiveProbe(slow, afterSpacing))
    }

    @Test
    fun `two socket timeouts within the window report a weak node`() {
        val (health, _) = warmedHealth()
        val now = 1_000_000L

        assertFalse(health.recordApiTimeout(now))
        assertTrue(health.recordApiTimeout(now + 1_000L))
    }

    @Test
    fun `socket timeouts further apart than the window do not add up`() {
        val (health, _) = warmedHealth()
        val now = 1_000_000L

        assertFalse(health.recordApiTimeout(now))
        assertFalse(health.recordApiTimeout(now + EndpointHealth.API_TIMEOUT_WINDOW_MS + 1))
    }

    @Test
    fun `an overdue pong reports a weak node`() {
        val (health, _) = warmedHealth()
        val now = 1_000_000L

        assertFalse(health.recordHeartbeat(EndpointHealth.PONG_OVERDUE_AFTER_MS, now))
        assertTrue(health.recordHeartbeat(overdue, now))
    }

    @Test
    fun `weak signals need a confirmed connection`() {
        val now = 1_000_000L
        val health = EndpointHealth()
        health.setEndpoint(node(1, "sock.mezon.ai"))

        assertFalse(health.recordApiTimeout(now))
        assertFalse(health.recordApiTimeout(now + 1))
        assertFalse(health.recordHeartbeat(overdue, now))
    }

    @Test
    fun `every weak signal shares one spacing`() {
        val (health, _) = warmedHealth()
        val now = 1_000_000L

        assertTrue(health.recordHeartbeat(overdue, now))
        assertFalse(health.recordApiTimeout(now + 1))
        assertFalse(health.recordApiTimeout(now + 2))

        val later = now + EndpointHealth.WEAK_REPORT_SPACING_MS
        assertFalse(health.recordApiTimeout(later))
        assertTrue(health.recordApiTimeout(later + 1))
    }

    @Test
    fun `the gateway naming the id of the node we are on keeps the connection`() {
        val health = EndpointHealth()
        health.setEndpoint(node(0, "sock.mezon.ai"))
        health.recordConnected(1_000_000L)

        health.setEndpoint(node(3, "sock.mezon.ai"))

        assertEquals(3, health.connectedEndpoint()?.id)
    }

    @Test
    fun `moving to another node starts its history from scratch`() {
        val now = 1_000_000L
        val health = EndpointHealth()
        health.setEndpoint(node(1, "sock.mezon.ai"))
        health.recordConnected(now)
        health.disableSlowReports()

        health.setEndpoint(node(2, "sock2.mezon.ai"))
        assertNull(health.connectedEndpoint())

        health.recordConnected(now)
        val warmed = now + EndpointHealth.PROBE_WARMUP_MS
        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED - 1, warmed)
        assertTrue(health.recordActiveProbe(slow, warmed))
    }

    @Test
    fun `a dropped connection retires the observation`() {
        val now = 1_000_000L
        val health = EndpointHealth()
        health.setEndpoint(node(1, "sock.mezon.ai"))
        health.recordConnected(now)
        health.recordDisconnected()

        assertNull(health.connectedEndpoint())
        assertFalse(health.recordActiveProbe(slow, now + EndpointHealth.PROBE_WARMUP_MS * 2))
    }

    @Test
    fun `a node is the same node by where it is not by which id it was given`() {
        assertTrue(node(1, "sock.mezon.ai").isSameNode(node(1, "sock.mezon.ai")))
        assertTrue(node(1, "sock.mezon.ai").isSameNode(node(7, "sock.mezon.ai")))
        assertTrue(node(0, "sock.mezon.ai").isSameNode(node(9, "sock.mezon.ai")))
    }

    @Test
    fun `a different host or port is a move`() {
        assertFalse(node(1, "sock.mezon.ai").isSameNode(node(1, "sock2.mezon.ai")))
        assertFalse(node(1, "sock.mezon.ai").isSameNode(node(1, "sock.mezon.ai", 7349)))
        assertFalse(node(1, "127.0.0.1", 4433).isSameNode(node(1, "127.0.0.1", 4999)))
    }

    @Test
    fun `a node the gateway did not name is labelled by address`() {
        assertEquals("2 (sock.mezon.ai:443)", node(2, "sock.mezon.ai").label())
        assertEquals("sock.mezon.ai:443", node(0, "sock.mezon.ai").label())
    }
}
