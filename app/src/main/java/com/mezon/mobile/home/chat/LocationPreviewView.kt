package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.util.LocationMessageData

class LocationPreviewView(
    context: Context,
    private val senderId: Long,
    private val senderAvatarUrl: String,
    private val title: String
) : View(context) {

    private val cardLayout = LocationCardLayout(context).also {
        it.invalidateCallback = { invalidate() }
    }
    private var location: LocationMessageData? = null
    private var preparedLocation: LocationMessageData? = null
    private var preparedWidth = 0

    fun setLocation(latitude: Double, longitude: Double) {
        location = LocationMessageData(latitude, longitude, "")
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val data = location
        if (data == null || width <= 0) {
            setMeasuredDimension(width, 0)
            return
        }
        if (data !== preparedLocation || width != preparedWidth) {
            cardLayout.prepare(data, senderId, senderAvatarUrl, title, ThemeColors.instance, width)
            preparedLocation = data
            preparedWidth = width
        }
        setMeasuredDimension(width, cardLayout.blockHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (preparedLocation != null) cardLayout.draw(canvas, 0f, 0f)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cardLayout.clear()
        preparedLocation = null
        preparedWidth = 0
    }
}
