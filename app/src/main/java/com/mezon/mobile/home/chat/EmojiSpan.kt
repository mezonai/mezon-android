package com.mezon.mobile.home.chat

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.style.ReplacementSpan
import android.view.View
import coil.Coil
import coil.request.ImageRequest
import coil.size.Size
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.util.getEmojiUrl
import java.lang.ref.WeakReference

private val EMOJI_SIZE = LayoutHelper.dp(20)

class EmojiSpan(
    private val emojiId: String,
    viewRef: WeakReference<View>
) : ReplacementSpan() {

    private val viewRef = viewRef
    @Volatile
    private var drawable: Drawable? = null
    @Volatile
    private var loadStarted = false

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
        } else {
            if (!loadStarted) {
                loadStarted = true
                val url = getEmojiUrl(emojiId) ?: return
                val view = viewRef.get() ?: return
                val request = ImageRequest.Builder(view.context)
                    .data(url)
                    .size(Size(EMOJI_SIZE * 2, EMOJI_SIZE * 2))
                    .allowHardware(false)
                    .target(
                        onSuccess = { result ->
                            val v = viewRef.get() ?: return@target
                            drawable = result
                            v.post { v.invalidate() }
                        }
                    )
                    .build()
                Coil.imageLoader(view.context).enqueue(request)
            }
        }
    }
}
