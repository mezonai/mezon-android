package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class ChatUnreadCell(context: Context, themeColors: ThemeColors) : LinearLayout(context) {

    companion object {
        private val LINE_COLOR = 0x80FF0000.toInt()
        private val TEXT_COLOR = 0xFFFF0000.toInt()
        private val PADDING = LayoutHelper.dp(12f)
        private val TEXT_H_PADDING = LayoutHelper.dp(8f)
        private val LINE_HEIGHT = LayoutHelper.dp(1f)
        private val ANIM_OFFSET = LayoutHelper.dp(10f).toFloat()
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(PADDING, PADDING, PADDING, PADDING)

        val leftLine = View(context).apply { setBackgroundColor(LINE_COLOR) }
        addView(leftLine, LayoutParams(0, LINE_HEIGHT, 1f))

        val label = TextView(context).apply {
            text = context.getString(R.string.message_new_messages)
            setTextColor(TEXT_COLOR)
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(TEXT_H_PADDING, 0, TEXT_H_PADDING, 0)
        }
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        val rightLine = View(context).apply { setBackgroundColor(LINE_COLOR) }
        addView(rightLine, LayoutParams(0, LINE_HEIGHT, 1f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        alpha = 0f
        translationY = -ANIM_OFFSET
        animate().alpha(1f).translationY(0f).setDuration(200).start()
    }

    override fun onDetachedFromWindow() {
        animate().cancel()
        super.onDetachedFromWindow()
    }
}
