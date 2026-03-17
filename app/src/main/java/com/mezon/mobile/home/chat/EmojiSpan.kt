package com.mezon.mobile.home.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.style.ReplacementSpan
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.util.getEmojiUrl
import java.lang.ref.WeakReference

private val EMOJI_SIZE = LayoutHelper.dp(20)
private val EMOJI_REQ = EMOJI_SIZE * 2

class EmojiSpan(
    private val emojiId: String,
    viewRef: WeakReference<View>
) : ReplacementSpan() {

    private val viewRef = viewRef
    @Volatile
    private var drawable: Drawable? = null
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
        val d = drawable
        if (d != null) {
            d.setBounds(x.toInt(), top, (x + EMOJI_SIZE).toInt(), bottom)
            d.draw(canvas)
            return
        }

        if (loadStarted) return

        val url = getEmojiUrl(emojiId) ?: return
        val view = viewRef.get() ?: return
        val loader = MezonImageLoader.getInstance(view.context)

        val cached = loader.getBitmapFromMemory(url, EMOJI_REQ, EMOJI_REQ)
        if (cached != null) {
            drawable = BitmapDrawable(view.resources, cached)
            drawable!!.setBounds(x.toInt(), top, (x + EMOJI_SIZE).toInt(), bottom)
            drawable!!.draw(canvas)
            return
        }

        loadStarted = true
        cancellable = loader.load(
            url, EMOJI_REQ, EMOJI_REQ,
            onSuccess = { bmp ->
                val v = viewRef.get() ?: return@load
                drawable = BitmapDrawable(v.resources, bmp)
                v.post { v.invalidate() }
            }
        )
    }

    fun cancelLoad() {
        cancellable?.cancel()
        cancellable = null
    }
}
