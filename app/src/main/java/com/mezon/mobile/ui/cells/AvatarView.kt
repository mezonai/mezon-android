package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.util.avatarImgproxyUrl

class AvatarView(context: Context) : View(context) {

    private val avatarDrawable = AvatarDrawable()
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
            avatarDrawable.setPhoto(null)
            invalidate()
            return
        }
        if (attachedToWindow) {
            loadImage(url)
        }
    }

    private fun loadImage(url: String) {
        val sizePx = LayoutHelper.dp(sizeDp)
        val proxyUrl = avatarImgproxyUrl(url, sizePx)
        cancellable = MezonImageLoader.getInstance(context).load(
            proxyUrl, sizePx, sizePx,
            onSuccess = { bmp ->
                cancellable = null
                avatarDrawable.setPhoto(bmp)
                invalidate()
            },
            onError = {
                cancellable = null
                avatarDrawable.setPhoto(null)
                invalidate()
            }
        )
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
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attachedToWindow = false
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
