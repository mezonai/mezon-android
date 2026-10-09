package com.mezon.mobile.home.chat.channelinfo

import com.mezon.mezon.api.QuickMenuAccess
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickMenuNameRulesTest {

    @Test
    fun `letters numbers spaces and allowed punctuation are valid`() {
        assertTrue(QuickMenuNameRules.isValidName("daily standup"))
        assertTrue(QuickMenuNameRules.isValidName("ăn trưa 12.30+"))
        assertTrue(QuickMenuNameRules.isValidName("deploy_prod-v2"))
        assertTrue(QuickMenuNameRules.isValidName("🚀 launch"))
    }

    @Test
    fun `empty, too long, leading underscore or dash and other symbols are invalid`() {
        assertFalse(QuickMenuNameRules.isValidName(""))
        assertFalse(QuickMenuNameRules.isValidName("a".repeat(QuickMenuNameRules.MAX_NAME_CODE_POINTS + 1)))
        assertFalse(QuickMenuNameRules.isValidName("_hidden"))
        assertFalse(QuickMenuNameRules.isValidName("-flag"))
        assertFalse(QuickMenuNameRules.isValidName("bad/name"))
        assertFalse(QuickMenuNameRules.isValidName("semi;colon"))
    }

    @Test
    fun `action message is limited by utf8 bytes`() {
        assertTrue(QuickMenuNameRules.isValidActionMsg("a".repeat(QuickMenuNameRules.MAX_ACTION_MSG_BYTES)))
        assertFalse(QuickMenuNameRules.isValidActionMsg("a".repeat(QuickMenuNameRules.MAX_ACTION_MSG_BYTES + 1)))
        assertFalse(QuickMenuNameRules.isValidActionMsg("ă".repeat(QuickMenuNameRules.MAX_ACTION_MSG_BYTES / 2 + 1)))
        assertFalse(QuickMenuNameRules.isValidActionMsg(""))
    }

    @Test
    fun `duplicate check is case sensitive and skips the item being edited`() {
        val items = listOf(
            QuickMenuAccess.newBuilder().setId(1L).setMenuName("standup").build(),
            QuickMenuAccess.newBuilder().setId(2L).setMenuName("lunch").build(),
        )
        assertTrue(QuickMenuNameRules.nameExists("standup", items, null))
        assertFalse(QuickMenuNameRules.nameExists("Standup", items, null))
        assertFalse(QuickMenuNameRules.nameExists("standup", items, 1L))
        assertTrue(QuickMenuNameRules.nameExists("lunch", items, 1L))
    }
}
