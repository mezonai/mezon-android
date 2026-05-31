package com.mezon.mobile.home.messages

import com.mezon.mobile.MainActivity
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment

internal fun BaseFragment.openChatFromPicker(
    channelId: Long,
    channelName: String,
    clanId: Long,
    channelType: Int,
    popSelfBeforeOpen: Boolean = false,
    onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null
) {
    val main = getParentActivity() as? MainActivity
    if (main != null) {
        if (popSelfBeforeOpen) {
            finishFragment()
            AndroidUtilities.runOnUIThread(Runnable {
                if (main.isFinishing || main.isDestroyed) return@Runnable
                main.openChat(
                    channelId,
                    channelName,
                    clanId,
                    channelType,
                    replaceLastFragment = true
                )
            }, 180L)
        } else {
            main.openChat(
                channelId,
                channelName,
                clanId,
                channelType,
                replaceLastFragment = true
            )
        }
    } else {
        if (popSelfBeforeOpen) finishFragment()
        onOpenChat?.invoke(channelId, channelName, clanId, channelType)
    }
}
