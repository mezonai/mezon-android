package com.mezon.mobile.ui.cells

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.mezon.mobile.MainActivity
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

    companion object {
        @Volatile
        private var visibleInstance: ToastOverlay? = null

        @JvmStatic
        fun hideVisible() {
            visibleInstance?.let { o ->
                o.handler.removeCallbacksAndMessages(null)
                if (o.childCount > 0) {
                    o.getChildAt(0).animate().cancel()
                }
                o.removeAllViews()
                (o.parent as? ViewGroup)?.removeView(o)
            }
            visibleInstance = null
        }

        @JvmStatic
        fun showInAppNotification(
            activity: MainActivity,
            parent: ViewGroup = activity.drawerLayoutContainer,
            title: String,
            body: String,
            durationMs: Long = 3000L,
            onTap: (() -> Unit)? = null
        ) {
            val current = visibleInstance
            if (current != null && current.parent != null && current.childCount == 1) {
                val inApp = current.getChildAt(0) as? InAppNotificationToastView
                if (inApp != null) {
                    inApp.updateContent(title, body)
                    current.rebindInAppFromNextNotification(parent, durationMs, onTap)
                    return
                }
            }
            hideVisible()
            ToastOverlay(activity, activity.themeColors)
                .attachInAppFirstShow(parent, title, body, durationMs, onTap)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    init {
        isClickable = false
        isFocusable = false
        clipChildren = false
        setWillNotDraw(true)
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
        presentContent(parent, toastView, durationMs, onTap)
    }

    private fun attachInAppFirstShow(
        parent: ViewGroup,
        title: String,
        body: String,
        durationMs: Long,
        onTap: (() -> Unit)?
    ) {
        val v = InAppNotificationToastView(context, theme)
        v.updateContent(title, body)
        presentContent(
            parent = parent,
            toastView = v,
            durationMs = durationMs,
            onTap = onTap,
            evictVisibleFirst = false
        )
    }

    private fun rebindInAppFromNextNotification(
        parent: ViewGroup,
        durationMs: Long,
        onTap: (() -> Unit)?
    ) {
        if (this.parent == null) {
            addSelfToParentWithOverlayLp(parent)
        } else if (this.parent !== parent) {
            (this.parent as? ViewGroup)?.removeView(this)
            addSelfToParentWithOverlayLp(parent)
        }
        parent.bringChildToFront(this)
        visibleInstance = this
        if (childCount == 0) return
        val toastView = getChildAt(0)
        bindInAppNotificationInteraction(toastView, onTap)
        scheduleInAppDismiss(durationMs)
    }

    private fun addSelfToParentWithOverlayLp(parent: ViewGroup) {
        val overlayLp = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        )
        parent.addView(this, overlayLp)
    }

    private fun scheduleInAppDismiss(durationMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ dismiss() }, durationMs)
    }

    private fun presentContent(
        parent: ViewGroup,
        toastView: View,
        durationMs: Long,
        onTap: (() -> Unit)?,
        evictVisibleFirst: Boolean = true
    ) {
        if (evictVisibleFirst) {
            ToastOverlay.hideVisible()
        }
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
            addSelfToParentWithOverlayLp(parent)
        }
        visibleInstance = this
        bindInAppNotificationInteraction(toastView, onTap)
        if (toastView is InAppNotificationToastView) {
            scheduleInAppDismiss(durationMs)
        }
        toastView.translationY = -LayoutHelper.dpf(80f)
        toastView.alpha = 0f
        toastView.animate().translationY(0f).alpha(1f).setDuration(250).start()
        if (toastView !is InAppNotificationToastView) {
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ dismiss() }, durationMs)
        }
    }

    private fun bindInAppNotificationInteraction(toastView: View, onTap: (() -> Unit)?) {
        if (onTap != null) {
            toastView.isClickable = true
            toastView.setOnClickListener {
                handler.removeCallbacksAndMessages(null)
                dismiss()
                onTap()
            }
        } else {
            toastView.setOnClickListener(null)
            toastView.isClickable = true
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
    }

    private fun dismiss() {
        val view = if (childCount > 0) getChildAt(0) else null
        view?.animate()?.translationY(-LayoutHelper.dpf(80f))?.alpha(0f)?.setDuration(250)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (visibleInstance === this@ToastOverlay) {
                        visibleInstance = null
                    }
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
        private var lastTextWidth = -1

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val iconBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
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
        private val cornerRadius = LayoutHelper.dpf(20f)
        private val minHeight = LayoutHelper.dp(60)
        private val padLeading = LayoutHelper.dp(20)
        private val padEnd = LayoutHelper.dp(20)
        private val iconColumn = LayoutHelper.dp(32)
        private val iconSize = LayoutHelper.dp(20)
        private val iconTextGap = LayoutHelper.dp(12)
        private val rowPadV = LayoutHelper.dp(12)

        private val leadingDrawable: Drawable = when (type) {
            ToastType.ERROR -> MezonIcon.circleExlaimionIcon.getDrawable(
                context,
                theme.error
            )
            else -> MezonIcon.checkmarkLargeIcon.getDrawable(
                context,
                theme.success
            )
        }

        private val bgColor: Int
            get() = when (type) {
                ToastType.SUCCESS -> 0xFFB6E1C6.toInt()
                ToastType.ERROR -> 0xFFEFC3CA.toInt()
                ToastType.INFO -> 0xFFB6E1C6.toInt()
                ToastType.TOOLTIP -> theme.secondaryLight
            }

        private val bubbleTints: Int
            get() = when (type) {
                ToastType.ERROR -> 0xFFE69CA0.toInt()
                else -> 0xFF90DDB1.toInt()
            }

        init {
            if (type == ToastType.TOOLTIP) {
                titlePaint.color = theme.textStrong
            }
        }

        private fun textBlockWidth(w: Int): Int {
            if (type == ToastType.TOOLTIP) {
                return (w - padLeading * 2).coerceAtLeast(0)
            }
            return (w - padLeading - iconColumn - iconTextGap - padEnd).coerceAtLeast(0)
        }

        private fun rebuildLayouts(textWidth: Int) {
            if (textWidth <= 0) {
                titleLayout = null
                descLayout = null
                return
            }
            if (type == ToastType.TOOLTIP) {
                val line = if (title.isNotEmpty()) title else description.orEmpty()
                titleLayout = StaticLayout.Builder
                    .obtain(line, 0, line.length, titlePaint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()
                descLayout = null
                return
            }
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

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val textW = textBlockWidth(w)
            if (textW != lastTextWidth) {
                lastTextWidth = textW
                rebuildLayouts(textW)
            }
            var textH = 0
            titleLayout?.let { textH += it.height }
            descLayout?.let { textH += it.height + LayoutHelper.dp(4) }
            var h = if (type == ToastType.TOOLTIP) {
                rowPadV * 2 + textH
            } else {
                maxOf(minHeight, rowPadV * 2 + textH, iconColumn)
            }
            h = h.coerceAtLeast(minHeight)
            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            rect.set(0f, 0f, w, h)
            bgPaint.color = bgColor
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
            if (type != ToastType.TOOLTIP) {
                bubblePaint.color = bubbleTints
                val r1 = LayoutHelper.dpf(30f)
                canvas.drawCircle(r1, r1, r1, bubblePaint)
                val r2 = LayoutHelper.dpf(15f)
                canvas.drawCircle(padLeading + r2, h - r2, r2, bubblePaint)
                val r3 = LayoutHelper.dpf(5f)
                canvas.drawCircle(w - LayoutHelper.dpf(40f), h - r3, r3, bubblePaint)
            }

            if (type == ToastType.TOOLTIP) {
                val top = (h - (titleLayout?.height ?: 0)) / 2f
                titleLayout?.let { layout ->
                    canvas.save()
                    canvas.translate(padLeading.toFloat(), top)
                    layout.draw(canvas)
                    canvas.restore()
                }
                return
            }

            val iconTop = (h - iconColumn) / 2f
            val iconCenterX = padLeading + iconColumn / 2f
            val iconCenterY = iconTop + iconColumn / 2f
            val iconR = iconColumn / 2f
            canvas.drawCircle(iconCenterX, iconCenterY, iconR, iconBgPaint)
            MezonIcon.drawIcon(
                canvas,
                leadingDrawable,
                iconCenterX.toInt(),
                iconCenterY.toInt(),
                iconSize
            )

            var textY = (h - textContentHeight()) / 2f
            titleLayout?.let { layout ->
                canvas.save()
                canvas.translate(
                    (padLeading + iconColumn + iconTextGap).toFloat(),
                    textY
                )
                layout.draw(canvas)
                canvas.restore()
                textY += layout.height
            }
            descLayout?.let { layout ->
                textY += LayoutHelper.dp(4)
                canvas.save()
                canvas.translate(
                    (padLeading + iconColumn + iconTextGap).toFloat(),
                    textY
                )
                layout.draw(canvas)
                canvas.restore()
            }
        }

        private fun textContentHeight(): Float {
            var t = 0f
            titleLayout?.let { t += it.height }
            descLayout?.let { t += it.height + LayoutHelper.dp(4) }
            return t
        }
    }
}
