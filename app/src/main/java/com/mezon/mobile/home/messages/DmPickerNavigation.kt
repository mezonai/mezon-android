package com.mezon.mobile.home.messages

import com.mezon.mobile.MainActivity
import com.mezon.mobile.core.BaseFragment

internal fun BaseFragment.openChatFromPicker(
    channelId: Long,
    channelName: String,
    clanId: Long,
    channelType: Int,
    popSelfBeforeOpen: Boolean = false,
    onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null
) {
    if (popSelfBeforeOpen) finishFragment()
    val main = getParentActivity() as? MainActivity
    if (main != null) {
        main.openChat(channelId, channelName, clanId, channelType, replaceLastFragment = true)
    } else {
        onOpenChat?.invoke(channelId, channelName, clanId, channelType)
    }
}
