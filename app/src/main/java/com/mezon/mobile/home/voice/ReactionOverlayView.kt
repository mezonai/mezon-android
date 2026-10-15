package com.mezon.mobile.home.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.graphics.drawable.Drawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.util.getEmojiUrl
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

class ReactionOverlayView(context: Context) : View(context) {

    companion object {
        private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }
        private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = 0xFFFFFFFF.toInt()
            textSize = LayoutHelper.sp(12f).toFloat()
            setShadowLayer(LayoutHelper.dp(2f).toFloat(), 0f, LayoutHelper.dp(1f).toFloat(), 0xB3000000.toInt())
        }
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private const val ANIMATION_DURATION_MS = 4000L
        private val EMOJI_BITMAP_SIZE = LayoutHelper.dp(36)
        private val BASE_EMOJI_TEXT_SIZE = LayoutHelper.sp(32f)
        private val NAME_GAP = LayoutHelper.dp(6).toFloat()
        private val NAME_MAX_WIDTH = LayoutHelper.dp(120).toFloat()
        private val START_Y_OFFSET = LayoutHelper.dp(120).toFloat()
        private val MAX_HORIZONTAL_OFFSET = LayoutHelper.dp(150).toFloat()
        private val MAX_VERTICAL_OFFSET = LayoutHelper.dp(30).toFloat()
        private const val FLIGHT_HEIGHT_RATIO = 0.7f
        private const val FLIGHT_HEIGHT_VARIANCE = 0.2f
        private const val MAX_ACTIVE_EMOJIS = 10
        private const val MAX_PENDING_EMOJIS = 20
        private const val PHASE_ONE_END = 0.2f
        private const val PHASE_TWO_END = 0.45f
        private const val PHASE_THREE_END = 0.75f
        private const val FADE_IN_END = 0.05f
        private const val FADE_OUT_START = 0.75f
        private const val FADE_OUT_END = 0.95f
        private const val SCALE_SPRING_END = 0.075f
        private const val SCALE_GROW_END = 0.2f
        private const val SCALE_MAX = 1.3f
        private val VERTICAL_INTERPOLATOR = PathInterpolator(0.25f, 0.46f, 0.45f, 0.94f)
    }

    class FloatingEmoji(
        val emoji: String,
        val senderName: String?,
        val isServerEmojiId: Boolean,
        val startX: Float,
        val startY: Float,
        val horizontalOffset: Float,
        val flightDistance: Float,
        val spawnTime: Long,
        var x: Float,
        var currentY: Float,
        var alpha: Float,
        var scale: Float,
        var drawable: Drawable? = null,
        var displayName: String? = null
    )

    private data class PendingEmoji(val emoji: String, val senderName: String?)

    private val activeEmojis = ArrayList<FloatingEmoji>(MAX_ACTIVE_EMOJIS)
    private val pendingEmojis = ArrayList<PendingEmoji>()
    private val tmpDstRect = RectF()
    private val tmpMatrix = Matrix()
    private var ticker: ValueAnimator? = null

    fun showEmojis(emojis: List<String>) {
        showEmojis(emojis, null)
    }

    fun showEmojis(emojis: List<String>, senderName: String?) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) {
            val space = MAX_PENDING_EMOJIS - pendingEmojis.size
            if (space > 0) {
                val items = emojis.map { PendingEmoji(it, senderName) }
                pendingEmojis.addAll(if (items.size <= space) items else items.take(space))
            }
            post { launchPending() }
            return
        }
        launchEmojis(emojis, senderName, w, h)
    }

    private fun launchPending() {
        if (pendingEmojis.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val copy = ArrayList(pendingEmojis)
        pendingEmojis.clear()
        for (item in copy) {
            launchEmojis(listOf(item.emoji), item.senderName, w, h)
        }
    }

    private fun launchEmojis(emojis: List<String>, senderName: String?, w: Float, h: Float) {
        val now = SystemClock.elapsedRealtime()
        val trimmedName = senderName?.trim()?.takeIf { it.isNotEmpty() }
        for (emoji in emojis) {
            if (activeEmojis.size >= MAX_ACTIVE_EMOJIS) {
                activeEmojis.removeAt(0)
            }
            val horizontalOffset = ((Math.random() - 0.5) * MAX_HORIZONTAL_OFFSET).toFloat()
            val verticalOffset = (Math.random() * MAX_VERTICAL_OFFSET).toFloat()
            val startX = w / 2f + horizontalOffset * 0.2f
            val startY = (h - START_Y_OFFSET - verticalOffset).coerceAtLeast(EMOJI_BITMAP_SIZE.toFloat())
            val flightDistance = h * FLIGHT_HEIGHT_RATIO + (Math.random().toFloat() * h * FLIGHT_HEIGHT_VARIANCE)
            val isServerEmojiId = emoji.isNotEmpty() && emoji.all { it.isDigit() }
            val fe = FloatingEmoji(
                emoji = emoji,
                senderName = trimmedName,
                isServerEmojiId = isServerEmojiId,
                startX = startX,
                startY = startY,
                horizontalOffset = horizontalOffset,
                flightDistance = flightDistance,
                spawnTime = now,
                x = startX,
                currentY = startY,
                alpha = 0f,
                scale = 0f
            )
            if (trimmedName != null) {
                fe.displayName = TextUtils.ellipsize(trimmedName, namePaint, NAME_MAX_WIDTH, TextUtils.TruncateAt.END).toString()
            }
            loadEmojiDrawableIfNeeded(fe)
            activeEmojis.add(fe)
        }
        ensureTickerRunning()
    }

    private fun ensureTickerRunning() {
        if (ticker != null) return
        ticker = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Long.MAX_VALUE
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { onTick() }
            start()
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun onTick() {
        if (activeEmojis.isEmpty()) {
            stopTicker()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val iter = activeEmojis.iterator()
        while (iter.hasNext()) {
            val fe = iter.next()
            val elapsed = now - fe.spawnTime
            if (elapsed >= ANIMATION_DURATION_MS) {
                (fe.drawable as? android.graphics.drawable.Animatable)?.stop()
                fe.drawable?.callback = null
                iter.remove()
                continue
            }
            val progress = (elapsed.toFloat() / ANIMATION_DURATION_MS).coerceIn(0f, 1f)
            fe.x = fe.startX + progressHorizontalOffset(progress, fe.horizontalOffset)
            fe.currentY = fe.startY - fe.flightDistance * VERTICAL_INTERPOLATOR.getInterpolation(progress)
            fe.alpha = computeAlpha(progress)
            fe.scale = computeScale(progress)
        }
        if (activeEmojis.isEmpty()) {
            stopTicker()
        }
        invalidate()
    }

    private fun computeAlpha(progress: Float): Float = when {
        progress < FADE_IN_END -> progress / FADE_IN_END
        progress < FADE_OUT_START -> 1f
        progress < FADE_OUT_END -> 1f - (progress - FADE_OUT_START) / (FADE_OUT_END - FADE_OUT_START)
        else -> 0f
    }

    private fun computeScale(progress: Float): Float = when {
        progress < SCALE_SPRING_END -> progress / SCALE_SPRING_END
        progress < SCALE_GROW_END -> {
            val t = (progress - SCALE_SPRING_END) / (SCALE_GROW_END - SCALE_SPRING_END)
            1f + (SCALE_MAX - 1f) * t
        }
        else -> SCALE_MAX
    }

    private fun progressHorizontalOffset(progress: Float, horizontalOffset: Float): Float = when {
        progress < PHASE_ONE_END -> {
            horizontalOffset * 0.3f * easeOutCircle(progress / PHASE_ONE_END)
        }
        progress < PHASE_TWO_END -> {
            val t = (progress - PHASE_ONE_END) / (PHASE_TWO_END - PHASE_ONE_END)
            val start = horizontalOffset * 0.3f
            start + (horizontalOffset * 0.7f - start) * easeInOutSine(t)
        }
        progress < PHASE_THREE_END -> {
            val t = (progress - PHASE_TWO_END) / (PHASE_THREE_END - PHASE_TWO_END)
            val start = horizontalOffset * 0.7f
            start + (horizontalOffset - start) * easeInCircle(t)
        }
        else -> horizontalOffset
    }

    private fun easeOutCircle(t: Float): Float = sqrt(1f - (t.coerceIn(0f, 1f) - 1f).pow(2))
    private fun easeInCircle(t: Float): Float = 1f - sqrt(1f - t.coerceIn(0f, 1f).pow(2))
    private fun easeInOutSine(t: Float): Float = (-(cos(Math.PI.toFloat() * t.coerceIn(0f, 1f)) - 1f) / 2f)

    override fun onDraw(canvas: Canvas) {
        for (i in 0 until activeEmojis.size) {
            val fe = activeEmojis[i]
            if (fe.alpha <= 0f) continue
            val alphaInt = (fe.alpha * 255f).toInt().coerceIn(0, 255)
            val drawable = fe.drawable
            val scaledHalf = EMOJI_BITMAP_SIZE * fe.scale * 0.5f
            var emojiDrawn = false
            if (drawable != null) {
                drawable.alpha = alphaInt
                drawable.setBounds(
                    (fe.x - scaledHalf).toInt(),
                    (fe.currentY - scaledHalf).toInt(),
                    (fe.x + scaledHalf).toInt(),
                    (fe.currentY + scaledHalf).toInt()
                )
                drawable.draw(canvas)
                emojiDrawn = true
            } else if (!fe.isServerEmojiId) {
                emojiPaint.alpha = alphaInt
                emojiPaint.textSize = BASE_EMOJI_TEXT_SIZE * fe.scale
                canvas.drawText(fe.emoji, fe.x, fe.currentY, emojiPaint)
                emojiDrawn = true
            }
            val name = fe.displayName
            if (emojiDrawn && !name.isNullOrEmpty()) {
                namePaint.alpha = alphaInt
                val nameY = fe.currentY + scaledHalf + NAME_GAP - namePaint.fontMetrics.ascent
                canvas.drawText(name, fe.x, nameY, namePaint)
            }
        }
    }

    fun cancelAll() {
        stopTicker()
        for (fe in activeEmojis) {
            (fe.drawable as? android.graphics.drawable.Animatable)?.stop()
            fe.drawable?.callback = null
        }
        activeEmojis.clear()
        pendingEmojis.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAll()
    }

    private val animationCallback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            invalidate()
        }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            postDelayed(what, `when` - SystemClock.uptimeMillis())
        }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            removeCallbacks(what)
        }
    }

    private fun loadEmojiDrawableIfNeeded(fe: FloatingEmoji) {
        if (!fe.isServerEmojiId) return
        val url = getEmojiUrl(fe.emoji) ?: return
        val loader = MezonImageLoader.getInstance(context)

        fun load(loadUrl: String, isRetry: Boolean) {
            loader.loadDrawable(
                loadUrl,
                EMOJI_BITMAP_SIZE,
                EMOJI_BITMAP_SIZE,
                onSuccess = { drawable ->
                    if (!activeEmojis.contains(fe)) return@loadDrawable
                    if (drawable is android.graphics.drawable.Animatable) {
                        drawable.callback = animationCallback
                        drawable.start()
                    }
                    fe.drawable = drawable
                    invalidate()
                },
                onError = {
                    if (!isRetry) {
                        val direct = com.mezon.mobile.util.getEmojiDirectUrl(fe.emoji)
                        if (direct != null && direct != loadUrl) {
                            load(direct, true)
                        }
                    }
                },
                cacheAnimated = false
            )
        }

        load(url, false)
    }
}
