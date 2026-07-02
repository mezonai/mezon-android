package com.mezon.mobile.home.clans

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class RapidClanSwitchDialog(
    context: Context,
    private val theme: ThemeColors,
) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar) {

    init {
        setCancelable(true)
        setCanceledOnTouchOutside(true)

        val pad = LayoutHelper.dp(16)
        val cardRadius = LayoutHelper.dp(16).toFloat()
        val btnRadius = LayoutHelper.dp(20).toFloat()

        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(0x80000000.toInt())
            setOnClickListener { dismiss() }
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(theme.surface)
                cornerRadius = cardRadius
            }
            isClickable = true
        }

        card.addView(TextView(context).apply {
            text = context.getString(R.string.clan_rapid_switch_title)
            setTextColor(theme.textStrong)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, bottomMargin = LayoutHelper.dp(10).toFloat()))

        card.addView(TextView(context).apply {
            text = context.getString(R.string.clan_rapid_switch_message)
            setTextColor(theme.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setLineSpacing(LayoutHelper.dp(4).toFloat(), 1f)
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, bottomMargin = LayoutHelper.dp(20).toFloat()))

        card.addView(TextView(context).apply {
            text = context.getString(R.string.clan_rapid_switch_confirm)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(LayoutHelper.dp(10), LayoutHelper.dp(12), LayoutHelper.dp(10), LayoutHelper.dp(12))
            background = GradientDrawable().apply {
                setColor(theme.blurple)
                cornerRadius = btnRadius
            }
            setOnClickListener { dismiss() }
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val cardLp = FrameLayout.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.9f).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
        root.addView(card, cardLp)
        setContentView(root)

        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    companion object {
        fun show(context: Context, theme: ThemeColors) {
            RapidClanSwitchDialog(context, theme).show()
        }
    }
}
