package com.mezon.mobile.core

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
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

        fun getColorForId(id: Long): Int {
            if (id == 0L) return 0xFFBDBDBD.toInt()
            return avatarColors[getColorIndex(id)]
        }

        fun getColorIndex(id: Long): Int = (java.lang.Math.abs(id) % avatarColors.size).toInt()

        fun getNameColorNameForId(id: Long): Int = getColorIndex(id)
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private var currentUserId: Long = 0L
    private var initial: String = ""
    private var bgColor: Int = avatarColors[0]
    private var bgColor2: Int = avatarColors[0]
    private var hasGradient = false
    private var photoBitmap: Bitmap? = null
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var smallSize = false
    private var scaleSize = 1f
    private var drawableByInfo = true
    private var customTextSize = 0f
    private val roundRectF = RectF()
    var cornerRadius: Float = Float.MAX_VALUE

    private var cachedShader: BitmapShader? = null
    private var cachedShaderBitmap: Bitmap? = null
    private val shaderMatrix = android.graphics.Matrix()
    private var cachedGradient: LinearGradient? = null
    private var cachedGradientColor1 = 0
    private var cachedGradientColor2 = 0
    private var cachedGradientHeight = 0f
    private var lastBoundsWidth = -1
    private var lastBoundsHeight = -1

    fun setInfo(id: Long, firstName: String?, lastName: String?) {
        if (id == currentUserId && photoBitmap != null) {
            initial = getAvatarSymbols(firstName, lastName)
            invalidateSelf()
            return
        }
        currentUserId = id
        bgColor = getColorForId(id)
        bgColor2 = bgColor
        hasGradient = false
        initial = getAvatarSymbols(firstName, lastName)
        photoBitmap = null
        cachedShader = null
        cachedShaderBitmap = null
        cachedGradient = null
        invalidateSelf()
    }

    fun setInfo(id: Long, name: String) {
        setInfo(id, name, null)
    }

    fun setInfo(account: Int, user: Any?) {
        if (user == null) return
        val map = user as? Map<*, *>
        if (map != null) {
            val id = (map["id"] as? Number)?.toLong() ?: 0L
            val first = map["first_name"] as? String
            val last = map["last_name"] as? String
            setInfo(id, first, last)
        }
    }

    fun setInfo(chat: Any?) {
        if (chat == null) return
        val map = chat as? Map<*, *>
        if (map != null) {
            val id = (map["id"] as? Number)?.toLong() ?: 0L
            val title = map["title"] as? String ?: (map["name"] as? String)
            setInfo(id, title, null)
        }
    }

    private fun getAvatarSymbols(firstName: String?, lastName: String?): String {
        val sb = StringBuilder()
        if (!firstName.isNullOrEmpty()) sb.append(firstName.first().uppercaseChar())
        if (!lastName.isNullOrEmpty()) {
            val lastWord = lastName.substringAfterLast(" ", lastName)
            if (lastWord.isNotEmpty()) sb.append(lastWord.first().uppercaseChar())
        }
        return if (sb.isEmpty()) "?" else sb.toString()
    }

    fun setColor(color: Int) {
        hasGradient = false
        bgColor = color
        bgColor2 = color
        invalidateSelf()
    }

    fun setColor(color1: Int, color2: Int) {
        hasGradient = true
        bgColor = color1
        bgColor2 = color2
        invalidateSelf()
    }

    fun setSmallSize(small: Boolean) {
        smallSize = small
        invalidateSelf()
    }

    fun setTextSize(size: Int) {
        customTextSize = size.toFloat()
        invalidateSelf()
    }

    fun setScaleSize(value: Float) {
        scaleSize = value
        invalidateSelf()
    }

    fun setDrawableByInfo(value: Boolean) {
        drawableByInfo = value
        invalidateSelf()
    }

    fun setPhoto(bitmap: Bitmap?) {
        photoBitmap = bitmap
        cachedShader = null
        cachedShaderBitmap = null
        invalidateSelf()
    }

    fun hasPhoto(): Boolean = photoBitmap != null

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val size = bounds.width()
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val radius = size / 2f
        val r = if (cornerRadius == Float.MAX_VALUE) radius else cornerRadius
        roundRectF.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())

        val boundsChanged = bounds.width() != lastBoundsWidth || bounds.height() != lastBoundsHeight
        if (boundsChanged) {
            lastBoundsWidth = bounds.width()
            lastBoundsHeight = bounds.height()
        }

        val photo = photoBitmap
        if (photo != null && !photo.isRecycled) {
            if (cachedShaderBitmap !== photo || cachedShader == null || boundsChanged) {
                cachedShaderBitmap = photo
                val shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val bw = bounds.width().toFloat()
                val bh = bounds.height().toFloat()
                val pw = photo.width.toFloat()
                val ph = photo.height.toFloat()
                val scale = maxOf(bw / pw, bh / ph)
                shaderMatrix.setScale(scale, scale)
                shaderMatrix.postTranslate(
                    bounds.left + (bw - pw * scale) * 0.5f,
                    bounds.top + (bh - ph * scale) * 0.5f
                )
                shader.setLocalMatrix(shaderMatrix)
                cachedShader = shader
            }
            photoPaint.shader = cachedShader
            canvas.drawRoundRect(roundRectF, r, r, photoPaint)
        } else if (drawableByInfo) {
            if (hasGradient) {
                val h = size.toFloat()
                if (cachedGradient == null || cachedGradientColor1 != bgColor || cachedGradientColor2 != bgColor2 || cachedGradientHeight != h) {
                    cachedGradient = LinearGradient(0f, 0f, 0f, h, bgColor, bgColor2, Shader.TileMode.CLAMP)
                    cachedGradientColor1 = bgColor
                    cachedGradientColor2 = bgColor2
                    cachedGradientHeight = h
                }
                bgPaint.shader = cachedGradient
                bgPaint.color = Color.TRANSPARENT
            } else {
                bgPaint.shader = null
                bgPaint.color = bgColor
            }
            canvas.drawRoundRect(roundRectF, r, r, bgPaint)
            bgPaint.shader = null
            val textSize = when {
                customTextSize > 0 -> customTextSize
                smallSize -> radius * 0.6f * scaleSize
                else -> radius * 0.85f * scaleSize
            }
            textPaint.textSize = textSize
            val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
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
