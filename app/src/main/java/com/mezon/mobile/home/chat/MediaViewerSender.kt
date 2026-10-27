package com.mezon.mobile.home.chat

import com.mezon.mobile.BuildConfig

data class MediaViewerSender(val name: String, val avatarUrl: String?)

internal val MEDIA_ANONYMOUS_USER_ID = BuildConfig.MEZON_ANONYMOUS_USER_ID.toLongOrNull() ?: -1L

fun resolveMediaViewerSender(
    uploaderId: Long,
    name: String?,
    avatarUrl: String?,
    anonymousLabel: String = "Anonymous"
): MediaViewerSender {
    val trimmedName = name?.trim().orEmpty()
    val anonymous = uploaderId == MEDIA_ANONYMOUS_USER_ID
    return MediaViewerSender(
        name = trimmedName.takeUnless {
            it.isEmpty() || (anonymous && it == MEDIA_ANONYMOUS_USER_ID.toString())
        } ?: if (anonymous) anonymousLabel else "User",
        avatarUrl = avatarUrl?.trim()?.takeIf { it.isNotEmpty() }
    )
}
