package com.mezon.mobile.home.stream

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper.dp

/** Uses the app's custom, swipe-dismissable sheet rather than a native action menu. */
class StreamingQualitySheet(context: Context, session: StreamingWebRtcSession) : BottomSheet(context) {
    private val accent = 0xFF9E8AFF.toInt()
    init {
        setBackgroundColor(0xFF1B1B1B.toInt())
        setNavigationBarColor(0xFF1B1B1B.toInt())
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }
        fun label(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(context).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        val handle = View(context).apply { background = rounded(0xFF666666.toInt(), 2) }
        content.addView(handle, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(18) })
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val heading = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(label(context.getString(R.string.stream_quality_title), 20f, bold = true))
        heading.addView(label(context.getString(R.string.stream_quality_description), 13f, 0xFF888888.toInt()))
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label("×", 26f, 0xFFBBBBBB.toInt()).apply {
            gravity = Gravity.CENTER; background = rounded(0xFF292929.toInt(), 18)
            contentDescription = context.getString(R.string.stream_close)
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        content.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        for (quality in StreamingQuality.entries) {
            val selected = quality == session.quality
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(60)
                setPadding(dp(12), dp(10), dp(14), dp(10))
                background = rounded(if (selected) 0xFF302B42.toInt() else 0xFF202020.toInt(), 12, if (selected) accent else 0)
                isSelected = selected
                contentDescription = context.getString(quality.titleRes) + ", " + context.getString(quality.detailRes)
                setOnClickListener { session.setQuality(quality); dismiss() }
            }
            row.addView(label(when (quality) { StreamingQuality.AUTO -> "A"; StreamingQuality.HIGH -> "HQ"; StreamingQuality.MEDIUM -> "MQ"; StreamingQuality.LOW -> "LQ" }, 12f, if (selected) accent else 0xFFAAAAAA.toInt(), true).apply {
                gravity = Gravity.CENTER; background = rounded(if (selected) 0xFF403854.toInt() else 0xFF2B2B2B.toInt(), 9)
            }, LinearLayout.LayoutParams(dp(34), dp(34)))
            val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(label(context.getString(quality.titleRes), 16f, bold = selected))
            labels.addView(label(context.getString(quality.detailRes), 12f, 0xFF888888.toInt()))
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12); marginEnd = dp(8) })
            row.addView(label(if (selected) "✓" else "○", 22f, if (selected) accent else 0xFF555555.toInt()).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(24), dp(28)))
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        val isDefault = session.quality == session.defaultQuality
        val save = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFF252525.toInt(), 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isEnabled = !isDefault
            setOnClickListener { session.setDefaultQuality(session.quality); dismiss() }
        }
        save.addView(label(context.getString(if (isDefault) R.string.stream_quality_default else R.string.stream_quality_save_default), 14f, bold = true))
        save.addView(label(context.getString(R.string.stream_quality_default_detail, context.getString(session.quality.titleRes)), 12f, 0xFF888888.toInt()))
        content.addView(save, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        setCustomView(ScrollView(context).apply { isFillViewport = false; addView(content) })
    }
    private fun rounded(color: Int, radius: Int, border: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (border != 0) setStroke(dp(1), border)
    }
}
