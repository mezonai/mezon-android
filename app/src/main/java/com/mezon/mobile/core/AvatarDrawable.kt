package com.mezon.mobile.core

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint

class AvatarDrawable : Drawable() {

    companion object {
        private val avatarColors = intArrayOf(
            0xFFE57373.toInt(), 0xFF81C784.toInt(), 0xFF64B5F6.toInt(),
            0xFFFFB74D.toInt(), 0xFFBA68C8.toInt(), 0xFF4DB6AC.toInt(),
            0xFFFF8A65.toInt(), 0xFFA1887F.toInt(), 0xFF90A4AE.toInt()
        )

        fun colorForId(id: Long): Int = avatarColors[(id % avatarColors.size).toInt().coerceAtLeast(0)]
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private var initial: String = ""
    private var bgColor: Int = avatarColors[0]
    private var photoBitmap: Bitmap? = null
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setInfo(id: Long, name: String) {
        bgColor = colorForId(id)
        initial = if (name.isNotEmpty()) name.first().uppercaseChar().toString() else "?"
        photoBitmap = null
        invalidateSelf()
    }

    fun setPhoto(bitmap: Bitmap?) {
        photoBitmap = bitmap
        invalidateSelf()
    }

    fun hasPhoto(): Boolean = photoBitmap != null

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val radius = bounds.width() / 2f

        val photo = photoBitmap
        if (photo != null && !photo.isRecycled) {
            photoPaint.shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                val scale = bounds.width().toFloat() / photo.width
                val matrix = android.graphics.Matrix()
                matrix.setScale(scale, scale)
                matrix.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
                setLocalMatrix(matrix)
            }
            canvas.drawCircle(cx, cy, radius, photoPaint)
            photoPaint.shader = null
        } else {
            bgPaint.color = bgColor
            canvas.drawCircle(cx, cy, radius, bgPaint)
            textPaint.textSize = radius * 0.85f
            val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(initial, cx, textY, textPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        bgPaint.alpha = alpha
        textPaint.alpha = alpha
        photoPaint.alpha = alpha
    }

    override fun setColorFilter(filter: ColorFilter?) {
        bgPaint.colorFilter = filter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
