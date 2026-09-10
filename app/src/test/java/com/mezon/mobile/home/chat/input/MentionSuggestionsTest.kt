package com.mezon.mobile.home.chat.input

import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.clans.ClanRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionSuggestionsTest {

    private fun member(
        userId: Long,
        username: String = "",
        displayName: String = "",
        clanNick: String = ""
    ) = ClanMember(
        userId = userId,
        username = username,
        displayName = displayName,
        avatarUrl = "",
        isOnline = false,
        clanNick = clanNick,
        clanAvatar = "",
        clanId = 100L,
        roleIds = emptyList()
    )

    private fun role(roleId: Long, title: String) = ClanRole(
        roleId = roleId,
        clanId = 100L,
        title = title,
        color = 0,
        colorHexRaw = "",
        iconUrl = "",
        slug = "",
        permissionSlugs = emptyList(),
        maxLevelPermission = 0,
        memberCount = 0,
        rolePermissions = emptyList(),
        roleChannelActive = 0,
        channelIds = emptyList(),
        orderRole = 0
    )

    private fun channel(channelId: Long, label: String) = ClanChannelEntity(
        clanId = 100L,
        channelId = channelId,
        parentId = 0L,
        categoryId = 0L,
        categoryName = "general",
        channelLabel = label,
        type = 1,
        isPrivate = false,
        topic = "",
        unreadCount = 0,
        isMuted = false
    )

    private fun context(
        members: List<ClanMember>,
        roles: List<ClanRole> = emptyList(),
        membersPending: Boolean = false
    ) = InputSuggestionsController.MentionContext(
        members = members,
        roles = roles,
        includeHere = true,
        includeRoles = true,
        membersPending = membersPending
    )

    private fun memberIds(items: List<InputSuggestionItem>): List<Long> =
        items.filterIsInstance<InputSuggestionItem.Member>().map { it.member.userId }

    @Test
    fun `members without any name are not offered on an empty keyword`() {
        val items = InputSuggestionsController.buildMentionItems(
            "",
            context(listOf(member(userId = 7L), member(userId = 8L, username = "nguyentran")))
        )

        assertEquals(listOf(8L), memberIds(items))
    }

    @Test
    fun `a member known only by username is still offered`() {
        val items = InputSuggestionsController.buildMentionItems(
            "",
            context(listOf(member(userId = 9L, username = "thin.tranhoang")))
        )

        assertEquals(listOf(9L), memberIds(items))
    }

    @Test
    fun `clan nick and display name are used as the mention label`() {
        val nicked = member(userId = 1L, username = "anh", clanNick = "Anh Tran")
        val displayOnly = member(userId = 2L, username = "gia", displayName = "Gia Chu Van")

        assertEquals("Anh Tran", InputSuggestionsController.mentionDisplayName(nicked))
        assertEquals("Gia Chu Van", InputSuggestionsController.mentionDisplayName(displayOnly))
        assertEquals("", InputSuggestionsController.mentionDisplayName(member(userId = 3L)))
    }

    @Test
    fun `unresolved members are reported so the caller can refetch`() {
        val resolved = listOf(member(userId = 1L, username = "anh"))
        val partiallyResolved = resolved + member(userId = 2L)

        assertTrue(InputSuggestionsController.hasUnresolvedMembers(partiallyResolved))
        assertFalse(InputSuggestionsController.hasUnresolvedMembers(resolved))
    }

    @Test
    fun `roles without a title are not offered`() {
        val items = InputSuggestionsController.buildMentionItems(
            "",
            context(members = emptyList(), roles = listOf(role(1L, ""), role(2L, "Admin")))
        )

        assertEquals(
            listOf(2L),
            items.filterIsInstance<InputSuggestionItem.Role>().map { it.role.roleId }
        )
    }

    @Test
    fun `channels without a label are not offered`() {
        val items = InputSuggestionsController.buildChannelItems(
            "",
            listOf(channel(1L, ""), channel(2L, "general"))
        )

        assertEquals(
            listOf(2L),
            items.filterIsInstance<InputSuggestionItem.Channel>().map { it.entity.channelId }
        )
    }

    @Test
    fun `a pending member load appends a loading row instead of blank rows`() {
        val items = InputSuggestionsController.buildMentionItems(
            "",
            context(listOf(member(userId = 7L)), membersPending = true)
        )

        assertEquals(listOf(InputSuggestionItem.Here, InputSuggestionItem.Loading), items)
    }

    @Test
    fun `a keyword that matches nobody still shows the loading row while members load`() {
        val items = InputSuggestionsController.buildMentionItems(
            "nguyen",
            context(listOf(member(userId = 7L)), membersPending = true)
        )

        assertEquals(listOf(InputSuggestionItem.Loading), items)
    }
}
