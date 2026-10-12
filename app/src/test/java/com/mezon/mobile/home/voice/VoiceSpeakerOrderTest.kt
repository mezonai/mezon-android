package com.mezon.mobile.home.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeakerOrderTest {
    private fun VoiceSpeakerOrder.orderUsers(users: List<String>, nowMs: Long) =
        order(users, nowMs, { it }, { it }, { it.startsWith("share") })

    @Test fun participantUpdatesCannotBypassTheSpeakerReorderInterval() {
        val order = VoiceSpeakerOrder()
        val users = listOf("a", "b", "c")
        var displayed = order.orderUsers(users, 0)
        for (now in 100L..1_400L step 100L) {
            order.updateSpeaking(setOf(if (now % 200L == 0L) "b" else "c"), now)
            displayed = order.orderUsers(users, now)
            assertEquals(users, displayed)
            assertEquals(1_500L - now, order.reorderDelayMs(now))
        }
        order.updateSpeaking(setOf("c"), 1_499)
        assertEquals(listOf("c", "b", "a"), order.orderUsers(users, 1_500))
    }

    @Test fun joinsLeavesAndSharesUpdateWhileSpeakerOrderIsThrottled() {
        val order = VoiceSpeakerOrder()
        order.orderUsers(listOf("a", "b", "leaving"), 0)
        order.updateSpeaking(setOf("b"), 100)
        val users = listOf("new", "b", "share-1", "a")
        val displayed = order.orderUsers(users, 100)
        assertEquals(listOf("share-1", "a", "b", "new"), displayed)
        assertEquals(listOf("share-1", "b", "a", "new"), order.orderUsers(users, 1_500))
    }

    @Test fun deviceKeysKeepMultipleTilesForTheSameSpeaker() {
        val order = VoiceSpeakerOrder()
        order.updateSpeaking(setOf("speaker"), 0)
        val users = listOf("other:1", "speaker:1", "speaker:2")
        assertEquals(listOf("speaker:1", "speaker:2", "other:1"), order.order(
            users, 0, { it }, { it.substringBefore(':') }, { false }
        ))
    }

    @Test fun resetAllowsImmediateReorderingInTheNextCall() {
        val order = VoiceSpeakerOrder()
        val users = listOf("a", "b")
        order.orderUsers(users, 0)
        order.clear()
        order.updateSpeaking(setOf("b"), 100)
        assertEquals(0L, order.reorderDelayMs(100))
        assertEquals(listOf("b", "a"), order.orderUsers(users, 100))
    }

    @Test fun queuedSpeakerOrderSurvivesAnotherSnapshotBeforeItsDiffIsDisplayed() {
        val order = VoiceSpeakerOrder()
        val users = listOf("a", "b")
        order.orderUsers(users, 0)
        order.updateSpeaking(setOf("b"), 1_500)
        assertEquals(listOf("b", "a"), order.orderUsers(users, 1_500))
        assertEquals(listOf("b", "a", "new"), order.orderUsers(users + "new", 1_510))
    }

    @Test fun sharesStayAheadOfCurrentAndRecentSpeakers() {
        val order = VoiceSpeakerOrder()
        order.updateSpeaking(setOf("recent"), 0)
        order.updateSpeaking(setOf("current"), 100)
        val users = listOf("silent", "recent", "current", "share")
        assertEquals(listOf("share", "current", "recent", "silent"), users.sortedBy {
            order.priority(it, it == "share", 101)
        })
    }

    @Test fun heldPriorityExpiresEvenWithoutAnotherAudioSample() {
        val order = VoiceSpeakerOrder()
        order.updateSpeaking(setOf("a"), 0)
        order.updateSpeaking(emptySet(), 100)
        assertEquals(5_100L, order.nextExpiryMs(101))
        assertTrue(order.priority("a", false, 5_099) < order.priority("silent", false, 5_099))
        assertEquals(order.priority("silent", false, 5_100), order.priority("a", false, 5_100))
        assertNull(order.nextExpiryMs(5_100))
    }

    @Test fun resumingSpeechCancelsTheOldHoldDeadline() {
        val order = VoiceSpeakerOrder()
        order.updateSpeaking(setOf("a"), 0)
        order.updateSpeaking(emptySet(), 100)
        order.updateSpeaking(setOf("a"), 200)
        assertNull(order.nextExpiryMs(201))
        assertTrue(order.priority("a", false, 10_000) < order.priority("silent", false, 10_000))
        order.updateSpeaking(emptySet(), 10_000)
        assertEquals(15_000L, order.nextExpiryMs(10_001))
    }

    @Test fun memberUpdatesDoNotExtendTheHoldAndDeparturesClearIt() {
        val order = VoiceSpeakerOrder()
        order.updateSpeaking(setOf("a"), 0)
        order.updateSpeaking(emptySet(), 100)
        order.updateSpeaking(emptySet(), 1_000)
        order.retainMembers(setOf("a"), 1_000)
        assertEquals(5_100L, order.nextExpiryMs(1_000))
        order.retainMembers(emptySet(), 1_001)
        assertNull(order.nextExpiryMs(1_001))
        assertEquals(order.priority("silent", false, 1_002), order.priority("a", false, 1_002))
    }

    @Test fun resetDoesNotCarrySpeakingPriorityIntoTheNextCall() {
        val order = VoiceSpeakerOrder()
        order.updateSpeaking(setOf("a", "b"), 0)
        order.updateSpeaking(setOf("b"), 100)
        order.clear()
        assertNull(order.nextExpiryMs(101))
        assertEquals(order.priority("silent", false, 101), order.priority("a", false, 101))
        assertEquals(order.priority("silent", false, 101), order.priority("b", false, 101))
    }
}
