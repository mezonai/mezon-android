package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.view.View
import coil.ImageLoader
import coil.request.ImageRequest
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper

class AvatarView(context: Context) : View(context) {

    private val avatarDrawable = AvatarDrawable()
    private var sizeDp = 40
    private var imageLoader: ImageLoader? = null
    private var currentUrl: String? = null

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
        if (url.isNullOrEmpty()) {
            avatarDrawable.setPhoto(null)
            invalidate()
            return
        }
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(LayoutHelper.dp(sizeDp))
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
        loader.enqueue(request)
    }

    fun setPhoto(bitmap: Bitmap?) {
        avatarDrawable.setPhoto(bitmap)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = LayoutHelper.dp(sizeDp)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        avatarDrawable.setBounds(0, 0, width, height)
        avatarDrawable.draw(canvas)
    }
}
