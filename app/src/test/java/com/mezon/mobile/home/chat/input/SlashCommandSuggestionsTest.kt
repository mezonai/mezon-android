package com.mezon.mobile.home.chat.input

import org.junit.Assert.assertEquals
import org.junit.Test

class SlashCommandSuggestionsTest {

    private val commands = listOf(
        SlashCommand(1L, "standup", "Daily standup starts now"),
        SlashCommand(2L, "lunch", "Lunch time!"),
        SlashCommand(3L, "", "unnamed"),
    )

    @Test
    fun `a leading slash opens the slash trigger with the typed keyword`() {
        val trigger = InputSuggestionsController.detect("/sta", 4)
        assertEquals(InputSuggestionsController.Mode.SLASH, trigger.mode)
        assertEquals(0, trigger.triggerPos)
        assertEquals(4, trigger.queryLen)
        assertEquals("sta", trigger.keyword)
    }

    @Test
    fun `a bare slash offers every command`() {
        val trigger = InputSuggestionsController.detect("/", 1)
        assertEquals(InputSuggestionsController.Mode.SLASH, trigger.mode)
        assertEquals("", trigger.keyword)
    }

    @Test
    fun `leading whitespace before the slash is ignored`() {
        val trigger = InputSuggestionsController.detect("  /st", 5)
        assertEquals(InputSuggestionsController.Mode.SLASH, trigger.mode)
        assertEquals(2, trigger.triggerPos)
        assertEquals("st", trigger.keyword)
    }

    @Test
    fun `a slash that is not the first character does not trigger`() {
        assertEquals(InputSuggestionsController.Mode.NONE, InputSuggestionsController.detect("hi /st", 6).mode)
    }

    @Test
    fun `whitespace inside the keyword closes the slash trigger`() {
        assertEquals(InputSuggestionsController.Mode.NONE, InputSuggestionsController.detect("/st and", 7).mode)
    }

    @Test
    fun `a mention typed after the slash wins`() {
        val trigger = InputSuggestionsController.detect("/st @jo", 7)
        assertEquals(InputSuggestionsController.Mode.MENTION, trigger.mode)
        assertEquals("jo", trigger.keyword)
    }

    @Test
    fun `slash items filter by name case-insensitively and skip unnamed commands`() {
        val all = InputSuggestionsController.buildSlashCommandItems("", commands)
        assertEquals(listOf(commands[0], commands[1]), all.map { (it as InputSuggestionItem.SlashCommand).command })

        val filtered = InputSuggestionsController.buildSlashCommandItems("LUN", commands)
        assertEquals(listOf(commands[1]), filtered.map { (it as InputSuggestionItem.SlashCommand).command })
    }

    @Test
    fun `ephemeral leads the slash list only when allowed and matching`() {
        val all = InputSuggestionsController.buildSlashCommandItems("", commands, includeEphemeral = true)
        assertEquals(InputSuggestionItem.EphemeralCommand, all.first())
        assertEquals(3, all.size)

        val matching = InputSuggestionsController.buildSlashCommandItems("EPH", commands, includeEphemeral = true)
        assertEquals(listOf<InputSuggestionItem>(InputSuggestionItem.EphemeralCommand), matching)

        val other = InputSuggestionsController.buildSlashCommandItems("lun", commands, includeEphemeral = true)
        assertEquals(listOf(commands[1]), other.map { (it as InputSuggestionItem.SlashCommand).command })

        val disallowed = InputSuggestionsController.buildSlashCommandItems("", commands)
        assertEquals(false, disallowed.contains(InputSuggestionItem.EphemeralCommand))
    }

    @Test
    fun `bot flash commands carry the bot name`() {
        val botCommand = SlashCommand(4L, "deploy", "*deploy", botId = 99L)
        val items = InputSuggestionsController.buildSlashCommandItems(
            "dep",
            listOf(botCommand),
            botNameFor = { if (it == 99L) "Komu" else "" }
        )
        assertEquals("Komu", (items.single() as InputSuggestionItem.SlashCommand).botName)
    }
}
