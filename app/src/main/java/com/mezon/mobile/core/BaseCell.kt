package com.mezon.mobile.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.RecordingCanvas
import android.graphics.RenderNode
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup

abstract class BaseCell(context: Context) : ViewGroup(context) {

    private var checkingForLongPress = false
    private var pendingCheckForLongPress: CheckForLongPress? = null
    private var pressCount = 0
    private var pendingCheckForTap: CheckForTap? = null

    protected var invalidateCallback: Runnable? = null

    private var renderNode: RenderNode? = null
    private var forceNotCacheNextFrame = false
    protected var updatedContent = false

    init {
        setWillNotDraw(false)
        isFocusable = true
        isHapticFeedbackEnabled = true
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {}

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
    }

    protected fun startCheckLongPress() {
        if (checkingForLongPress) return
        checkingForLongPress = true
        val tap = pendingCheckForTap ?: CheckForTap().also { pendingCheckForTap = it }
        postDelayed(tap, ViewConfiguration.getTapTimeout().toLong())
    }

    protected fun cancelCheckLongPress() {
        checkingForLongPress = false
        pendingCheckForLongPress?.let { removeCallbacks(it) }
        pendingCheckForTap?.let { removeCallbacks(it) }
    }

    override fun hasOverlappingRendering(): Boolean = false

    protected open fun onLongPress(): Boolean = true

    open fun getBoundsLeft(): Int = 0

    open fun getBoundsRight(): Int = width

    fun listenInvalidate(callback: Runnable?) {
        invalidateCallback = callback
    }

    fun invalidateLite() {
        super.invalidate()
    }

    override fun invalidate() {
        invalidateCallback?.run()
        super.invalidate()
    }

    fun forceNotCacheNextFrame() {
        forceNotCacheNextFrame = true
    }

    fun drawCached(canvas: Canvas) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && renderNode != null && renderNode!!.hasDisplayList() && canvas.isHardwareAccelerated && !updatedContent) {
            canvas.drawRenderNode(renderNode!!)
        } else {
            draw(canvas)
        }
    }

    override fun draw(canvas: Canvas) {
        val cache = allowCaching()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cache != (renderNode != null)) {
            if (cache) {
                renderNode = RenderNode("basecell").apply { clipToBounds = false }
                updatedContent = true
            } else {
                renderNode = null
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && renderNode != null && !forceNotCacheNextFrame && canvas.isHardwareAccelerated) {
                val rn = renderNode!!
                rn.setPosition(0, 0, width, height)
                val rc: RecordingCanvas = rn.beginRecording()
                super.draw(rc)
                rn.endRecording()
                canvas.drawRenderNode(rn)
            } else {
                super.draw(canvas)
            }
        } catch (_: IllegalStateException) {
        }
        forceNotCacheNextFrame = false
        updatedContent = false
    }

    protected open fun allowCaching(): Boolean = true

    private inner class CheckForTap : Runnable {
        override fun run() {
            val lp = pendingCheckForLongPress ?: CheckForLongPress().also { pendingCheckForLongPress = it }
            lp.currentPressCount = ++pressCount
            postDelayed(lp, ViewConfiguration.getLongPressTimeout().toLong() - ViewConfiguration.getTapTimeout().toLong())
        }
    }

    private inner class CheckForLongPress : Runnable {
        var currentPressCount = 0
        override fun run() {
            if (checkingForLongPress && parent != null && currentPressCount == pressCount) {
                checkingForLongPress = false
                if (onLongPress()) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                    onTouchEvent(event)
                    event.recycle()
                }
            }
        }
    }

    companion object {
        @JvmStatic
        fun setDrawableBounds(drawable: Drawable?, x: Int, y: Int) {
            drawable ?: return
            drawable.setBounds(x, y, x + drawable.intrinsicWidth, y + drawable.intrinsicHeight)
        }

        @JvmStatic
        fun setDrawableBounds(drawable: Drawable?, x: Float, y: Float) {
            drawable ?: return
            setDrawableBounds(drawable, x.toInt(), y.toInt())
        }

        @JvmStatic
        fun setDrawableBounds(drawable: Drawable?, x: Int, y: Int, w: Int, h: Int) {
            drawable?.setBounds(x, y, x + w, y + h)
        }

        @JvmStatic
        fun setDrawableBounds(drawable: Drawable?, x: Float, y: Float, w: Int, h: Int) {
            drawable?.setBounds(x.toInt(), y.toInt(), x.toInt() + w, y.toInt() + h)
        }
    }
}
