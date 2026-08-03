package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.util.avatarImgproxyUrl
import com.mezon.mobile.util.isAnimatedImageUrl

class AvatarView(context: Context) : View(context) {

    private val avatarDrawable = AvatarDrawable().also { it.attachToView(this) }
    private var sizeDp = 40
    private var currentUrl: String? = null
    private var cancellable: MezonImageLoader.Cancellable? = null
    private var attachedToWindow = false

    fun setSizeDp(dp: Int) {
        sizeDp = dp
        requestLayout()
    }

    fun setInfo(id: Long, username: String) {
        avatarDrawable.setInfo(id, username)
        invalidate()
    }

    fun setRoundRadius(dp: Float) {
        avatarDrawable.cornerRadius = LayoutHelper.dp(dp).toFloat()
        invalidate()
    }

    fun setImageUrl(url: String?) {
        if (url == currentUrl && (cancellable != null || avatarDrawable.hasPhoto())) return
        currentUrl = url
        cancellable?.cancel()
        cancellable = null
        if (url.isNullOrEmpty()) {
            avatarDrawable.setLoadingPlaceholder(false)
            avatarDrawable.setPhoto(null)
            invalidate()
            return
        }
        if (attachedToWindow) {
            loadImage(url)
        }
    }

    private fun loadImage(url: String) {
        cancellable?.cancel()
        val sizePx = LayoutHelper.dp(sizeDp)
        val proxyUrl = avatarImgproxyUrl(url, sizePx)
        avatarDrawable.setLoadingPlaceholder(true)
        val loader = MezonImageLoader.getInstance(context)
        
        val absoluteUrlFallback = com.mezon.mobile.util.plainSourceUrlFromImgproxy(proxyUrl) ?: proxyUrl
        val isAnimated = isAnimatedImageUrl(absoluteUrlFallback)

        val successCallback: (Any) -> Unit = { result ->
            cancellable = null
            avatarDrawable.setLoadingPlaceholder(false)
            if (result is android.graphics.drawable.Animatable) {
                avatarDrawable.setAnimatedPhoto(result as android.graphics.drawable.Drawable)
                avatarDrawable.startAnimation()
            } else if (result is android.graphics.drawable.BitmapDrawable) {
                avatarDrawable.setPhoto(result.bitmap)
            } else if (result is android.graphics.Bitmap) {
                avatarDrawable.setPhoto(result)
            }
            invalidate()
        }

        val errorCallback: (Throwable) -> Unit = {
            if (absoluteUrlFallback != proxyUrl) {
                cancellable = loader.loadDrawable(
                    absoluteUrlFallback, sizePx, sizePx, successCallback,
                    onError = {
                        cancellable = null
                        avatarDrawable.setLoadingPlaceholder(false)
                        invalidate()
                    }
                )
            } else {
                cancellable = null
                avatarDrawable.setLoadingPlaceholder(false)
                invalidate()
            }
        }

        if (isAnimated) {
            cancellable = loader.loadDrawable(absoluteUrlFallback, sizePx, sizePx, successCallback, {
                cancellable = null
                avatarDrawable.setLoadingPlaceholder(false)
                invalidate()
            })
        } else {
            cancellable = loader.load(proxyUrl, sizePx, sizePx, { bmp -> successCallback(bmp) }, errorCallback)
        }
    }

    fun setPhoto(bitmap: android.graphics.Bitmap?) {
        cancellable?.cancel()
        cancellable = null
        avatarDrawable.setPhoto(bitmap)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedToWindow = true
        val url = currentUrl
        if (url != null && cancellable == null && !avatarDrawable.hasPhoto()) {
            loadImage(url)
        }
        avatarDrawable.startAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attachedToWindow = false
        avatarDrawable.stopAnimation()
        cancellable?.cancel()
        cancellable = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = LayoutHelper.dp(sizeDp)
        setMeasuredDimension(size, size)
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onDraw(canvas: Canvas) {
        avatarDrawable.setBounds(0, 0, width, height)
        avatarDrawable.draw(canvas)
    }
}
