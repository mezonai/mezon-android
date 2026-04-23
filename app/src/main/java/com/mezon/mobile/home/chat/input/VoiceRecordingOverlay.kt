package com.mezon.mobile.home.chat.input

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class VoiceRecordingOverlay(
    context: Context,
    private val theme: ThemeColors
) : FrameLayout(context) {

    private val micContainer: FrameLayout
    private val pulseCircle: View
    private val micIcon: ImageView
    private val timerView: TextView
    private val slideContainer: LinearLayout
    private val slideText: TextView
    private val chevronIcon: ImageView

    private var pulseAnimator: ValueAnimator? = null
    private var slideAnimator: ObjectAnimator? = null
    private var timerHandler: Handler? = null
    private var timerRunnable: Runnable? = null
    private var startTimeMs: Long = 0

    init {
        background = GradientDrawable(
            GradientDrawable.Orientation.RIGHT_LEFT,
            intArrayOf(theme.primary, theme.primary)
        ).apply {
            cornerRadius = LayoutHelper.dp(20f).toFloat()
        }

        val leftWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(
            leftWrap,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            ).apply { leftMargin = LayoutHelper.dp(12f) }
        )

        micContainer = FrameLayout(context)
        leftWrap.addView(
            micContainer,
            LinearLayout.LayoutParams(LayoutHelper.dp(28f), LayoutHelper.dp(28f))
        )

        pulseCircle = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(0x55, 0xFF, 0x4D, 0x4D))
            }
        }
        micContainer.addView(
            pulseCircle,
            LayoutParams(
                LayoutHelper.dp(28f), LayoutHelper.dp(28f),
                Gravity.CENTER
            )
        )

        micIcon = ImageView(context).apply {
            val d = MezonIcon.microphoneIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        micContainer.addView(
            micIcon,
            LayoutParams(
                LayoutHelper.dp(18f), LayoutHelper.dp(18f),
                Gravity.CENTER
            )
        )

        timerView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            text = "0:00"
        }
        leftWrap.addView(
            timerView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = LayoutHelper.dp(10f) }
        )

        slideContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(
            slideContainer,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { rightMargin = LayoutHelper.dp(16f) }
        )

        chevronIcon = ImageView(context).apply {
            val d = MezonIcon.chevronSmallLeftIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        slideContainer.addView(
            chevronIcon,
            LinearLayout.LayoutParams(LayoutHelper.dp(16f), LayoutHelper.dp(16f))
        )

        slideText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            text = DEFAULT_SLIDE_TEXT
        }
        slideContainer.addView(
            slideText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = LayoutHelper.dp(4f) }
        )

        visibility = GONE
    }

    fun setSlideToCancelText(text: String) {
        slideText.text = text
    }

    fun show() {
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(200).start()
        startTimeMs = System.currentTimeMillis()
        startTimer()
        startPulseAnim()
        startSlideAnim()
    }

    fun hide() {
        stopTimer()
        stopPulseAnim()
        stopSlideAnim()
        animate().alpha(0f).setDuration(150).withEndAction {
            visibility = GONE
            alpha = 1f
        }.start()
    }

    fun setSlideProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        slideContainer.alpha = 1f - clamped * 0.8f
    }

    private fun startTimer() {
        stopTimer()
        val handler = Handler(Looper.getMainLooper())
        timerHandler = handler
        timerRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTimeMs
                timerView.text = formatTime(elapsed)
                handler.postDelayed(this, 100)
            }
        }
        timerView.text = "0:00"
        handler.post(timerRunnable!!)
    }

    private fun stopTimer() {
        timerRunnable?.let { timerHandler?.removeCallbacks(it) }
        timerRunnable = null
        timerHandler = null
    }

    private fun startPulseAnim() {
        stopPulseAnim()
        val animator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                val scale = 1f + v * 0.4f
                pulseCircle.scaleX = scale
                pulseCircle.scaleY = scale
                pulseCircle.alpha = 0.7f * (1f - v) + 0.2f
            }
        }
        pulseAnimator = animator
        animator.start()
    }

    private fun stopPulseAnim() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseCircle.scaleX = 1f
        pulseCircle.scaleY = 1f
        pulseCircle.alpha = 0.5f
    }

    private fun startSlideAnim() {
        stopSlideAnim()
        val animator = ObjectAnimator.ofFloat(
            slideContainer, View.TRANSLATION_X,
            0f, -LayoutHelper.dp(12f).toFloat(), 0f
        ).apply {
            duration = 1600
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            interpolator = LinearInterpolator()
        }
        slideAnimator = animator
        animator.start()
    }

    private fun stopSlideAnim() {
        slideAnimator?.cancel()
        slideAnimator = null
        slideContainer.translationX = 0f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopTimer()
        stopPulseAnim()
        stopSlideAnim()
    }

    companion object {
        private const val DEFAULT_SLIDE_TEXT = "Slide to cancel"

        fun formatTime(millis: Long): String {
            val totalSeconds = millis / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }
    }
}
