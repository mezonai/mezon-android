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
import kotlin.math.max
import kotlin.math.min

class ColoredImageSpan : ReplacementSpan {

    var drawable: Drawable? = null
        private set
    var usePaintColor = true
    var overrideColor = 0
    var translateX = 0f
    var translateY = 0f
    var scaleX = 1f
    var scaleY = 1f
    var backgroundColor = 0
    private var alpha = 1f
    private var drawableColor = 0
    private var size = 0
    private var sizeWidth = 0
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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
        val w = if (sizeWidth != 0) {
            (kotlin.math.abs(scaleX) * sizeWidth).toInt()
        } else {
            val baseW = if (size != 0) size else (drawable?.intrinsicWidth ?: 0)
            (kotlin.math.abs(scaleX) * baseW).toInt()
        }
        if (fm != null && verticalAlignment == ALIGN_CENTER) {
            val h = if (size != 0) size else (drawable?.intrinsicHeight ?: 0)
            if (h > 0) {
                val p = paint.fontMetricsInt
                val textH = p.descent - p.ascent
                val off = (h - textH) / 2
                fm.ascent = p.ascent - off
                fm.descent = p.descent + off
                fm.top = min(p.top, fm.ascent)
                fm.bottom = max(p.bottom, fm.descent)
            }
        }
        return w
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

        if (backgroundColor != 0) {
            val width = if (sizeWidth != 0) {
                (kotlin.math.abs(scaleX) * sizeWidth)
            } else {
                val baseW = if (size != 0) size else (d.intrinsicWidth)
                (kotlin.math.abs(scaleX) * baseW)
            }
            backgroundPaint.color = backgroundColor
            canvas.drawRect(x, top.toFloat(), x + width, bottom.toFloat(), backgroundPaint)
        }

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
        } else {
            if (drawableColor != 0 || d.colorFilter != null) {
                drawableColor = 0
                d.clearColorFilter()
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
