package com.mezon.mobile.home.chat.channelinfo

import android.content.Context
import android.view.View

interface TabHelper {
    fun buildView(context: Context): View
    fun reload()
}
