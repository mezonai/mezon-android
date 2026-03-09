package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.style.ReplacementSpan
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.mezon.mobile.core.LayoutHelper

class ColoredImageSpan : ReplacementSpan {

    var drawable: Drawable? = null
        private set
    var usePaintColor = true
    var overrideColor = 0
    var translateX = 0f
    var translateY = 0f
    var scaleX = 1f
    var scaleY = 1f
    private var alpha = 1f
    private var drawableColor = 0
    private var size = 0
    private var sizeWidth = 0

    private val verticalAlignment: Int

    constructor(context: Context, @DrawableRes resId: Int) :
        this(ContextCompat.getDrawable(context, resId)!!.mutate(), ALIGN_DEFAULT)

    constructor(context: Context, @DrawableRes resId: Int, verticalAlignment: Int) :
        this(ContextCompat.getDrawable(context, resId)!!.mutate(), verticalAlignment)

    constructor(drawable: Drawable) : this(drawable, ALIGN_DEFAULT)

    constructor(drawable: Drawable, verticalAlignment: Int) {
        this.drawable = drawable
        this.verticalAlignment = verticalAlignment
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
    }

    fun setSize(size: Int) {
        this.size = size
        drawable?.setBounds(0, 0, size, size)
    }

    fun setWidth(width: Int) {
        sizeWidth = width
    }

    fun setScale(sx: Float, sy: Float = sx) {
        scaleX = sx
        scaleY = sy
    }

    fun setAlpha(v: Float) {
        alpha = v
    }

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        if (sizeWidth != 0) return (kotlin.math.abs(scaleX) * sizeWidth).toInt()
        val w = if (size != 0) size else (drawable?.intrinsicWidth ?: 0)
        return (kotlin.math.abs(scaleX) * kotlin.math.abs(1f) * w).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val d = drawable ?: return

        if (overrideColor != 0) {
            if (drawableColor != overrideColor) {
                drawableColor = overrideColor
                d.colorFilter = PorterDuffColorFilter(drawableColor, PorterDuff.Mode.SRC_IN)
            }
        } else if (usePaintColor) {
            val color = paint.color
            if (drawableColor != color) {
                drawableColor = color
                d.colorFilter = PorterDuffColorFilter(drawableColor, PorterDuff.Mode.SRC_IN)
            }
        }

        canvas.save()

        val transY = when (verticalAlignment) {
            ALIGN_CENTER -> top + (bottom - top) / 2 - d.bounds.height() / 2
            ALIGN_BASELINE -> bottom - d.bounds.bottom
            else -> {
                val lineHeight = bottom - top
                val drawableHeight = if (size != 0) size else d.intrinsicHeight
                top + (lineHeight - drawableHeight) / 2
            }
        }

        canvas.translate(x + translateX, (transY + translateY).toFloat())

        if (scaleX != 1f || scaleY != 1f) {
            canvas.scale(scaleX, scaleY, 0f, d.bounds.centerY().toFloat())
        }

        d.alpha = (paint.alpha * alpha).toInt()
        d.draw(canvas)
        canvas.restore()
    }

    companion object {
        const val ALIGN_DEFAULT = 0
        const val ALIGN_BASELINE = 1
        const val ALIGN_CENTER = 2
    }
}
