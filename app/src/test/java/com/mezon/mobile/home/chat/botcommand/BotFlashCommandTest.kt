package com.mezon.mobile.home.chat.botcommand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotFlashCommandTest {

    private val command = BotFlashCommand(botId = 7L, menuName = "deploy", actionMsg = "  *deploy ")

    @Test
    fun `text that still starts with the action is a bot command`() {
        assertTrue(command.stillPrefixes("*deploy prod"))
        assertTrue(command.stillPrefixes("   *deploy"))
    }

    @Test
    fun `edited text that drops the action is a normal message`() {
        assertFalse(command.stillPrefixes("deploy prod"))
        assertFalse(command.stillPrefixes(""))
        assertFalse(BotFlashCommand(7L, "empty", "   ").stillPrefixes("anything"))
    }

    @Test
    fun `arguments are the trimmed text after the action`() {
        assertEquals("prod now", command.arguments("*deploy   prod now  "))
        assertEquals("", command.arguments("*deploy"))
        assertEquals("other text", command.arguments("  other text "))
    }
}
