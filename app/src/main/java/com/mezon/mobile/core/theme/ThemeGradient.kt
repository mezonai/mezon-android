package com.mezon.mobile.core.theme

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Desktop surface metadata. A preview always uses its own bounds. */
class ThemeGradient(
    val angle: Float,
    colors: IntArray,
    positions: FloatArray,
    val viewportAnchored: Boolean
) {
    private val colors = colors.copyOf()
    private val positions = positions.copyOf()
    internal val startColor: Int get() = this.colors.first()

    fun previewDrawable(cornerRadius: Float): Drawable = drawable(cornerRadius, null)

    internal fun surfaceDrawable(view: View): Drawable =
        drawable(0f, view.takeIf { viewportAnchored })

    private fun drawable(cornerRadius: Float, anchor: View?): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        private val reference = RectF()
        private val anchorLocation = IntArray(2)
        private val windowLocation = IntArray(2)

        override fun onBoundsChange(bounds: Rect) {
            rect.set(bounds)
            updateShader(rect)
        }

        private fun updateShader(area: RectF) {
            if (reference == area || area.isEmpty) return
            reference.set(area)
            val radians = Math.toRadians((angle - 90).toDouble())
            val longestSide = max(area.width(), area.height())
            val x = cos(radians).toFloat() * area.width() / longestSide
            val y = sin(radians).toFloat() * area.height() / longestSide
            val length = if (abs(x) > abs(y)) area.width() else area.height()
            val halfAxis = length / (2 * hypot(x, y))
            paint.shader = LinearGradient(
                area.centerX() - x * halfAxis, area.centerY() - y * halfAxis,
                area.centerX() + x * halfAxis, area.centerY() + y * halfAxis,
                colors, positions, Shader.TileMode.CLAMP
            )
        }

        private val windowBounds = RectF()

        override fun draw(canvas: Canvas) {
            if (anchor != null && anchor.isAttachedToWindow) {
                val window = anchor.rootView
                anchor.getLocationInWindow(anchorLocation)
                window.getLocationInWindow(windowLocation)
                val left = (windowLocation[0] - anchorLocation[0]).toFloat()
                val top = (windowLocation[1] - anchorLocation[1]).toFloat()
                windowBounds.set(left, top, left + window.width, top + window.height)
                updateShader(windowBounds)
            }
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

internal fun View.applyThemeSurface(color: Int, gradient: ThemeGradient?, anchorToWindow: Boolean = false) {
    if (gradient == null) {
        setBackgroundColor(color)
    } else {
        background = if (anchorToWindow) gradient.surfaceDrawable(this) else gradient.previewDrawable(0f)
    }
}

internal fun themeSurfaceShape(color: Int, cornerRadius: Float, strokeColor: Int, strokeWidth: Int): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        this.cornerRadius = cornerRadius
        setStroke(strokeWidth, strokeColor)
    }
