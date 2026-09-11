package com.mezon.mobile.home.chat.input

import com.mezon.mezon.api.QuickMenuAccess

data class SlashCommand(
    val id: Long,
    val name: String,
    val actionMsg: String,
) {
    companion object {
        fun fromProto(proto: QuickMenuAccess): SlashCommand =
            SlashCommand(id = proto.id, name = proto.menuName, actionMsg = proto.actionMsg)
    }
}
