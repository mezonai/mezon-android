package com.mezon.mobile.home.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageMentionHighlightTest {

    private val meId = 1775731111020111000L
    private val otherId = 999L
    private val adminRoleId = 5550001L
    private val myRoles = listOf(adminRoleId, 5550002L)

    private fun message(content: String, senderId: Long = otherId) = MessageEntity(
        id = 42L,
        channelId = 7L,
        senderId = senderId,
        senderName = "someone",
        senderAvatar = "",
        content = content,
        timestampSeconds = 1_700_000_000L,
        code = MessageEntity.CODE_CHAT
    )

    private fun directMentionContent() =
        """{"t":"hi @me","mentions":[{"s":3,"e":6,"user_id":"$meId","username":"me"}]}"""

    private fun roleMentionContent() =
        """{"t":"hi @Admin","mentions":[{"s":3,"e":9,"role_id":"$adminRoleId","username":"Admin"}]}"""

    private fun replyToMeContent() =
        """{"t":"got it","references":[{"message_id":"10","message_ref_id":"9","ref_type":0,"message_sender_id":"$meId","message_sender_username":"me","content":"{}"}]}"""

    @Test
    fun `direct user mention highlights`() {
        assertTrue(message(directMentionContent()).isMentionOrReplyForUser(meId, myRoles))
    }

    @Test
    fun `here mention highlights`() {
        val content = """{"t":"hey @here","mentions":[{"s":4,"e":9,"user_id":"here"}]}"""
        assertTrue(message(content).isMentionOrReplyForUser(meId, myRoles))
    }

    @Test
    fun `role mention highlights when user holds the role`() {
        assertTrue(message(roleMentionContent()).isMentionOrReplyForUser(meId, myRoles))
    }

    @Test
    fun `role mention does not highlight when user lacks the role`() {
        assertFalse(message(roleMentionContent()).isMentionOrReplyForUser(meId, listOf(5550002L)))
    }

    @Test
    fun `reply to my message highlights`() {
        assertTrue(message(replyToMeContent()).isMentionOrReplyForUser(meId, myRoles))
    }

    @Test
    fun `reply to someone else does not highlight`() {
        val content =
            """{"t":"got it","references":[{"message_id":"10","message_sender_id":"$otherId"}]}"""
        assertFalse(message(content).isMentionOrReplyForUser(meId, myRoles))
    }

    @Test
    fun `plain message does not highlight`() {
        assertFalse(message("""{"t":"hello world"}""").isMentionOrReplyForUser(meId, myRoles))
    }

    @Test
    fun `role mention and reply are invisible to the user-only mention check`() {
        assertFalse(message(roleMentionContent()).hasMention(meId.toString()))
        assertFalse(message(replyToMeContent()).hasMention(meId.toString()))
        assertTrue(message(directMentionContent()).hasMention(meId.toString()))
    }
}
