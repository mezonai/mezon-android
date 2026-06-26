package com.mezon.mobile.home.chat

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.text.style.ReplacementSpan
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.util.getEmojiDirectUrl
import com.mezon.mobile.util.getEmojiUrl
import java.lang.ref.WeakReference

private val PLACEHOLDER_RADIUS = LayoutHelper.dp(4).toFloat()
private const val PLACEHOLDER_COLOR = 0x1A000000

class EmojiSpan(
    private val emojiId: String,
    viewRef: WeakReference<View>,
    private val size: Int = LayoutHelper.dp(20)
) : ReplacementSpan() {

    private val viewRef = viewRef
    @Volatile
    private var drawable: Drawable? = null
    @Volatile
    private var loadStarted = false
    private var cancellable: MezonImageLoader.Cancellable? = null

    private val drawableCallback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            viewRef.get()?.invalidate()
        }
        override fun scheduleDrawable(who: Drawable, what: Runnable, w: Long) {
            val delay = w - android.os.SystemClock.uptimeMillis()
            viewRef.get()?.postDelayed(what, maxOf(0L, delay))
        }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            viewRef.get()?.removeCallbacks(what)
        }
    }

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        if (fm != null) {
            val h = paint.fontMetricsInt.descent - paint.fontMetricsInt.ascent
            if (h < size) {
                val diff = (size - h) / 2
                fm.ascent = paint.fontMetricsInt.ascent - diff
                fm.descent = paint.fontMetricsInt.descent + diff
                fm.top = fm.ascent
                fm.bottom = fm.descent
            }
        }
        return size
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
        val h = bottom - top
        val cy = top + h / 2f
        val topF = cy - size / 2f
        val bottomF = cy + size / 2f

        val d = drawable
        if (d != null) {
            if (d.callback != drawableCallback) {
                d.callback = drawableCallback
                if (d is AnimatedImageDrawable) {
                    d.start()
                }
            }
            val iw = d.intrinsicWidth.toFloat()
            val ih = d.intrinsicHeight.toFloat()
            var dw = size
            var dh = size
            if (iw > 0 && ih > 0) {
                val ratio = iw / ih
                dw = if (ratio > 1f) size else (size * ratio).toInt()
                dh = if (ratio < 1f) size else (size / ratio).toInt()
            }
            val dx = x + (size - dw) / 2f
            val dy = topF + (size - dh) / 2f

            canvas.save()
            canvas.translate(dx, dy)
            d.setBounds(0, 0, dw, dh)
            d.draw(canvas)
            canvas.restore()
            return
        }

        if (loadStarted) {
            drawPlaceholder(canvas, x, topF.toInt(), bottomF.toInt())
            return
        }

        val url = getEmojiUrl(emojiId) ?: return
        val view = viewRef.get() ?: return
        val loader = MezonImageLoader.getInstance(view.context)

        drawPlaceholder(canvas, x, topF.toInt(), bottomF.toInt())
        loadStarted = true
        
        fun applyDrawable(loaded: Drawable) {
            drawable = loaded
            loaded.callback = drawableCallback
            if (loaded is AnimatedImageDrawable) {
                loaded.start()
            }
            viewRef.get()?.invalidate()
        }

        cancellable = loader.loadDrawable(
            url, size, size,
            onSuccess = { loaded ->
                applyDrawable(loaded)
            },
            onError = outerErr@{
                val direct = getEmojiDirectUrl(emojiId) ?: run {
                    viewRef.get()?.invalidate()
                    return@outerErr
                }
                if (direct == url) {
                    viewRef.get()?.invalidate()
                    return@outerErr
                }
                cancellable = loader.loadDrawable(
                    direct, size, size,
                    onSuccess = { d2 ->
                        applyDrawable(d2)
                    },
                    onError = { viewRef.get()?.invalidate() }
                )
            }
        )
    }

    private fun drawPlaceholder(canvas: Canvas, x: Float, top: Int, bottom: Int) {
        placeholderPaint.color = PLACEHOLDER_COLOR
        tmpRect.set(x, top.toFloat(), x + size, bottom.toFloat())
        canvas.drawRoundRect(tmpRect, PLACEHOLDER_RADIUS, PLACEHOLDER_RADIUS, placeholderPaint)
    }

    fun cancelLoad() {
        cancellable?.cancel()
        cancellable = null
        loadStarted = false
        val d = drawable
        if (d is AnimatedImageDrawable) {
            d.stop()
        }
        d?.callback = null
    }

    companion object {
        private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tmpRect = RectF()
    }
}
