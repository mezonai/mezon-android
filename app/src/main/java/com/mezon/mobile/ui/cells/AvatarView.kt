package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.view.View
import coil.ImageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.util.createImgproxyUrl

class AvatarView(context: Context) : View(context) {

    private val avatarDrawable = AvatarDrawable()
    private var sizeDp = 40
    private var imageLoader: ImageLoader? = null
    private var currentUrl: String? = null
    private var disposable: Disposable? = null
    private var attachedToWindow = false

    fun setSizeDp(dp: Int) {
        sizeDp = dp
        requestLayout()
    }

    fun setInfo(id: Long, name: String) {
        avatarDrawable.setInfo(id, name)
        invalidate()
    }

    fun setImageUrl(url: String?, loader: ImageLoader) {
        imageLoader = loader
        if (url == currentUrl) return
        currentUrl = url
        disposable?.dispose()
        disposable = null
        if (url.isNullOrEmpty()) {
            avatarDrawable.setPhoto(null)
            invalidate()
            return
        }
        if (!attachedToWindow) return
        loadImage(url, loader)
    }

    private fun loadImage(url: String, loader: ImageLoader) {
        val sizePx = LayoutHelper.dp(sizeDp)
        val proxyUrl = createImgproxyUrl(url, sizePx * 2, sizePx * 2, "fill")
        val request = ImageRequest.Builder(context)
            .data(proxyUrl)
            .size(sizePx)
            .target(
                onSuccess = { result ->
                    val bmp = (result as? BitmapDrawable)?.bitmap
                    avatarDrawable.setPhoto(bmp)
                    invalidate()
                },
                onError = {
                    avatarDrawable.setPhoto(null)
                    invalidate()
                }
            )
            .build()
        disposable = loader.enqueue(request)
    }

    fun setPhoto(bitmap: android.graphics.Bitmap?) {
        avatarDrawable.setPhoto(bitmap)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedToWindow = true
        val url = currentUrl
        val loader = imageLoader
        if (url != null && loader != null && disposable == null) {
            loadImage(url, loader)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attachedToWindow = false
        disposable?.dispose()
        disposable = null
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
