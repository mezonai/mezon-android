package com.mezon.mobile.home.messages

import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.util.avatarImgproxyUrl
import com.mezon.mobile.util.isAnimatedImageUrl

import android.view.View

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
    hostView: View,
    avatarDrawable: AvatarDrawable,
    request: ChannelAvatarRequest,
    state: ChannelAvatarLoadState,
    attached: Boolean,
    onInvalidate: () -> Unit
) {
    avatarDrawable.attachToView(hostView)
    avatarDrawable.setInfo(request.avatarId, request.placeholderKey)

    if (request.channelType == CHANNEL_TYPE_GROUP && isDefaultGroupAvatarUrl(request.avatarUrl)) {
        if (state.loadKey == GroupAvatar.DEFAULT_LOAD_KEY && avatarDrawable.hasPhoto()) return
        state.cancel()
        state.loadKey = GroupAvatar.DEFAULT_LOAD_KEY
        avatarDrawable.setPhoto(GroupAvatar.bitmap(hostView.context))
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

    val loader = MezonImageLoader.getInstance(hostView.context)
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

    val absoluteUrlFallback = com.mezon.mobile.util.plainSourceUrlFromImgproxy(proxyUrl) ?: proxyUrl
    val isAnimated = isAnimatedImageUrl(absoluteUrlFallback)

    val successCallback: (Any) -> Unit = { result ->
        if (state.loadKey == expectedKey) {
            avatarDrawable.setLoadingPlaceholder(false)
            if (result is android.graphics.drawable.Animatable) {
                avatarDrawable.setAnimatedPhoto(result as android.graphics.drawable.Drawable)
                avatarDrawable.startAnimation()
            } else if (result is android.graphics.drawable.BitmapDrawable) {
                avatarDrawable.setPhoto(result.bitmap)
            } else if (result is android.graphics.Bitmap) {
                avatarDrawable.setPhoto(result)
            }
            onInvalidate()
        }
    }

    val errorCallback: (Throwable) -> Unit = {
        if (absoluteUrlFallback != proxyUrl) {
            state.disposable = loader.loadDrawable(
                absoluteUrlFallback, request.sizePx, request.sizePx, successCallback,
                onError = {
                    if (state.loadKey == expectedKey) {
                        avatarDrawable.setLoadingPlaceholder(false)
                        onInvalidate()
                    }
                }
            )
        } else {
            if (state.loadKey == expectedKey) {
                avatarDrawable.setLoadingPlaceholder(false)
                onInvalidate()
            }
        }
    }

    if (isAnimated) {
        state.disposable = loader.loadDrawable(absoluteUrlFallback, request.sizePx, request.sizePx, successCallback, {
            if (state.loadKey == expectedKey) {
                avatarDrawable.setLoadingPlaceholder(false)
                onInvalidate()
            }
        })
    } else {
        state.disposable = loader.load(proxyUrl, request.sizePx, request.sizePx, { bmp -> successCallback(bmp) }, errorCallback)
    }
}
