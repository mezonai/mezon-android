package com.mezon.mobile.home.chat.input

import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.chat.EmojiItem
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.clans.ClanRole

sealed class InputSuggestionItem {
    data object Here : InputSuggestionItem()
    data class Member(val member: ClanMember) : InputSuggestionItem()
    data class Role(val role: ClanRole) : InputSuggestionItem()
    data class Channel(val entity: ClanChannelEntity, val subText: String) : InputSuggestionItem()
    data class Emoji(val item: EmojiItem) : InputSuggestionItem()
}
