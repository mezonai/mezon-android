package com.mezon.mobile.home.chat.emoji

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.SharedConfig
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.network.TenorCategory

private val CELL_HEIGHT = LayoutHelper.dp(100f)
private val CORNER_RADIUS = LayoutHelper.dp(10f).toFloat()

class GifCategoryCell(context: Context, private val themeColors: ThemeColors) : View(context) {

    private var category: TenorCategory? = null
    private var drawable: Drawable? = null
    private var cancellable: MezonImageLoader.Cancellable? = null
    private val loader = MezonImageLoader.getInstance(context)
    private var nameLayout: StaticLayout? = null
    private val clipPath = android.graphics.Path()
    private val clipRect = android.graphics.RectF()

    private val drawableCallback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) { this@GifCategoryCell.invalidate() }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            this@GifCategoryCell.postDelayed(what, `when` - android.os.SystemClock.uptimeMillis())
        }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            this@GifCategoryCell.removeCallbacks(what)
        }
    }

    fun getCategory(): TenorCategory? = category

    fun setCategory(item: TenorCategory) {
        if (category?.name == item.name) return
        stopAnimation()
        cancellable?.cancel()
        drawable = null
        category = item

        nameLayout = buildNameLayout(item.name, measuredWidth.coerceAtLeast(LayoutHelper.dp(100f)))

        val reqW = if (measuredWidth > 0) measuredWidth else LayoutHelper.dp(170f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !SharedConfig.deviceIsLow()) {
            cancellable = loader.loadDrawable(item.imageUrl, reqW, CELL_HEIGHT,
                onSuccess = { d ->
                    drawable = d
                    d.callback = drawableCallback
                    if (d is AnimatedImageDrawable) d.start()
                    invalidate()
                })
        } else {
            cancellable = loader.load(item.imageUrl, reqW, CELL_HEIGHT, onSuccess = { bmp ->
                drawable = android.graphics.drawable.BitmapDrawable(resources, bmp)
                invalidate()
            })
        }
        invalidate()
    }

    override fun invalidate() {
        if (category == null) return
        super.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, CELL_HEIGHT)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cat = category ?: return
        nameLayout = buildNameLayout(cat.name, w)
    }

    override fun onDraw(canvas: Canvas) {
        val w = measuredWidth.toFloat()
        val h = measuredHeight.toFloat()

        canvas.save()
        clipPath.reset()
        clipRect.set(0f, 0f, w, h)
        clipPath.addRoundRect(clipRect, CORNER_RADIUS, CORNER_RADIUS, android.graphics.Path.Direction.CW)
        canvas.clipPath(clipPath)

        val d = drawable
        if (d != null) {
            drawCover(canvas, d, measuredWidth, measuredHeight)
        } else {
            canvas.drawColor(0x1A000000)
        }

        canvas.drawRect(0f, 0f, w, h, overlayPaint)

        val layout = nameLayout
        if (layout != null) {
            canvas.save()
            val textX = (w - layout.width) / 2f
            val textY = (h - layout.height) / 2f
            canvas.translate(textX, textY)
            layout.draw(canvas)
            canvas.restore()
        }

        canvas.restore()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val d = drawable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable) d.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
        cancellable?.cancel()
        cancellable = null
    }

    fun stopHeavyOperations() {
        val d = drawable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable) d.stop()
    }

    fun startHeavyOperations() {
        val d = drawable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable) d.start()
    }

    private fun stopAnimation() {
        val d = drawable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable) d.stop()
        d?.callback = null
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

    private fun buildNameLayout(name: String, width: Int): StaticLayout {
        val maxW = width.coerceAtLeast(1)
        return StaticLayout.Builder.obtain(name, 0, name.length, namePaint, maxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(2)
            .build()
    }

    companion object {
        private val overlayPaint = Paint().apply { color = 0x60000000 }
        private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = LayoutHelper.dp(14f).toFloat()
            typeface = Typeface.DEFAULT_BOLD
        }
    }
}
