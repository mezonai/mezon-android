package com.mezon.mobile.ui.cells

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class ToastOverlay(context: Context, private val theme: ThemeColors) : FrameLayout(context) {

    enum class ToastType { SUCCESS, ERROR, INFO, TOOLTIP }

    private val handler = Handler(Looper.getMainLooper())

    init {
        isClickable = false
        isFocusable = false
    }

    fun show(
        parent: ViewGroup,
        type: ToastType,
        title: String,
        description: String? = null,
        durationMs: Long = 3000L,
        onTap: (() -> Unit)? = null
    ) {
        val toastView = ToastView(context, theme, type, title, description)
        val statusBarHeight = AndroidUtilities.statusBarHeight
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = statusBarHeight + LayoutHelper.dp(8)
            leftMargin = LayoutHelper.dp(16)
            rightMargin = LayoutHelper.dp(16)
        }

        removeAllViews()
        addView(toastView, lp)

        if (this.parent == null) {
            val overlayLp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            parent.addView(this, overlayLp)
        }

        if (onTap != null) {
            toastView.isClickable = true
            toastView.setOnClickListener {
                handler.removeCallbacksAndMessages(null)
                dismiss()
                onTap()
            }
        }

        var startY = 0f
        toastView.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> startY = ev.y
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (startY - ev.y > AndroidUtilities.touchSlop) {
                        handler.removeCallbacksAndMessages(null)
                        dismiss()
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }

        toastView.translationY = -LayoutHelper.dpf(80f)
        toastView.alpha = 0f
        toastView.animate().translationY(0f).alpha(1f).setDuration(250).start()

        handler.postDelayed({ dismiss() }, durationMs)
    }

    private fun dismiss() {
        val view = if (childCount > 0) getChildAt(0) else null
        view?.animate()?.translationY(-LayoutHelper.dpf(80f))?.alpha(0f)?.setDuration(250)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    removeAllViews()
                    (this@ToastOverlay.parent as? ViewGroup)?.removeView(this@ToastOverlay)
                }
            })?.start()
    }

    private class ToastView(
        context: Context,
        private val theme: ThemeColors,
        private val type: ToastType,
        private val title: String,
        description: String?
    ) : View(context) {

        private val description: String? = description?.replace('\n', ' ')?.replace('\r', ' ')
        private var lastBuildWidth = 0

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(14f)
            color = android.graphics.Color.BLACK
            isFakeBoldText = true
        }
        private val descPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(13f)
            color = 0xB3000000.toInt()
        }
        private val rect = RectF()
        private var titleLayout: StaticLayout? = null
        private var descLayout: StaticLayout? = null
        private val radius = LayoutHelper.dpf(16f)
        private val padH = LayoutHelper.dp(16)
        private val padV = LayoutHelper.dp(12)

        private val bgColor: Int get() = when (type) {
            ToastType.SUCCESS -> 0xFFB6E1C6.toInt()
            ToastType.ERROR -> 0xFFEFC3CA.toInt()
            ToastType.INFO -> 0xFFB6D4E1.toInt()
            ToastType.TOOLTIP -> theme.surfaceVariant
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val textWidth = w - padH * 2

            if (textWidth > 0 && textWidth != lastBuildWidth) {
                lastBuildWidth = textWidth
                titleLayout = StaticLayout.Builder
                    .obtain(title, 0, title.length, titlePaint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(1)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()

                descLayout = description?.let { d ->
                    StaticLayout.Builder
                        .obtain(d, 0, d.length, descPaint, textWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setMaxLines(2)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .build()
                }
            }

            var h = padV
            titleLayout?.let { h += it.height }
            descLayout?.let { h += it.height + LayoutHelper.dp(4) }
            h += padV

            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            bgPaint.color = bgColor
            canvas.drawRoundRect(rect, radius, radius, bgPaint)

            var top = padV.toFloat()
            titleLayout?.let {
                canvas.save()
                canvas.translate(padH.toFloat(), top)
                it.draw(canvas)
                canvas.restore()
                top += it.height
            }
            descLayout?.let {
                top += LayoutHelper.dp(4)
                canvas.save()
                canvas.translate(padH.toFloat(), top)
                it.draw(canvas)
                canvas.restore()
            }
        }
    }
}
