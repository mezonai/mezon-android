package com.mezon.mobile.home.call

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.AvatarView

class OngoingCallBanner(context: Context) : FrameLayout(context) {

    var onReturnToCall: (() -> Unit)? = null

    private val tc = ThemeColors.instance
    private val avatarView: AvatarView
    private val nameTv: TextView
    private val durationView: CallDurationView

    private var boundConnectedTime = -1L
    private var boundPeerId = -1L

    init {
        val corner = LayoutHelper.dp(14).toFloat()
        background = GradientDrawable().apply {
            setColor(tc.charcoal)
            setStroke(
                LayoutHelper.dp(1),
                Color.argb(0x8C, Color.red(tc.connectedColor), Color.green(tc.connectedColor), Color.blue(tc.connectedColor))
            )
            cornerRadius = corner
        }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
        }
        elevation = LayoutHelper.dpf(16f)

        isClickable = true
        isFocusable = true
        setOnClickListener { onReturnToCall?.invoke() }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                LayoutHelper.dp(10),
                LayoutHelper.dp(9),
                LayoutHelper.dp(14),
                LayoutHelper.dp(9)
            )
        }

        val avatarPx = LayoutHelper.dp(34)
        val ringPad = LayoutHelper.dp(3)
        avatarView = AvatarView(context).apply {
            setSizeDp(34)
            setRoundRadius(17f)
        }
        val avatarRing = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(LayoutHelper.dp(2), tc.connectedColor)
            }
            setPadding(ringPad, ringPad, ringPad, ringPad)
            addView(avatarView, FrameLayout.LayoutParams(avatarPx, avatarPx))
        }
        row.addView(
            avatarRing,
            LinearLayout.LayoutParams(avatarPx + ringPad * 2, avatarPx + ringPad * 2)
        )

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(11), 0, LayoutHelper.dp(10), 0)
        }

        val labelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelRow.addView(
            LiveDot(context, tc.connectedColor),
            LinearLayout.LayoutParams(LayoutHelper.dp(8), LayoutHelper.dp(8))
        )
        val titleTv = TextView(context).apply {
            text = context.getString(R.string.call_ongoing_banner_title)
            setTextColor(tc.connectedColor)
            textSize = 11f
            letterSpacing = 0.03f
            setTypeface(typeface, Typeface.BOLD)
        }
        labelRow.addView(
            titleTv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = LayoutHelper.dp(5) }
        )
        textCol.addView(
            labelRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        nameTv = TextView(context).apply {
            setTextColor(tc.textStrong)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(
            nameTv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = LayoutHelper.dp(1) }
        )

        row.addView(
            textCol,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        durationView = CallDurationView(context, tc).apply {
            setTextSizeSp(14f)
        }
        row.addView(
            durationView,
            LinearLayout.LayoutParams(LayoutHelper.dp(52), LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(callInfo: CallInfo, connectedTime: Long) {
        if (boundConnectedTime == connectedTime && boundPeerId == callInfo.peerId) return
        boundConnectedTime = connectedTime
        boundPeerId = callInfo.peerId
        nameTv.text = callInfo.peerName
        avatarView.setInfo(callInfo.peerId, callInfo.peerUsername.ifEmpty { callInfo.peerName })
        avatarView.setImageUrl(callInfo.peerAvatar)
        durationView.startTimer(connectedTime)
    }

    fun show() {
        visibility = View.VISIBLE
    }

    fun dismiss() {
        boundConnectedTime = -1L
        boundPeerId = -1L
        durationView.stopTimer()
        visibility = View.GONE
    }

    private class LiveDot(context: Context, private val dotColor: Int) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var animator: ValueAnimator? = null
        private var pulse = 1f

        override fun onDraw(canvas: Canvas) {
            paint.color = dotColor
            paint.alpha = (pulse * 255).toInt()
            canvas.drawCircle(width / 2f, height / 2f, LayoutHelper.dpf(3.5f), paint)
        }

        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            if (isVisible) startPulse() else stopPulse()
        }

        private fun startPulse() {
            if (animator != null) return
            animator = ValueAnimator.ofFloat(1f, 0.3f).apply {
                duration = 850L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    pulse = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun stopPulse() {
            animator?.cancel()
            animator = null
            pulse = 1f
        }
    }
}
