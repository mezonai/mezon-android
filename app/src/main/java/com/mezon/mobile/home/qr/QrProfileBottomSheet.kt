package com.mezon.mobile.home.qr

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.ui.cells.AvatarView

class QrProfileBottomSheet(
    context: Context,
    private val displayName: String,
    private val username: String,
    private val avatarUrl: String?
) : BottomSheet(context) {

    interface Listener {
        fun onAddFriend() {}
        fun onMessage() {}
        fun onDismissed() {}
    }

    var listener: Listener? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        val root = FrameLayout(context).apply {
            setPadding(0, LayoutHelper.dp(24), 0, LayoutHelper.dp(24))
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(16f).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(
                LayoutHelper.dp(20),
                LayoutHelper.dp(16),
                LayoutHelper.dp(20),
                LayoutHelper.dp(16)
            )
        }
        root.addView(card, FrameLayout.LayoutParams(
            LayoutHelper.dp(300),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        val title = TextView(context).apply {
            text = context.getString(R.string.qr_profile_sheet_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF888888.toInt())
            letterSpacing = 0.05f
        }
        card.addView(title)

        val avatar = AvatarView(context).apply {
            setSizeDp(64)
            setRoundRadius(32f)
            setInfo(0L, displayName.ifEmpty { username })
            if (!avatarUrl.isNullOrBlank()) {
                setImageUrl(avatarUrl)
            }
        }
        card.addView(avatar, LinearLayout.LayoutParams(
            LayoutHelper.dp(64),
            LayoutHelper.dp(64)
        ).apply { topMargin = LayoutHelper.dp(12) })

        val nameView = TextView(context).apply {
            text = username.ifEmpty { displayName }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(0xFF1F1F1F.toInt())
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        card.addView(nameView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(8) })

        card.addView(buildPrimaryButton(
            context.getString(R.string.qr_profile_add_friend)
        ) {
            listener?.onAddFriend()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(44)
        ).apply { topMargin = LayoutHelper.dp(16) })

        card.addView(buildPrimaryButton(
            context.getString(R.string.qr_profile_message),
            secondary = true
        ) {
            listener?.onMessage()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(44)
        ).apply { topMargin = LayoutHelper.dp(8) })

        card.addView(buildGhostButton(
            context.getString(R.string.qr_profile_decline)
        ) {
            dismiss()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(44)
        ).apply { topMargin = LayoutHelper.dp(8) })

        setCustomView(root)
        super.onCreate(savedInstanceState)
    }

    override fun dismiss() {
        super.dismiss()
        listener?.onDismissed()
    }

    private fun buildPrimaryButton(text: String, secondary: Boolean = false, onClick: () -> Unit): View {
        val color = if (secondary) 0xFF5E68F0.toInt() else 0xFF4F46E5.toInt()
        return TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(color)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun buildGhostButton(text: String, onClick: () -> Unit): View {
        return TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF6B7280.toInt())
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(0xFFE5E7EB.toInt())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }
}

