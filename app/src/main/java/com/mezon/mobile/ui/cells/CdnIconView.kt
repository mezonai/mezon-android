package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader

class CdnIconView(context: Context, private val theme: ThemeColors) : View(context) {

    private var drawable: android.graphics.drawable.Drawable? = null
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sizeDp = 24
    private var isCircular = false
    private var currentUrl: String? = null
    private var cancellable: MezonImageLoader.Cancellable? = null
    private var attachedToWindow = false

    private val drawableCallback = object : android.graphics.drawable.Drawable.Callback {
        override fun invalidateDrawable(who: android.graphics.drawable.Drawable) {
            invalidate()
        }
        override fun scheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable, `when`: Long) {
            postDelayed(what, `when` - SystemClock.uptimeMillis())
        }
        override fun unscheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable) {
            removeCallbacks(what)
        }
    }

    fun setSizeDp(dp: Int) {
        sizeDp = dp
        requestLayout()
    }

    fun setCircular(circular: Boolean) {
        isCircular = circular
        invalidate()
    }

    fun setImageUrl(url: String?) {
        if (url == currentUrl) return
        currentUrl = url
        cancellable?.cancel()
        cancellable = null
        drawable?.callback = null
        drawable = null
        if (url.isNullOrEmpty()) {
            invalidate()
            return
        }
        if (!attachedToWindow) return
        loadImage(url)
    }

    private fun loadImage(url: String) {
        val px = LayoutHelper.dp(sizeDp)
        cancellable = MezonImageLoader.getInstance(context).loadDrawable(
            url, px, px,
            cacheAnimated = true,
            onSuccess = { drw ->
                drawable?.callback = null
                drawable = drw
                drw.callback = drawableCallback
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && drw is android.graphics.drawable.AnimatedImageDrawable) {
                    drw.start()
                }
                invalidate()
            },
            onError = {
                drawable?.callback = null
                drawable = null
                invalidate()
            }
        )
    }

    fun setBitmap(bmp: Bitmap?) {
        drawable?.callback = null
        if (bmp != null) {
            drawable = android.graphics.drawable.BitmapDrawable(resources, bmp)
        } else {
            drawable = null
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedToWindow = true
        val url = currentUrl
        if (url != null && drawable == null && cancellable == null) {
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
        val drw = drawable
        if (drw != null) {
            if (isCircular) {
                canvas.save()
                val path = android.graphics.Path()
                path.addCircle(width / 2f, height / 2f, width / 2f, android.graphics.Path.Direction.CW)
                canvas.clipPath(path)
                drw.setBounds(0, 0, width, height)
                drw.draw(canvas)
                canvas.restore()
            } else {
                drw.setBounds(0, 0, width, height)
                drw.draw(canvas)
            }
        } else {
            placeholderPaint.color = theme.surfaceVariant
            if (isCircular) {
                canvas.drawCircle(width / 2f, height / 2f, width / 2f, placeholderPaint)
            } else {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), placeholderPaint)
            }
        }
    }

    override fun verifyDrawable(who: android.graphics.drawable.Drawable): Boolean {
        return who == drawable || super.verifyDrawable(who)
    }
}
