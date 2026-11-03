package com.mezon.mobile.home.qr

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableString
import android.text.Spanned
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.ColoredImageSpan
import com.mezon.mobile.ui.cells.MezonIcon

class QrProfileDialog(
    context: Context,
    private val themeColors: ThemeColors,
    userId: Long,
    name: String,
    username: String,
    avatarUrl: String?,
    showAddFriend: Boolean,
    onAddFriend: () -> Unit,
    onMessage: () -> Unit,
    onCall: () -> Unit,
) : Dialog(context) {

    private val actionButtons = mutableListOf<View>()
    private val mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val root = FrameLayout(context)

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCancelable(true)
        setCanceledOnTouchOutside(true)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(LayoutHelper.dp(24), LayoutHelper.dp(32), LayoutHelper.dp(24), LayoutHelper.dp(24))
        }

        card.addView(AvatarView(context).apply {
            setSizeDp(72)
            setRoundRadius(36f)
            setUserId(userId)
            setImageUrl(avatarUrl)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutHelper.createLinear(72, 72, gravity = Gravity.CENTER_HORIZONTAL))

        card.addView(TextView(context).apply {
            text = name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(themeColors.textStrong)
            typeface = mediumTypeface
            includeFontPadding = false
            gravity = Gravity.CENTER
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, topMargin = 16f))

        if (username.isNotBlank() && !username.equals(name, ignoreCase = true)) {
            card.addView(TextView(context).apply {
                text = "@" + username.removePrefix("@")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(themeColors.tabLabelInactive)
                includeFontPadding = false
                gravity = Gravity.CENTER
            }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, topMargin = 4f))
        }

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(actionButton(
            context.getString(R.string.qr_profile_message),
            MezonIcon.chatIcon,
            themeColors.primary,
            themeColors.onPrimary,
            onMessage,
        ), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, weight = 1f).apply {
            marginEnd = LayoutHelper.dp(12)
        })
        actions.addView(actionButton(
            context.getString(R.string.profile_call),
            MezonIcon.phoneCallIcon,
            themeColors.primary,
            themeColors.onPrimary,
            onCall,
        ), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, weight = 1f))
        card.addView(actions, buttonLayout(24f))

        if (showAddFriend) {
            card.addView(actionButton(
                context.getString(R.string.qr_profile_add_friend),
                MezonIcon.userPlusIcon,
                themeColors.channelPanelBg,
                themeColors.textStrong,
                onAddFriend,
            ), buttonLayout(12f))
        }

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            background = roundedBackground(themeColors.secondaryLight, 24f)
            clipToOutline = true
            addView(card)
        }
        root.addView(scroll, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        val closeButton = object : ImageView(context) {
            override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
        }.apply {
            setImageDrawable(MezonIcon.closeIcon.getDrawable(context, themeColors.tabLabelInactive))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(LayoutHelper.dp(13), LayoutHelper.dp(13), LayoutHelper.dp(13), LayoutHelper.dp(13))
            contentDescription = context.getString(R.string.common_close)
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf((themeColors.onSurface and 0x00FFFFFF) or 0x24000000),
                null,
                roundedBackground(Color.WHITE, 22f),
            )
            setOnClickListener { dismiss() }
        }
        root.addView(closeButton, FrameLayout.LayoutParams(
            LayoutHelper.dp(44), LayoutHelper.dp(44), Gravity.TOP or Gravity.END,
        ).apply {
            topMargin = LayoutHelper.dp(6)
            marginEnd = LayoutHelper.dp(6)
        })
        setContentView(root)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
        }
    }

    override fun onStart() {
        super.onStart()
        val availableWidth = context.resources.displayMetrics.widthPixels - LayoutHelper.dp(32)
        window?.apply {
            decorView.setPadding(0, 0, 0, 0)
            setGravity(Gravity.CENTER)
            setLayout(minOf(availableWidth, LayoutHelper.dp(420)), WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    fun setActionsEnabled(enabled: Boolean) {
        actionButtons.forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.5f
        }
    }

    private fun actionButton(
        label: String,
        icon: MezonIcon,
        color: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): TextView = object : TextView(context) {
        override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
    }.apply {
        val iconSpan = ColoredImageSpan(icon.getDrawable(context, textColor), ColoredImageSpan.ALIGN_CENTER).apply {
            setSize(LayoutHelper.dp(20))
            setWidth(LayoutHelper.dp(28))
        }
        text = SpannableString("\uFFFC$label").apply {
            setSpan(iconSpan, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        contentDescription = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(textColor)
        typeface = mediumTypeface
        includeFontPadding = false
        minHeight = LayoutHelper.dp(48)
        setPadding(LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12))
        background = RippleDrawable(
            ColorStateList.valueOf((textColor and 0x00FFFFFF) or 0x24000000),
            roundedBackground(color, 16f),
            roundedBackground(Color.WHITE, 16f),
        )
        isFocusable = true
        setOnClickListener { onClick() }
        actionButtons.add(this)
    }

    private fun buttonLayout(topMargin: Float) = LayoutHelper.createLinear(
        LayoutHelper.MATCH_PARENT,
        LayoutHelper.WRAP_CONTENT,
        topMargin = topMargin,
    )

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = LayoutHelper.dp(radius).toFloat()
    }
}
