package com.mezon.mobile.home.voice

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.mezon.mobile.core.LayoutHelper
import java.util.concurrent.CopyOnWriteArrayList

class ReactionOverlayView(context: Context) : View(context) {

    companion object {
        private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(32f)
            textAlign = Paint.Align.CENTER
        }
        private const val ANIMATION_DURATION = 2000L
    }

    private val activeEmojis = CopyOnWriteArrayList<FloatingEmoji>()

    data class FloatingEmoji(
        val emoji: String,
        var x: Float,
        var startY: Float,
        var currentY: Float,
        var alpha: Float,
        var animator: ValueAnimator? = null
    )

    fun showEmojis(emojis: List<String>) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        for (emoji in emojis) {
            val startX = (Math.random() * (w - LayoutHelper.dp(60)) + LayoutHelper.dp(30)).toFloat()
            val startY = h
            val fe = FloatingEmoji(emoji, startX, startY, startY, 1f)

            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ANIMATION_DURATION
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val progress = anim.animatedValue as Float
                    fe.currentY = startY - (h * 0.7f * progress)
                    fe.alpha = 1f - progress * 0.8f
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        activeEmojis.remove(fe)
                        invalidate()
                    }
                })
            }
            fe.animator = animator
            activeEmojis.add(fe)
            animator.start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        for (fe in activeEmojis) {
            emojiPaint.alpha = (fe.alpha * 255).toInt().coerceIn(0, 255)
            canvas.drawText(fe.emoji, fe.x, fe.currentY, emojiPaint)
        }
    }

    fun cancelAll() {
        for (fe in activeEmojis) {
            fe.animator?.cancel()
        }
        activeEmojis.clear()
    }
}
