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

    fun setUserId(id: Long) {
        avatarDrawable.setUserId(id)
        invalidate()
    }

    fun setInfo(id: Long, name: String) {
        avatarDrawable.setInfo(id, name)
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
        val isAnimated = isAnimatedImageUrl(proxyUrl)

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
            cancellable = null
            avatarDrawable.setLoadingPlaceholder(false)
            invalidate()
        }

        cancellable = if (isAnimated) {
            loader.loadDrawable(proxyUrl, sizePx, sizePx, successCallback, errorCallback)
        } else {
            loader.load(proxyUrl, sizePx, sizePx, { bmp -> successCallback(bmp) }, errorCallback)
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
