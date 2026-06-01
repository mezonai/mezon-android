package com.mezon.mobile.home.sharing

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.home.chat.ImageReceiver

internal class ForwardPreviewThumbHost(context: Context) : View(context) {
    private val receiver = ImageReceiver(this)

    init {
        receiver.setRoundRadius(LayoutHelper.dp(4f))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        receiver.setImageCoords(0f, 0f, w.toFloat(), h.toFloat())
        receiver.setRequestedSize(w, h)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        receiver.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        receiver.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        receiver.draw(canvas)
    }

    fun bind(url: String?, thumb: String?) {
        receiver.setImage(url, thumb, context)
    }
}
