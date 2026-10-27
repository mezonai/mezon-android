package com.mezon.mobile.home.voice

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper.dp

/** Desktop-style participant dialog; primary action precedes Not now on mobile. */
internal class VoiceBreakoutDialog(
    activity: Activity,
    signal: VoiceBreakoutState.Signal,
    rooms: List<Pair<Long, String>>,
    onJoin: (Long) -> Unit,
    onNotNow: () -> Unit,
) : Dialog(activity) {
    private var completing = false
    private val width = minOf(dp(420), activity.resources.displayMetrics.widthPixels - dp(40))

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = rounded(0xFF242424.toInt(), 16, border = true)
        }
        val title = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        title.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_voice_breakout)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        title.addView(label(activity, activity.getString(R.string.voice_breakout_title), 18f, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        card.addView(title)

        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val message = when (signal) {
            is VoiceBreakoutState.Signal.Assigned -> R.string.voice_breakout_assigned
            is VoiceBreakoutState.Signal.Choose -> R.string.voice_breakout_choose
            VoiceBreakoutState.Signal.Return -> R.string.voice_breakout_return
        }
        body.addView(label(activity, activity.getString(message), 14f))
        if (signal != VoiceBreakoutState.Signal.Return) {
            rooms.forEach { (id, name) ->
                val row = LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(48)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = rounded(0xFF2E2E2E.toInt(), 8, border = true)
                }
                row.addView(ImageView(activity).apply { setImageResource(R.drawable.ic_voice_breakout) },
                    LinearLayout.LayoutParams(dp(18), dp(18)))
                row.addView(label(activity, name, 14f, bold = true),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
                if (signal is VoiceBreakoutState.Signal.Choose) {
                    row.addView(label(activity, activity.getString(R.string.voice_breakout_join), 14f, bold = true).apply {
                        setTextColor(ACCENT)
                        setPadding(dp(12), 0, 0, 0)
                    })
                    row.isFocusable = true
                    row.contentDescription = "$name, ${activity.getString(R.string.voice_breakout_join)}"
                    row.setOnClickListener { complete { onJoin(id) } }
                }
                body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
            }
        }
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeight = minOf(dp(320), (resources.displayMetrics.heightPixels * 0.45).toInt())
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
            }
        }.apply { addView(body); isFillViewport = false }
        card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20); bottomMargin = dp(20) })
        if (signal !is VoiceBreakoutState.Signal.Choose && rooms.isNotEmpty()) {
            val text = activity.getString(if (signal == VoiceBreakoutState.Signal.Return)
                R.string.voice_breakout_return_to_main else R.string.voice_breakout_join)
            card.addView(action(activity, text, primary = true) { complete { onJoin(rooms.first().first) } })
        }
        card.addView(action(activity, activity.getString(R.string.voice_breakout_not_now), primary = false) {
            complete(onNotNow)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        setContentView(card)
        setCanceledOnTouchOutside(true)
        setOnCancelListener { if (!completing) { completing = true; onNotNow() } }
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
        }
    }

    override fun show() {
        super.show()
        window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun complete(action: () -> Unit) {
        if (completing) return
        completing = true
        dismiss()
        action()
    }

    private fun label(context: Context, value: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(0xFFF2F3F5.toInt())
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun action(context: Context, text: String, primary: Boolean, click: () -> Unit) =
        label(context, text, 14f, bold = primary).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            if (primary) background = rounded(ACCENT, 8) else setTextColor(0xFFB5BAC1.toInt())
            isFocusable = true
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    private fun rounded(color: Int, radius: Int, border: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (border) setStroke(dp(1), 0xFF3D3D3D.toInt())
    }

    private companion object { const val ACCENT = 0xFF5865F2.toInt() }
}
