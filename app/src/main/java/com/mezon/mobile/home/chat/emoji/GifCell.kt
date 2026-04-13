package com.mezon.mobile.home.chat.emoji

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.SharedConfig
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.network.TenorGif

private val CELL_HEIGHT = LayoutHelper.dp(100f)
private val CORNER_RADIUS = LayoutHelper.dp(10f).toFloat()

class GifCell(context: Context, private val themeColors: ThemeColors) : View(context) {

    private var gif: TenorGif? = null
    private var animDrawable: Drawable? = null
    private var cancellable: MezonImageLoader.Cancellable? = null
    private val loader = MezonImageLoader.getInstance(context)
    private val clipPath = android.graphics.Path()
    private val clipRect = android.graphics.RectF()

    private val drawableCallback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) { this@GifCell.invalidate() }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            this@GifCell.postDelayed(what, `when` - android.os.SystemClock.uptimeMillis())
        }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            this@GifCell.removeCallbacks(what)
        }
    }

    fun getGif(): TenorGif? = gif

    fun setGif(item: TenorGif) {
        if (gif?.id == item.id) return
        stopAnimation()
        cancellable?.cancel()
        animDrawable = null
        gif = item

        val reqW = if (measuredWidth > 0) measuredWidth else LayoutHelper.dp(170f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !SharedConfig.deviceIsLow()) {
            cancellable = loader.loadDrawable(item.thumbnailUrl, reqW, CELL_HEIGHT,
                onSuccess = { d ->
                    animDrawable = d
                    d.callback = drawableCallback
                    if (d is AnimatedImageDrawable) d.start()
                    invalidate()
                })
        } else {
            cancellable = loader.load(item.thumbnailUrl, reqW, CELL_HEIGHT, onSuccess = { bmp ->
                animDrawable = android.graphics.drawable.BitmapDrawable(resources, bmp)
                invalidate()
            })
        }
        invalidate()
    }

    override fun invalidate() {
        if (gif == null) return
        super.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, CELL_HEIGHT)
    }

    override fun onDraw(canvas: Canvas) {
        val w = measuredWidth.toFloat()
        val h = measuredHeight.toFloat()

        canvas.save()
        clipPath.reset()
        clipRect.set(0f, 0f, w, h)
        clipPath.addRoundRect(clipRect, CORNER_RADIUS, CORNER_RADIUS, android.graphics.Path.Direction.CW)
        canvas.clipPath(clipPath)

        val d = animDrawable
        if (d != null) {
            drawCover(canvas, d, w.toInt(), h.toInt())
        } else {
            canvas.drawColor(Color.BLACK)
        }

        canvas.restore()
    }

    private fun drawCover(canvas: Canvas, d: Drawable, viewW: Int, viewH: Int) {
        val iw = d.intrinsicWidth
        val ih = d.intrinsicHeight
        if (iw <= 0 || ih <= 0) {
            d.setBounds(0, 0, viewW, viewH)
            d.draw(canvas)
            return
        }
        val scale = maxOf(viewW.toFloat() / iw, viewH.toFloat() / ih)
        val dx = (viewW - iw * scale) / 2f
        val dy = (viewH - ih * scale) / 2f
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        d.setBounds(0, 0, iw, ih)
        d.draw(canvas)
        canvas.restore()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val d = animDrawable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable) d.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
        cancellable?.cancel()
        cancellable = null
    }

    fun stopHeavyOperations() {
        val d = animDrawable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable) d.stop()
    }

    fun startHeavyOperations() {
        val d = animDrawable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable) d.start()
    }

    private fun stopAnimation() {
        val d = animDrawable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable) d.stop()
        d?.callback = null
    }
}
