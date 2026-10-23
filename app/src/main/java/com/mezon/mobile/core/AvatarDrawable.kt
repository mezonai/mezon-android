package com.mezon.mobile.core

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
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
        private const val DEFAULT_USER_HEAD_CENTER_Y = 0.498f
        private const val DEFAULT_USER_HEAD_RADIUS = 0.235f
        private const val DEFAULT_USER_BODY_TOP = 0.734f
        private val avatarColors = intArrayOf(
            0xFFade603.toInt(),
            0xFF00b2cc.toInt(),
            0xFFfda63c.toInt(),
            0xFFe16dcc.toInt(),
            0xFFe8467b.toInt(),
            0xFF9c7cfd.toInt(),
            0xFF22e2b3.toInt()
        )

        private fun avatarPlaceholderChar(name: String): Char? {
            if (name.isEmpty()) return null
            val first = name.firstOrNull { it.isLetterOrDigit() } ?: name.first()
            return first.uppercaseChar()
        }

        private fun getColorForName(name: String): Int {
            val upper = avatarPlaceholderChar(name) ?: return avatarColors[0]
            return avatarColors[upper.code % avatarColors.size]
        }

        fun getColorForId(id: Long): Int {
            if (id == 0L) return 0xFFBDBDBD.toInt()
            val safe = if (id == Long.MIN_VALUE) 0L else java.lang.Math.abs(id)
            return avatarColors[(safe % avatarColors.size).toInt()]
        }

        private fun normalizeName(name: String?): String {
            if (name.isNullOrBlank()) return ""
            return name.trim().removePrefix("@")
        }
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private var boundId: Long = 0L
    private var initial: String = ""
    private var bgColor: Int = avatarColors[0]
    private var photoBitmap: Bitmap? = null
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loadingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LOADING_PLACEHOLDER_COLOR }
    private val defaultUserBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF606065.toInt() }
    private val defaultUserBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8C8C90.toInt() }
    private val defaultUserHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val defaultUserBodyPath = Path()
    private val defaultUserBodyBand = Path()
    private val defaultUserBodyBounds = RectF()
    private var defaultUserBodyRadius = -1f

    private enum class Fallback { BLANK, DEFAULT_USER, INITIALS }

    private var fallback = Fallback.DEFAULT_USER
    private var loadingPlaceholder = false
    private val roundRectF = RectF()
    var cornerRadius: Float = Float.MAX_VALUE

    private var cachedShader: BitmapShader? = null
    private var cachedShaderBitmap: Bitmap? = null
    private val shaderMatrix = android.graphics.Matrix()
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

    fun setUserId(id: Long) {
        fallback = Fallback.DEFAULT_USER
        bindId(id)
    }

    fun setBlankFallback() {
        fallback = Fallback.BLANK
        invalidateSelf()
    }

    fun setInfo(id: Long, name: String?) {
        val normalized = normalizeName(name)
        fallback = Fallback.INITIALS
        bgColor = getColorForName(normalized)
        initial = avatarPlaceholderChar(normalized)?.toString().orEmpty()
        bindId(id)
    }

    private fun bindId(id: Long) {
        if (id == boundId && hasPhoto()) {
            invalidateSelf()
            return
        }
        boundId = id
        clearAnimatedDrawable()
        photoBitmap = null
        loadingPlaceholder = false
        cachedShader = null
        cachedShaderBitmap = null
        invalidateSelf()
    }

    fun setColor(color: Int) {
        bgColor = color
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
        } else if (loadingPlaceholder || fallback == Fallback.BLANK) {
            canvas.drawRoundRect(roundRectF, r, r, loadingPaint)
        } else if (fallback == Fallback.DEFAULT_USER) {
            canvas.drawRoundRect(roundRectF, r, r, defaultUserBgPaint)
            canvas.drawPath(defaultUserBodyPath(r), defaultUserBodyPaint)
            canvas.drawCircle(cx, bounds.top + bounds.height() * DEFAULT_USER_HEAD_CENTER_Y, size * DEFAULT_USER_HEAD_RADIUS, defaultUserHeadPaint)
        } else {
            bgPaint.color = bgColor
            canvas.drawRoundRect(roundRectF, r, r, bgPaint)
            textPaint.textSize = radius * 0.85f
            val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(initial, cx, textY, textPaint)
        }
    }

    private fun defaultUserBodyPath(r: Float): Path {
        if (r != defaultUserBodyRadius || roundRectF != defaultUserBodyBounds) {
            defaultUserBodyRadius = r
            defaultUserBodyBounds.set(roundRectF)
            defaultUserBodyPath.reset()
            defaultUserBodyPath.addRoundRect(roundRectF, r, r, Path.Direction.CW)
            defaultUserBodyBand.reset()
            defaultUserBodyBand.addRect(
                roundRectF.left,
                roundRectF.top + roundRectF.height() * DEFAULT_USER_BODY_TOP,
                roundRectF.right,
                roundRectF.bottom,
                Path.Direction.CW
            )
            defaultUserBodyPath.op(defaultUserBodyBand, Path.Op.INTERSECT)
        }
        return defaultUserBodyPath
    }

    override fun setAlpha(alpha: Int) {
        bgPaint.alpha = alpha
        textPaint.alpha = alpha
        photoPaint.alpha = alpha
        defaultUserBgPaint.alpha = alpha
        defaultUserBodyPaint.alpha = alpha
        defaultUserHeadPaint.alpha = alpha
    }

    override fun setColorFilter(filter: ColorFilter?) {
        bgPaint.colorFilter = filter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
