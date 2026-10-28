package com.mezon.mobile.home.stream

import android.content.Context
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import com.otaliastudios.zoom.Alignment
import com.otaliastudios.zoom.ZoomApi
import com.otaliastudios.zoom.ZoomLayout

/** Zoom only the picture; controls remain outside this view. */
class StreamingVideoViewport(context: Context) : ZoomLayout(context) {
    var onContentTap: (() -> Unit)? = null
    private var trackingSingleTouch = false
    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapConfirmed(event: MotionEvent) = performClick()
        override fun onDoubleTap(event: MotionEvent): Boolean {
            moveToCenter(if (zoom > 1.05f) 1f else 2f, true)
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    init {
        isClickable = true
        isFocusable = true
        setHasClickableChildren(true)
        setTransformation(ZoomApi.TRANSFORMATION_CENTER_INSIDE, ZoomApi.TRANSFORMATION_GRAVITY_AUTO)
        setAlignment(Alignment.CENTER)
        setMinZoom(1f, ZoomApi.TYPE_ZOOM)
        setMaxZoom(4f, ZoomApi.TYPE_ZOOM)
        setOverPinchable(false)
        setHorizontalPanEnabled(true)
        setVerticalPanEnabled(true)
        setFlingEnabled(true)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onContentTap?.invoke()
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) trackingSingleTouch = true
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            cancelContentTap()
        }
        val tapHandled = trackingSingleTouch && tapDetector.onTouchEvent(event)
        val zoomHandled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            trackingSingleTouch = false
        }
        return zoomHandled || tapHandled
    }

    private fun cancelContentTap() {
        trackingSingleTouch = false
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        tapDetector.onTouchEvent(cancel)
        cancel.recycle()
    }

    fun resetZoom() {
        cancelAnimations()
        moveToCenter(1f, false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // ZoomLayout measures its child without bounds. MATCH_PARENT alone therefore
        // collapses a picture containing only MATCH_PARENT video views to zero size.
        getChildAt(0)?.let { picture ->
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            val params = picture.layoutParams
            if (width > 0 && height > 0 && (params.width != width || params.height != height)) {
                params.width = width
                params.height = height
                picture.layoutParams = params
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDetachedFromWindow() {
        cancelContentTap()
        cancelAnimations()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) post { resetZoom() }
    }
}
