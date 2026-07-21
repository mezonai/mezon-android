package com.mezon.mobile.home.messages

import android.content.Context
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.util.avatarImgproxyUrl

data class ChannelAvatarRequest(
    val channelType: Int,
    val avatarUrl: String,
    val avatarId: Long,
    val placeholderKey: String,
    val sizePx: Int
)

class ChannelAvatarLoadState {
    var loadKey: String? = null
    var disposable: MezonImageLoader.Cancellable? = null

    fun cancel() {
        disposable?.cancel()
        disposable = null
    }
}

fun loadChannelAvatar(
    context: Context,
    avatarDrawable: AvatarDrawable,
    request: ChannelAvatarRequest,
    state: ChannelAvatarLoadState,
    attached: Boolean,
    onInvalidate: () -> Unit
) {
    avatarDrawable.setInfo(request.avatarId, request.placeholderKey)

    if (request.channelType == CHANNEL_TYPE_GROUP && isDefaultGroupAvatarUrl(request.avatarUrl)) {
        if (state.loadKey == GroupAvatar.DEFAULT_LOAD_KEY && avatarDrawable.hasPhoto()) return
        state.cancel()
        state.loadKey = GroupAvatar.DEFAULT_LOAD_KEY
        avatarDrawable.setPhoto(GroupAvatar.bitmap(context))
        onInvalidate()
        return
    }

    val url = request.avatarUrl
    if (url.isBlank()) {
        if (state.loadKey == null && !avatarDrawable.hasPhoto()) return
        state.cancel()
        state.loadKey = null
        avatarDrawable.setPhoto(null)
        onInvalidate()
        return
    }

    val proxyUrl = avatarImgproxyUrl(url, request.sizePx).ifEmpty { url }
    val keyChanged = state.loadKey != proxyUrl
    if (!keyChanged && avatarDrawable.hasPhoto()) return
    if (!keyChanged && state.disposable != null) return

    state.cancel()
    state.loadKey = proxyUrl

    val loader = MezonImageLoader.getInstance(context)
    val cached = loader.getBitmapFromMemory(proxyUrl, request.sizePx, request.sizePx)
    if (cached != null) {
        avatarDrawable.setPhoto(cached)
        onInvalidate()
        return
    }

    if (keyChanged) {
        avatarDrawable.setPhoto(null)
    }
    if (!attached) return

    avatarDrawable.setLoadingPlaceholder(true)
    val expectedKey = proxyUrl
    state.disposable = loader.load(
        proxyUrl, request.sizePx, request.sizePx,
        onSuccess = { bmp ->
            if (state.loadKey != expectedKey) return@load
            avatarDrawable.setLoadingPlaceholder(false)
            avatarDrawable.setPhoto(bmp)
            onInvalidate()
        },
        onError = {
            if (state.loadKey != expectedKey) return@load
            avatarDrawable.setLoadingPlaceholder(false)
            onInvalidate()
        }
    )
}
