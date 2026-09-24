package com.mezon.mobile.deeplink

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

internal class ChannelDeepLinkLoadingView(context: Context, colors: ThemeColors) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = false
        isFocusable = false
        setPadding(LayoutHelper.dp(18), LayoutHelper.dp(15), LayoutHelper.dp(18), LayoutHelper.dp(15))
        background = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dp(18).toFloat()
            setColor(colors.surface)
            setStroke(LayoutHelper.dp(1), colors.surfaceVariant)
        }
        elevation = LayoutHelper.dp(12).toFloat()

        val spinner = ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(colors.primary)
        }
        addView(spinner, LinearLayout.LayoutParams(LayoutHelper.dp(26), LayoutHelper.dp(26)))

        val labels = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(LayoutHelper.dp(14), 0, 0, 0)
        }
        addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        labels.addView(TextView(context).apply {
            text = context.getString(R.string.deeplink_opening_channel)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colors.onSurface)
        })
        labels.addView(TextView(context).apply {
            text = context.getString(R.string.deeplink_checking_access)
            textSize = 13f
            setTextColor(colors.onSurfaceVariant)
        })
        contentDescription = "${context.getString(R.string.deeplink_opening_channel)} ${context.getString(R.string.deeplink_checking_access)}"
    }
}

internal class ChannelUnavailableBottomSheet(
    context: Context,
    colors: ThemeColors
) : BottomSheet(context) {
    init {
        setCanDismissWithSwipe(true)
        setCanDismissWithTouchOutside(true)
        setCancelable(true)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(LayoutHelper.dp(24), LayoutHelper.dp(12), LayoutHelper.dp(24), LayoutHelper.dp(24))
        }
        val iconBackground = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colors.surfaceVariant)
            }
        }
        iconBackground.addView(ImageView(context).apply {
            setImageDrawable(MezonIcon.lockIcon.getDrawable(context, colors.primary))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, FrameLayout.LayoutParams(LayoutHelper.dp(30), LayoutHelper.dp(30), Gravity.CENTER))
        content.addView(iconBackground, LinearLayout.LayoutParams(LayoutHelper.dp(64), LayoutHelper.dp(64)))

        content.addView(TextView(context).apply {
            text = context.getString(R.string.deeplink_channel_unavailable_title)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colors.onSurface)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(16)
        })
        content.addView(TextView(context).apply {
            text = context.getString(R.string.deeplink_channel_unavailable_message)
            textSize = 15f
            setTextColor(colors.onSurfaceVariant)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(10)
        })
        content.addView(TextView(context).apply {
            text = context.getString(R.string.deeplink_channel_unavailable_got_it)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colors.onPrimary)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12).toFloat()
                setColor(colors.primary)
            }
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(48)).apply {
            topMargin = LayoutHelper.dp(24)
        })
        setCustomView(content)
    }
}
