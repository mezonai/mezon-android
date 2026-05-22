package com.mezon.mobile.home.qr

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
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
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class QrExternalLinkBottomSheet(
    private val sheetContext: Context,
    private val themeColors: ThemeColors,
    private val url: String,
    private val onOpenLink: () -> Boolean,
    private val onClosed: () -> Unit
) : BottomSheet(sheetContext) {

    private var openRequested = false

    init {
        setCanDismissWithSwipe(true)
        setCanDismissWithTouchOutside(true)
        setCancelable(true)
        setCustomView(buildContent(sheetContext))
        setOnHideListener {
            if (!openRequested) onClosed()
        }
    }

    private fun buildContent(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(20), LayoutHelper.dp(6), LayoutHelper.dp(20), LayoutHelper.dp(24))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = LayoutHelper.dp(16)
        })

        val iconWrap = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.surfaceVariant)
            }
        }
        val linkIcon = ImageView(context).apply {
            setImageDrawable(MezonIcon.linkIcon.getDrawable(context, themeColors.primary))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        iconWrap.addView(linkIcon, FrameLayout.LayoutParams(
            LayoutHelper.dp(22),
            LayoutHelper.dp(22),
            Gravity.CENTER
        ))
        header.addView(iconWrap, LinearLayout.LayoutParams(
            LayoutHelper.dp(44),
            LayoutHelper.dp(44)
        ))

        val titleColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(12), 0, 0, 0)
        }
        header.addView(titleColumn, LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ))

        val title = TextView(context).apply {
            text = context.getString(R.string.qr_external_link_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(themeColors.onSurface)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        titleColumn.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val subtitle = TextView(context).apply {
            text = context.getString(R.string.qr_external_link_message)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(themeColors.onSurfaceVariant)
            includeFontPadding = false
            setPadding(0, LayoutHelper.dp(6), 0, 0)
        }
        titleColumn.addView(subtitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val urlBox = TextView(context).apply {
            text = url
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurface)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(LayoutHelper.dp(14), LayoutHelper.dp(12), LayoutHelper.dp(14), LayoutHelper.dp(12))
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(themeColors.surfaceVariant)
            }
        }
        root.addView(urlBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = LayoutHelper.dp(18)
        })

        val openButton = TextView(context).apply {
            text = context.getString(R.string.qr_external_link_open)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(themeColors.onPrimary)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(14f).toFloat()
                setColor(themeColors.primary)
            }
            setOnClickListener {
                openRequested = true
                val opened = onOpenLink()
                dismiss()
                if (!opened) onClosed()
            }
        }
        root.addView(openButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(48)
        ))

        val cancelButton = TextView(context).apply {
            text = context.getString(R.string.common_cancel)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(themeColors.onSurfaceVariant)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { dismiss() }
        }
        root.addView(cancelButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(44)
        ).apply {
            topMargin = LayoutHelper.dp(6)
        })

        return root
    }
}
