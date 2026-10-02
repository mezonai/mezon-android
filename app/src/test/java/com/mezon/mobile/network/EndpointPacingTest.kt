package com.mezon.mobile.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointPacingTest {

    private fun ladder(startMs: Long, capMs: Long, steps: Int): List<Long> {
        var current = startMs
        return List(steps) {
            current = doubledBackoffMs(current, capMs)
            current
        }
    }

    @Test
    fun `the healthy endpoint window doubles from five seconds and stops at a minute`() {
        assertEquals(
            listOf(10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L),
            ladder(startMs = 5_000L, capMs = 60_000L, steps = 6)
        )
    }

    @Test
    fun `the refresh throttle doubles from a minute and stops at five`() {
        assertEquals(
            listOf(120_000L, 240_000L, 300_000L, 300_000L),
            ladder(startMs = 60_000L, capMs = 300_000L, steps = 4)
        )
    }

    @Test
    fun `a window already at the cap stays there`() {
        assertEquals(60_000L, doubledBackoffMs(60_000L, 60_000L))
        assertEquals(60_000L, doubledBackoffMs(90_000L, 60_000L))
    }

    @Test
    fun `doubling cannot overflow into a negative window`() {
        assertEquals(Long.MAX_VALUE, doubledBackoffMs(Long.MAX_VALUE - 1, Long.MAX_VALUE))
        assertEquals(60_000L, doubledBackoffMs(Long.MAX_VALUE, 60_000L))
    }

    @Test
    fun `a host is read out of every url shape the gateway sends`() {
        assertEquals("sock.mezon.ai", resolveHost("wss://sock.mezon.ai:443"))
        assertEquals("sock.mezon.ai", resolveHost("wss://sock.mezon.ai"))
        assertEquals("sock.mezon.ai", resolveHost("sock.mezon.ai:7350"))
        assertEquals("sock.mezon.ai", resolveHost("sock.mezon.ai"))
        assertEquals("api.mezon.ai", resolveHost("https://api.mezon.ai/"))
        assertEquals("sock.mezon.ai", resolveHost("wss://sock.mezon.ai:443/ws?token=abc"))
        assertEquals("sock.mezon.ai", resolveHost("  wss://sock.mezon.ai:443  "))
    }

    @Test
    fun `a missing host reads as nothing rather than an empty node`() {
        assertNull(resolveHost(null))
        assertNull(resolveHost(""))
        assertNull(resolveHost("   "))
        assertNull(resolveHost("wss://"))
    }

    @Test
    fun `a port is read only when the url carries one`() {
        assertEquals(443, resolvePort("wss://sock.mezon.ai:443"))
        assertEquals(7350, resolvePort("sock.mezon.ai:7350"))
        assertEquals(443, resolvePort("wss://sock.mezon.ai:443/ws?token=abc"))
        assertNull(resolvePort("wss://sock.mezon.ai"))
        assertNull(resolvePort("https://api.mezon.ai/"))
        assertNull(resolvePort(null))
        assertNull(resolvePort(""))
    }

    @Test
    fun `the tcp url names the node when it carries a host`() {
        val endpoint = realtimeEndpointOf("sock.mezon.ai:7350", "wss://other.mezon.ai:443")
        assertEquals("sock.mezon.ai", endpoint?.host)
        assertEquals(7350, endpoint?.port)
        assertEquals(0, endpoint?.id)
    }

    @Test
    fun `two spellings of the same node are the same node`() {
        val fromTcp = realtimeEndpointOf("sock.mezon.ai:443", null)
        val fromUrl = realtimeEndpointOf("wss://sock.mezon.ai:443/ws?token=abc", null)
        assertEquals(true, fromTcp?.isSameNode(fromUrl!!))
    }
}
