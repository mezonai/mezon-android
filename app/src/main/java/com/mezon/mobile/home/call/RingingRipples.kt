package com.mezon.mobile.home.call

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import com.mezon.mobile.core.ThemeColors
import kotlin.math.PI
import kotlin.math.cos

internal class RingingRipples(
    private val themeColors: ThemeColors,
    private val invalidate: () -> Unit
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private var animator: ValueAnimator? = null

    fun setVisible(visible: Boolean) {
        val animationsEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
        if (!visible || !animationsEnabled) {
            animator?.cancel()
            animator = null
            return
        }
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2300L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun draw(canvas: Canvas, cx: Float, cy: Float, avatarRadius: Float, availableRadius: Float) {
        val outerRadius = minOf(avatarRadius * 2.1f, availableRadius)
        val extraRadius = outerRadius - avatarRadius
        if (avatarRadius <= 0f || extraRadius <= 0f) return

        val animating = animator != null
        val innerPulse = if (animating) pulse(progress) else 1f
        val outerPulse = if (animating) pulse(progress - 0.20f) else 1f
        paint.color = ColorUtils.blendARGB(themeColors.primary, themeColors.onSurface, 0.60f)

        paint.alpha = ((if (animating) 0.065f + 0.055f * outerPulse else 0.09f) * 255).toInt()
        canvas.drawCircle(cx, cy, avatarRadius + extraRadius * (0.70f + 0.30f * outerPulse), paint)

        paint.alpha = ((if (animating) 0.12f + 0.08f * innerPulse else 0.16f) * 255).toInt()
        canvas.drawCircle(cx, cy, avatarRadius + extraRadius * (0.22f + 0.13f * innerPulse), paint)
    }

    private fun pulse(phase: Float): Float = ((1.0 - cos(2.0 * PI * phase)) / 2.0).toFloat()
}
