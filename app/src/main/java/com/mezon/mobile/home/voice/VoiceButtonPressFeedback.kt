package com.mezon.mobile.home.voice

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

fun View.applyVoiceButtonPressFeedback(pressedAlpha: Float = 0.78f, pressedScale: Float = 0.96f) {
    val original = background
    if (original != null && original !is RippleDrawable) {
        val rippleColor = ColorStateList.valueOf(resolveRippleColor(context))
        background = RippleDrawable(rippleColor, original, null)
    }
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.animate().cancel()
                view.alpha = pressedAlpha
                view.scaleX = pressedScale
                view.scaleY = pressedScale
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.animate().cancel()
                view.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120L)
                    .start()
            }
        }
        false
    }
}

private fun resolveRippleColor(context: Context): Int {
    val tv = TypedValue()
    return if (context.theme.resolveAttribute(android.R.attr.colorControlHighlight, tv, true)) {
        tv.data
    } else {
        0x1F000000.toInt()
    }
}
