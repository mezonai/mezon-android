package com.mezon.mobile.home.profile

import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.util.PROFILE_COVER_HEIGHT_PX
import com.mezon.mobile.util.PROFILE_COVER_WIDTH_PX
import com.mezon.mobile.util.isAnimatedImageUrl
import com.mezon.mobile.util.profileCoverImgproxyUrl

class ProfileCoverImageView(context: Context) : ImageView(context) {

    private var sourceUrl = ""
    private var request: MezonImageLoader.Cancellable? = null

    init {
        scaleType = ScaleType.CENTER_CROP
        cropToPadding = true
        visibility = GONE
    }

    fun bind(url: String) {
        val source = url.trim()
        if (source == sourceUrl) return
        cancel()
        sourceUrl = source
        if (source.isEmpty()) {
            showCover(null)
            return
        }
        val proxyUrl = profileCoverImgproxyUrl(source)
        val loader = MezonImageLoader.getInstance(context)
        val onError: (Exception) -> Unit = {
            if (sourceUrl == source) {
                request = null
                showCover(null)
            }
        }
        if (isAnimatedImageUrl(source)) {
            request = loader.loadDrawable(
                proxyUrl, PROFILE_COVER_WIDTH_PX, PROFILE_COVER_HEIGHT_PX,
                onSuccess = { drawable ->
                    if (sourceUrl == source) {
                        request = null
                        showCover(drawable)
                    }
                },
                onError = onError
            )
            return
        }
        loader.getBitmapFromMemory(proxyUrl, PROFILE_COVER_WIDTH_PX, PROFILE_COVER_HEIGHT_PX)?.let { cached ->
            showCover(BitmapDrawable(resources, cached))
            return
        }
        request = loader.load(
            proxyUrl, PROFILE_COVER_WIDTH_PX, PROFILE_COVER_HEIGHT_PX,
            onSuccess = { bitmap ->
                if (sourceUrl == source) {
                    request = null
                    showCover(BitmapDrawable(resources, bitmap))
                }
            },
            onError = onError
        )
    }

    fun cancel() {
        val pending = request ?: return
        pending.cancel()
        request = null
        sourceUrl = ""
    }

    private fun showCover(cover: Drawable?) {
        (drawable as? Animatable)?.stop()
        setImageDrawable(cover)
        visibility = if (cover == null) GONE else VISIBLE
        (cover as? Animatable)?.start()
    }
}
