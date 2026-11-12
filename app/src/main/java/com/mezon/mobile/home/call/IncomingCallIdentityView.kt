package com.mezon.mobile.home.call

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper.dp
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.AvatarView

internal class IncomingCallIdentityView(
    context: Context,
    private val themeColors: ThemeColors
) : LinearLayout(context) {
    private val ring = AvatarRing(
        context,
        ColorUtils.blendARGB(themeColors.primary, themeColors.onSurface, 0.64f),
        themeColors.onSurface
    )
    private val avatar = AvatarView(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val portrait = FrameLayout(context).apply {
        addView(ring, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(avatar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
    }
    private val nameView = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 26f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(themeColors.onSurface)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val statusSlot = FrameLayout(context)
    private val statusText = TextView(context).apply {
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val connectedDot = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(themeColors.connectedColor)
        }
        visibility = GONE
    }
    private val duration = Chronometer(context).apply {
        textSize = 14f
        fontFeatureSettings = "tnum"
        visibility = GONE
    }
    private val statusView = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(6), dp(12), dp(6))
        addView(connectedDot, LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) })
        addView(statusText, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(duration, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
    }
    private var compact = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), 0, dp(24), 0)
        avatar.setSizeDp(112)
        avatar.setRoundRadius(56f)
        addView(portrait, LayoutParams(dp(128), dp(128)))
        addView(nameView, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        addView(statusSlot, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        moveStatus(null)
        showConnecting()
    }

    fun bind(name: String, avatarUrl: String?) {
        nameView.text = name
        avatar.setInfo(0L, name)
        avatar.setImageUrl(avatarUrl?.takeUnless { it.isBlank() || it == "null" })
    }

    fun showConnecting() {
        ring.setConnecting(true)
        statusText.setText(R.string.common_error_connecting)
        connectedDot.visibility = GONE
        duration.visibility = GONE
        duration.stop()
    }

    fun showConnected(connectedTime: Long) {
        ring.setConnecting(false)
        statusText.setText(R.string.connection_connected)
        connectedDot.visibility = VISIBLE
        duration.visibility = VISIBLE
        duration.base = connectedTime
        duration.start()
    }

    fun moveStatus(videoContainer: FrameLayout?) {
        val target = videoContainer ?: statusSlot
        if (statusView.parent !== target) {
            (statusView.parent as? FrameLayout)?.removeView(statusView)
            val params = if (videoContainer == null) {
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER)
            } else {
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                    topMargin = dp(12)
                    marginStart = dp(12)
                }
            }
            target.addView(statusView, params)
        }
        val onVideo = videoContainer != null
        val textColor = if (onVideo) Color.WHITE else themeColors.onSurface
        statusText.setTextColor(textColor)
        duration.setTextColor(if (onVideo) Color.WHITE else themeColors.onSurfaceVariant)
        statusView.background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(
                if (onVideo) 0xC4212430.toInt()
                else ColorUtils.blendARGB(themeColors.serverRailBg, themeColors.onSurface, 0.08f)
            )
        }
        if (onVideo) statusView.bringToFront()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val useCompactLayout = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED &&
            MeasureSpec.getSize(heightMeasureSpec) < dp(224)
        if (useCompactLayout != compact) {
            compact = useCompactLayout
            val portraitSize = if (compact) 80 else 128
            portrait.layoutParams.width = dp(portraitSize)
            portrait.layoutParams.height = dp(portraitSize)
            val avatarSize = portraitSize - 16
            avatar.setSizeDp(avatarSize)
            avatar.setRoundRadius(avatarSize / 2f)
            nameView.textSize = if (compact) 20f else 26f
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private class AvatarRing(context: Context, private val accent: Int, textColor: Int) : View(context) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(textColor, 34)
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }
        private val highlight = Paint(track).apply {
            color = Color.WHITE
            strokeWidth = dp(3).toFloat()
        }
        private var connecting = true
        private var rotation = -90f
        private var animator: ValueAnimator? = null

        fun setConnecting(value: Boolean) {
            if (connecting == value) return
            connecting = value
            updateAnimation(isShown && isAttachedToWindow)
            invalidate()
        }

        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            updateAnimation(isVisible)
        }

        private fun updateAnimation(visible: Boolean) {
            val animationsEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
            if (visible && connecting && animationsEnabled) {
                if (animator != null) return
                animator = ValueAnimator.ofFloat(-90f, 270f).apply {
                    duration = 1300
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        rotation = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            } else {
                animator?.cancel()
                animator = null
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            highlight.shader = SweepGradient(
                w / 2f, h / 2f,
                intArrayOf(ColorUtils.setAlphaComponent(accent, 0), accent, ColorUtils.setAlphaComponent(accent, 0)),
                floatArrayOf(0f, 0.30f, 0.36f)
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(cx, cy) - dp(3)
            if (radius <= 0f) return
            canvas.drawCircle(cx, cy, radius, track)
            if (connecting) {
                canvas.save()
                canvas.rotate(rotation, cx, cy)
                canvas.drawCircle(cx, cy, radius, highlight)
                canvas.restore()
            }
        }

        override fun onDetachedFromWindow() {
            updateAnimation(false)
            super.onDetachedFromWindow()
        }
    }

    private companion object {
        const val MATCH_PARENT = LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = LayoutParams.WRAP_CONTENT
    }
}
