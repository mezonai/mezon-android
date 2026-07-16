package com.mezon.mobile.home.call

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class CallDurationView(
    context: Context,
    private val themeColors: ThemeColors
) : View(context) {

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }

    private val handler = Handler(Looper.getMainLooper())
    private var startTime = 0L
    private var running = false
    private var durationText = "00:00"

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            updateDuration()
            handler.postDelayed(this, 1000)
        }
    }

    fun setTextSizeSp(sizeSp: Float) {
        textPaint.textSize =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
        invalidate()
    }

    fun startTimer(connectedTime: Long) {
        handler.removeCallbacks(tickRunnable)
        startTime = connectedTime
        running = true
        handler.post(tickRunnable)
    }

    fun stopTimer() {
        running = false
        handler.removeCallbacks(tickRunnable)
    }

    private fun updateDuration() {
        val elapsed = SystemClock.elapsedRealtime() - startTime
        val totalSeconds = (elapsed / 1000).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        durationText = "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            LayoutHelper.dp(28)
        )
    }

    override fun onDraw(canvas: Canvas) {
        textPaint.color = themeColors.colorText
        canvas.drawText(durationText, width / 2f, height / 2f + textPaint.textSize / 3f, textPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopTimer()
    }
}
