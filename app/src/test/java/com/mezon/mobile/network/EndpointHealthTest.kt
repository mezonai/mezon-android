package com.mezon.mobile.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointHealthTest {

    private val slow = EndpointHealth.SLOW_RTT_MS + 100
    private val fast = 40L

    private fun node(id: Int, host: String, port: Int = 443) = RealtimeEndpoint(id, host, port)

    private fun settledHealth(now: Long = 1_000_000L): Pair<EndpointHealth, Long> {
        val health = EndpointHealth()
        health.setEndpoint(node(1, "sock.mezon.ai"))
        health.recordConnected(now)
        return health to now + EndpointHealth.SLOW_SWITCH_COOLDOWN_MS
    }

    private fun EndpointHealth.probeSlowly(times: Int, now: Long) {
        repeat(times) { assertFalse(recordActiveProbe(slow, now)) }
    }

    @Test
    fun `a slow link is reported only after settling and a full streak`() {
        val now = 1_000_000L
        val (health, settled) = settledHealth(now)

        assertFalse(health.recordActiveProbe(slow, now))

        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED - 1, settled)
        assertTrue(health.recordActiveProbe(slow, settled))
    }

    @Test
    fun `reporting is suppressed for a cooldown after a report`() {
        val (health, settled) = settledHealth()
        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED - 1, settled)
        assertTrue(health.recordActiveProbe(slow, settled))

        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED * 2, settled)

        val afterCooldown = settled + EndpointHealth.SLOW_SWITCH_COOLDOWN_MS + 1
        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED - 1, afterCooldown)
        assertTrue(health.recordActiveProbe(slow, afterCooldown))
    }

    @Test
    fun `one fast sample breaks the streak`() {
        val (health, settled) = settledHealth()

        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED - 1, settled)
        assertFalse(health.recordActiveProbe(fast, settled))

        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED - 1, settled)
        assertTrue(health.recordActiveProbe(slow, settled))
    }

    @Test
    fun `a probe before the node has settled never counts`() {
        val now = 1_000_000L
        val health = EndpointHealth()
        health.setEndpoint(node(1, "sock.mezon.ai"))
        health.recordConnected(now)

        val justBeforeSettled = now + EndpointHealth.SLOW_SWITCH_COOLDOWN_MS - 1
        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED * 3, justBeforeSettled)
    }

    @Test
    fun `a gateway confirmed slow node stops reporting until the next connect`() {
        val (health, settled) = settledHealth()
        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED - 1, settled)
        assertTrue(health.recordActiveProbe(slow, settled))

        health.disableSlowReports()
        val muchLater = settled + EndpointHealth.SLOW_SWITCH_COOLDOWN_MS * 10
        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED * 3, muchLater)

        health.recordConnected(muchLater)
        val resettled = muchLater + EndpointHealth.SLOW_SWITCH_COOLDOWN_MS
        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED - 1, resettled)
        assertTrue(health.recordActiveProbe(slow, resettled))
    }

    @Test
    fun `a fresh connection does not inherit the previous suppression`() {
        val (health, settled) = settledHealth()
        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED - 1, settled)
        assertTrue(health.recordActiveProbe(slow, settled))

        health.recordDisconnected()
        health.recordConnected(settled)

        val resettled = settled + EndpointHealth.SLOW_SWITCH_COOLDOWN_MS
        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED - 1, resettled)
        assertTrue(health.recordActiveProbe(slow, resettled))
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
        val settled = now + EndpointHealth.SLOW_SWITCH_COOLDOWN_MS
        health.probeSlowly(EndpointHealth.SLOW_STREAK_REQUIRED - 1, settled)
        assertTrue(health.recordActiveProbe(slow, settled))
    }

    @Test
    fun `a dropped connection retires the observation`() {
        val now = 1_000_000L
        val health = EndpointHealth()
        health.setEndpoint(node(1, "sock.mezon.ai"))
        health.recordConnected(now)
        health.recordDisconnected()

        assertNull(health.connectedEndpoint())
        assertFalse(health.recordActiveProbe(slow, now + EndpointHealth.SLOW_SWITCH_COOLDOWN_MS * 2))
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
