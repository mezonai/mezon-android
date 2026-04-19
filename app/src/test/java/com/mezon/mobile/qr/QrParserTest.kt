package com.mezon.mobile.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrParserTest {
    @Test
    fun parseProfileQrValue() {
        val payload = ProfilePayload(id = 123L, avatar = "https://cdn.mezon.ai/avatar.png", name = "Mezon")
        val value = buildProfileQrValue("https://mezon.ai", "tester", payload)
        val action = parseQrValue(value)
        assertTrue(action is QrAction.Profile)
        val decoded = decodeProfilePayload((action as QrAction.Profile).data)
        assertEquals(payload.id, decoded?.id)
        assertEquals(payload.avatar, decoded?.avatar)
        assertEquals(payload.name, decoded?.name)
    }

    @Test
    fun parseTransferPayload() {
        val value = buildTransferPayload("tester", 456L)
        val action = parseQrValue(value)
        assertTrue(action is QrAction.Transfer)
        assertEquals(value, (action as QrAction.Transfer).rawJson)
    }

    @Test
    fun parseInviteLink() {
        val value = "https://mezon.ai/invite/abc123"
        val action = parseQrValue(value)
        assertTrue(action is QrAction.Invite)
        assertEquals("abc123", (action as QrAction.Invite).inviteId)
    }
}

