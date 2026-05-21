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
    const val DEFAULT_LOAD_KEY = "\u0000group_default_avatar"

    @Volatile
    private var cached: Bitmap? = null

    fun bitmap(context: Context): Bitmap {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val decoded = BitmapFactory.decodeResource(context.resources, R.drawable.avatar_group)
                ?: throw IllegalStateException("Missing R.drawable.avatar_group")
            val immutable = if (decoded.isMutable) {
                val copy = decoded.copy(decoded.config ?: Bitmap.Config.ARGB_8888, false)
                    ?: throw IllegalStateException("Failed to copy avatar_group bitmap")
                decoded.recycle()
                copy
            } else {
                decoded
            }
            cached = immutable
            return immutable
        }
    }
}
