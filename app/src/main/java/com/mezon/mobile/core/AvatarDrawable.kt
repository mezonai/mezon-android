package com.mezon.mobile.core

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextPaint
import android.view.View
import java.lang.ref.WeakReference

class AvatarDrawable : Drawable() {

    companion object {
        private const val LOADING_PLACEHOLDER_COLOR = 0x33808080
        private val avatarColors = intArrayOf(
            0xFFade603.toInt(),
            0xFF00b2cc.toInt(),
            0xFFfda63c.toInt(),
            0xFFe16dcc.toInt(),
            0xFFe8467b.toInt(),
            0xFF9c7cfd.toInt(),
            0xFF22e2b3.toInt()
        )

        fun avatarPlaceholderChar(name: String): Char? {
            if (name.isEmpty()) return null
            val first = name.firstOrNull { it.isLetterOrDigit() } ?: name.first()
            return first.uppercaseChar()
        }

        fun getColorForName(name: String): Int {
            val upper = avatarPlaceholderChar(name) ?: return avatarColors[0]
            return avatarColors[upper.code % avatarColors.size]
        }

        fun getColorIndexForName(name: String): Int {
            val upper = avatarPlaceholderChar(name) ?: return 0
            return upper.code % avatarColors.size
        }

        fun getColorForId(id: Long): Int {
            if (id == 0L) return 0xFFBDBDBD.toInt()
            return avatarColors[getColorIndex(id)]
        }

        fun getColorIndex(id: Long): Int {
            val safe = if (id == Long.MIN_VALUE) 0L else java.lang.Math.abs(id)
            return (safe % avatarColors.size).toInt()
        }

        fun getNameColorNameForId(id: Long): Int = getColorIndex(id)

        fun normalizeUsername(username: String?): String {
            if (username.isNullOrBlank()) return ""
            return username.trim().removePrefix("@")
        }
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
    private val loadingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LOADING_PLACEHOLDER_COLOR }
    private var smallSize = false
    private var scaleSize = 1f
    private var drawableByInfo = true
    private var loadingPlaceholder = false
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
    private var animatedDrawable: Drawable? = null
    private var hostViewRef: WeakReference<View>? = null
    private val clipPath = Path()

    private val childAnimCallback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            invalidateSelf()
            hostViewRef?.get()?.invalidate()
        }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            val view = hostViewRef?.get()
            if (view != null) {
                view.postDelayed(what, `when` - SystemClock.uptimeMillis())
            } else {
                scheduleSelf(what, `when`)
            }
        }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            val view = hostViewRef?.get()
            if (view != null) {
                view.removeCallbacks(what)
            } else {
                unscheduleSelf(what)
            }
        }
    }

    fun setInfo(id: Long, username: String?) {
        val normalized = normalizeUsername(username)
        if (id == currentUserId && (photoBitmap != null || animatedDrawable != null)) {
            bgColor = getColorForName(normalized)
            bgColor2 = bgColor
            initial = getAvatarSymbols(normalized)
            invalidateSelf()
            return
        }
        currentUserId = id
        bgColor = getColorForName(normalized)
        bgColor2 = bgColor
        hasGradient = false
        initial = getAvatarSymbols(normalized)
        clearAnimatedDrawable()
        photoBitmap = null
        loadingPlaceholder = false
        cachedShader = null
        cachedShaderBitmap = null
        cachedGradient = null
        invalidateSelf()
    }

    fun setInfo(account: Int, user: Any?) {
        if (user == null) return
        val map = user as? Map<*, *> ?: return
        val id = (map["id"] as? Number)?.toLong() ?: 0L
        val username = map["username"] as? String
        setInfo(id, username)
    }

    fun setInfo(chat: Any?) {
        if (chat == null) return
        val map = chat as? Map<*, *> ?: return
        val id = (map["id"] as? Number)?.toLong() ?: 0L
        val title = map["title"] as? String ?: (map["name"] as? String)
        setInfo(id, title)
    }

    private fun getAvatarSymbols(username: String): String {
        val upper = avatarPlaceholderChar(username) ?: return ""
        return upper.toString()
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

    fun setLoadingPlaceholder(value: Boolean) {
        if (loadingPlaceholder == value) return
        loadingPlaceholder = value
        invalidateSelf()
    }

    fun setPhoto(bitmap: Bitmap?) {
        clearAnimatedDrawable()
        photoBitmap = bitmap
        cachedShader = null
        cachedShaderBitmap = null
        invalidateSelf()
    }

    fun hasPhoto(): Boolean = photoBitmap != null || animatedDrawable != null

    fun attachToView(view: View) {
        hostViewRef = WeakReference(view)
    }

    fun setAnimatedPhoto(drawable: Drawable?) {
        clearAnimatedDrawable()
        animatedDrawable = drawable
        photoBitmap = null
        cachedShader = null
        cachedShaderBitmap = null
        loadingPlaceholder = false
        drawable?.callback = childAnimCallback
        invalidateSelf()
    }

    fun startAnimation() {
        (animatedDrawable as? Animatable)?.start()
    }

    fun stopAnimation() {
        (animatedDrawable as? Animatable)?.stop()
    }

    private fun clearAnimatedDrawable() {
        val old = animatedDrawable ?: return
        (old as? Animatable)?.stop()
        old.callback = null
        animatedDrawable = null
    }

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

        val anim = animatedDrawable
        if (anim != null) {
            canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(roundRectF, r, r, Path.Direction.CW)
            canvas.clipPath(clipPath)
            anim.setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
            anim.draw(canvas)
            canvas.restore()
            return
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
        } else if (loadingPlaceholder) {
            canvas.drawRoundRect(roundRectF, r, r, loadingPaint)
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
