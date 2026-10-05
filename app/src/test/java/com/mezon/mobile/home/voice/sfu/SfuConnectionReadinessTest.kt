package com.mezon.mobile.home.voice.sfu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SfuConnectionReadinessTest {
    @Test fun firstJoinRequiresEverySignalRegardlessOfArrivalOrder() {
        for (order in permutations((0..4).toList())) {
            val gate = SfuConnectionReadiness()
            order.forEachIndexed { index, signal ->
                when (signal) {
                    0 -> gate.iceConnected = true
                    1 -> gate.transportConnected = true
                    2 -> gate.roomConfirmed = true
                    3 -> gate.peerId = "241"
                    4 -> gate.confirmVoiceJoined("241")
                }
                if (index == 4) assertTrue("$order", gate.isReady) else assertFalse("$order", gate.isReady)
            }
        }
    }

    @Test fun legacyVoiceJoinedStillRequiresTransportAndRoomConfirmation() {
        for (peer in listOf(null, "", "0")) {
            val gate = SfuConnectionReadiness()
            gate.confirmVoiceJoined(peer)
            gate.iceConnected = true
            gate.peerId = "241"
            gate.roomConfirmed = true
            assertFalse(gate.isReady)
            gate.transportConnected = true
            assertTrue(gate.isReady)
        }
    }

    @Test fun wrongPeerCannotUnlockMicrophone() {
        val gate = transportReady()
        gate.confirmVoiceJoined("240")
        assertFalse(gate.isReady)
        gate.confirmVoiceJoined("241")
        assertTrue(gate.isReady)
        gate.peerId = "242"
        assertFalse(gate.isReady)
    }

    @Test fun iceAndPresenceDoNotHideDtlsFailure() {
        val gate = transportReady()
        gate.confirmVoiceJoined("241")
        gate.transportConnected = false
        assertFalse(gate.isReady)
    }

    @Test fun absentVoiceJoinedCannotCompleteFirstJoin() {
        assertFalse(transportReady().isReady)
    }

    @Test fun reconnectCanSkipPresenceButStillNeedsAllTransportSignals() {
        for (order in permutations((0..3).toList())) {
            val gate = SfuConnectionReadiness(requiresVoiceJoined = false)
            order.forEachIndexed { index, signal ->
                when (signal) {
                    0 -> gate.iceConnected = true
                    1 -> gate.transportConnected = true
                    2 -> gate.roomConfirmed = true
                    3 -> gate.peerId = "242"
                }
                if (index == 3) assertTrue(gate.isReady) else assertFalse(gate.isReady)
            }
        }
    }

    @Test fun newAttemptDiscardsPreviousPresenceAndReadiness() {
        val old = transportReady().apply { confirmVoiceJoined("0") }
        assertTrue(old.isReady)
        assertFalse(SfuConnectionReadiness().isReady)
        assertFalse(transportReady().isReady)
    }

    @Test fun invalidSnapshotPeerDoesNotCountAsAdmission() {
        for (peer in listOf(null, "", "0", "null")) {
            val gate = transportReady().apply { confirmVoiceJoined("0"); peerId = peer }
            assertFalse(gate.isReady)
        }
    }

    private fun transportReady() = SfuConnectionReadiness().apply {
        iceConnected = true
        transportConnected = true
        roomConfirmed = true
        peerId = "241"
    }

    private fun permutations(values: List<Int>): List<List<Int>> =
        if (values.isEmpty()) listOf(emptyList())
        else values.flatMap { value -> permutations(values - value).map { listOf(value) + it } }
}
