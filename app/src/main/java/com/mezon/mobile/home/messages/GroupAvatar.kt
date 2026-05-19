package com.mezon.mobile.home.messages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mezon.mobile.R
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP

const val DEFAULT_GROUP_AVATAR_URL_MARKER = "avatar-group.png"

fun DirectMessage.hasCustomAvatar(): Boolean {
    if (avatarUrl.isBlank()) return false
    if (type == CHANNEL_TYPE_GROUP) {
        return !avatarUrl.contains(DEFAULT_GROUP_AVATAR_URL_MARKER, ignoreCase = true)
    }
    return true
}

fun isDefaultGroupAvatarUrl(url: String): Boolean =
    url.isBlank() || url.contains(DEFAULT_GROUP_AVATAR_URL_MARKER, ignoreCase = true)

object GroupAvatar {
    @Volatile
    private var cached: Bitmap? = null

    fun bitmap(context: Context): Bitmap {
        cached?.let { return it }
        return BitmapFactory.decodeResource(context.resources, R.drawable.avatar_group).also {
            cached = it
        }
    }
}
