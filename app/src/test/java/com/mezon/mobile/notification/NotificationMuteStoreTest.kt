package com.mezon.mobile.notification

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationMuteStoreTest {
    private val context = mockk<Context>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private val values = mutableMapOf<String, Long>()
    private var userId = "account-a"
    private var now = 1_800_000_000_000L

    private fun store() = NotificationMuteStore(context, { userId }, { now })

    @Before
    fun setUp() {
        every { context.getSharedPreferences(any(), any()) } returns preferences
        every { preferences.getLong(any(), any()) } answers { values[firstArg()] ?: secondArg() }
        every { preferences.edit() } returns editor
        every { editor.putLong(any(), any()) } answers {
            values[firstArg()] = secondArg()
            editor
        }
        every { editor.remove(any()) } answers {
            values.remove(firstArg<String>())
            editor
        }
        every { editor.commit() } returns true
        every { editor.apply() } returns Unit
    }

    @Test
    fun timedMuteSurvivesStoreRecreationAndExpiresAtDeadline() {
        store().setDuration(userId, 123L, 15 * 60)
        now += 899_999L
        assertTrue(store().isMuted(123L))
        assertFalse(store().isMuted(456L))

        now += 1L
        assertFalse(store().isMuted(123L))
    }

    @Test
    fun muteIsScopedToAccountAndCanBeCleared() {
        store().setDuration(userId, 123L, 900)
        userId = "account-b"
        assertFalse(store().isMuted(123L))
        userId = "account-a"
        assertTrue(store().isMuted(123L))
        store().clear(123L)
        assertFalse(store().isMuted(123L))
    }

    @Test
    fun serverUpdatesAndUnmuteReplaceSavedMute() {
        store().updateFromServer(123L, -1)
        now += 3_600_000L
        assertTrue(store().isMuted(123L))
        store().setDuration(userId, 123L, 0)
        assertFalse(store().isMuted(123L))
        store().updateFromServer(123L, (now / 1000 + 10).toInt())
        assertTrue(store().isMuted(123L))
        now += 10_000L
        assertFalse(store().isMuted(123L))
    }
}
