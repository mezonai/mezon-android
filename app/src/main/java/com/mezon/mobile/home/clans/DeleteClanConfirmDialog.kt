package com.mezon.mobile.home.clans

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class DeleteClanConfirmDialog(
    context: Context,
    private val theme: ThemeColors,
    private val isLeaveClan: Boolean,
    private val clanName: String,
    private val onConfirm: () -> Unit,
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

        val titleRes = if (isLeaveClan) R.string.clan_leave_confirm_title else R.string.clan_delete_confirm_title
        val messageRes = if (isLeaveClan) R.string.clan_leave_confirm_message else R.string.clan_delete_confirm_message

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, LayoutHelper.dp(12))
        }
        header.addView(TextView(context).apply {
            text = context.getString(titleRes)
            setTextColor(theme.textStrong)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val divider = View(context).apply {
            setBackgroundColor(theme.border)
        }
        header.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.dp(1)))

        card.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, topMargin = 0f, bottomMargin = LayoutHelper.dp(16).toFloat()))

        val messageText = context.getString(messageRes, clanName)
        val spannable = SpannableString(messageText)
        val nameStart = messageText.indexOf(clanName)
        if (nameStart >= 0) {
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                nameStart,
                nameStart + clanName.length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        card.addView(TextView(context).apply {
            text = spannable
            setTextColor(theme.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setLineSpacing(LayoutHelper.dp(4).toFloat(), 1f)
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, bottomMargin = LayoutHelper.dp(16).toFloat()))

        val btnStack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun pillButton(label: String, bgColor: Int, textColor: Int, action: () -> Unit): TextView {
            return TextView(context).apply {
                text = label
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(LayoutHelper.dp(10), LayoutHelper.dp(10), LayoutHelper.dp(10), LayoutHelper.dp(10))
                background = GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = btnRadius
                }
                setOnClickListener {
                    action()
                    dismiss()
                }
            }
        }

        btnStack.addView(
            pillButton(context.getString(R.string.common_yes), theme.blurple, 0xFFFFFFFF.toInt()) {
                onConfirm()
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, bottomMargin = LayoutHelper.dp(12).toFloat()),
        )
        btnStack.addView(
            pillButton(context.getString(R.string.common_cancel), theme.secondaryWeight, theme.textStrong) {},
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
        )

        card.addView(btnStack, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, topMargin = LayoutHelper.dp(12).toFloat()))

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
        fun show(
            context: Context,
            theme: ThemeColors,
            isLeaveClan: Boolean,
            clanName: String,
            onConfirm: () -> Unit,
        ) {
            DeleteClanConfirmDialog(context, theme, isLeaveClan, clanName, onConfirm).show()
        }
    }
}
