package com.mezon.mobile.home.voice.sfu

import android.util.Log
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.AudioTrack
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver

class MezonSfuSessionAudioTest {
    private val fixtures = mutableListOf<Fixture>()

    @Before fun mockAndroidLogging() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
    }

    @After fun closeFixtures() {
        fixtures.forEach { it.close() }
        unmockkStatic(Log::class)
    }

    @Test fun attachesAlreadyMutedTrackOnJoin() {
        val f = fixture(enabled = false)
        assertTrue(f.synchronize(enabled = false))
        assertFalse(f.enabled)
        verify(exactly = 1) { f.sender.setTrack(f.track, false) }
    }

    @Test fun acceptsRepeatedUnmutedTrackSynchronization() {
        val f = fixture(enabled = true)
        repeat(3) { assertTrue(f.synchronize(enabled = true)) }
        assertTrue(f.enabled)
        verify(exactly = 3) { f.sender.setTrack(f.track, false) }
    }

    @Test fun preservesMuteAcrossRepeatedSnapshotAndOfferSynchronization() {
        val f = fixture(enabled = false)
        for (enabled in listOf(false, false, true, true, false, false)) {
            assertTrue(f.synchronize(enabled))
            if (enabled) assertTrue(f.enabled) else assertFalse(f.enabled)
        }
    }

    @Test fun stillReportsActualSenderBindingFailure() {
        val f = fixture(enabled = false)
        every { f.sender.setTrack(f.track, false) } returns false
        assertFalse(f.synchronize(enabled = false))
        verify(exactly = 1) { f.sender.setTrack(f.track, false) }
    }

    @Test fun cannotUnmuteOrStartPushToTalkBeforeConnected() {
        val f = fixture(enabled = false)
        for (state in listOf(SfuConnectionState.CONNECTING, SfuConnectionState.ICE_CONNECTED,
            SfuConnectionState.DTLS_HANDSHAKE, SfuConnectionState.AWAITING_CONFIRMATION,
            SfuConnectionState.DISCONNECTED, SfuConnectionState.FAILED)) {
            f.assertMicrophoneLocked(state)
        }
    }

    @Test fun voiceJoinedOnlyConfirmsCurrentUserAndRoomOnAnOpenSession() {
        fixture(enabled = false).assertPresenceFiltering()
    }

    private fun fixture(enabled: Boolean) = Fixture(enabled).also { fixtures.add(it) }

    private class Fixture(var enabled: Boolean) {
        val track = mockk<AudioTrack>()
        val sender = mockk<RtpSender>()
        private val transceiver = mockk<RtpTransceiver>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val session = MezonSfuSession(
            mockk(), mockk(), mockk(), mockk(), scope, Dispatchers.Unconfined
        )

        init {
            every { track.isDisposed } returns false
            every { track.state() } returns MediaStreamTrack.State.LIVE
            every { track.enabled() } answers { enabled }
            every { track.setEnabled(any()) } answers {
                // Native MediaStreamTrack returns "changed", not "success".
                val next = firstArg<Boolean>()
                val changed = next != enabled
                enabled = next
                changed
            }
            every { transceiver.mid } returns "0"
            every { transceiver.sender } returns sender
            every { sender.setTrack(track, false) } returns true
            field("active").set(session, true)
            field("localAudioTrack").set(session, track)
            field("transceiverCache").set(session, listOf(transceiver))
        }

        fun synchronize(enabled: Boolean): Boolean {
            field("micEnabled").set(session, enabled)
            return MezonSfuSession::class.java.getDeclaredMethod("synchronizeLocalAudioTrack")
                .apply { isAccessible = true }.invoke(session) as Boolean
        }

        fun assertPresenceFiltering() {
            val identity = field("callIdentity").type.declaredConstructors.single()
                .apply { isAccessible = true }.newInstance(22L, 11L, "33")
            field("callIdentity").set(session, identity)
            field("socketOpen").setBoolean(session, true)
            val gate = SfuConnectionReadiness().apply {
                iceConnected = true; transportConnected = true; roomConfirmed = true; peerId = "241"
            }
            field("readiness").set(session, gate)
            session.handleVoiceJoined(12L, 22L, "33", "0")
            session.handleVoiceJoined(11L, 23L, "33", "0")
            session.handleVoiceJoined(11L, 22L, "34", "0")
            assertFalse(gate.isReady)
            field("socketOpen").setBoolean(session, false)
            session.handleVoiceJoined(11L, 22L, "33", "0")
            assertFalse(gate.isReady)
            field("socketOpen").setBoolean(session, true)
            session.handleVoiceJoined(11L, 22L, "33", "0")
            assertTrue(gate.isReady)
        }

        fun assertMicrophoneLocked(state: SfuConnectionState) {
            field("connectionState").set(session, state)
            session.setMicEnabled(true)
            assertFalse(field("micEnabled").getBoolean(session))
            assertFalse(enabled)
            field("role").set(session, SfuRole.AUDIENCE)
            session.pttPress()
            assertFalse(field("pttRequested").getBoolean(session))
            field("role").set(session, SfuRole.SPEAKER)
        }

        fun close() {
            scope.cancel()
            (field("webRtcDispatcher").get(session) as ExecutorCoroutineDispatcher).close()
        }

        private fun field(name: String) = MezonSfuSession::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
    }
}
