package com.mezon.mobile.home.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.util.getEmojiUrl
import java.lang.ref.WeakReference

private val EMOJI_SIZE = LayoutHelper.dp(20)
private val PLACEHOLDER_RADIUS = LayoutHelper.dp(4).toFloat()
private const val PLACEHOLDER_COLOR = 0x1A000000

class EmojiSpan(
    private val emojiId: String,
    viewRef: WeakReference<View>
) : ReplacementSpan() {

    private val viewRef = viewRef
    @Volatile
    private var bitmap: Bitmap? = null
    @Volatile
    private var loadStarted = false
    private var cancellable: MezonImageLoader.Cancellable? = null

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int = EMOJI_SIZE

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
        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            tmpRect.set(x, top.toFloat(), x + EMOJI_SIZE, bottom.toFloat())
            canvas.drawBitmap(bmp, null, tmpRect, null)
            return
        }

        if (loadStarted) {
            drawPlaceholder(canvas, x, top, bottom)
            return
        }

        val url = getEmojiUrl(emojiId) ?: return
        val view = viewRef.get() ?: return
        val loader = MezonImageLoader.getInstance(view.context)

        val cached = loader.getBitmapFromMemory(url, EMOJI_SIZE, EMOJI_SIZE)
        if (cached != null) {
            bitmap = cached
            tmpRect.set(x, top.toFloat(), x + EMOJI_SIZE, bottom.toFloat())
            canvas.drawBitmap(cached, null, tmpRect, null)
            return
        }

        drawPlaceholder(canvas, x, top, bottom)
        loadStarted = true
        cancellable = loader.load(
            url, EMOJI_SIZE, EMOJI_SIZE,
            onSuccess = { bmp ->
                bitmap = bmp
                viewRef.get()?.invalidate()
            }
        )
    }

    private fun drawPlaceholder(canvas: Canvas, x: Float, top: Int, bottom: Int) {
        placeholderPaint.color = PLACEHOLDER_COLOR
        placeholderRect.set(x, top.toFloat(), x + EMOJI_SIZE, bottom.toFloat())
        canvas.drawRoundRect(placeholderRect, PLACEHOLDER_RADIUS, PLACEHOLDER_RADIUS, placeholderPaint)
    }

    fun cancelLoad() {
        cancellable?.cancel()
        cancellable = null
    }

    companion object {
        private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val placeholderRect = RectF()
        private val tmpRect = RectF()
    }
}
