package com.mezon.mobile.home.clans.settings

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.clans.CreateClanRnUiTokens
import com.mezon.mobile.ui.cells.MezonIcon

object ClanSettingsUiHelpers {

    private val actionIconDp = 15
    private val actionIconPaddingDp = 6

    private val menuRowLeadingIconDp = 20
    private val menuRowChevronDp = 18

    fun newMezonScrollRoot(context: Context): NestedScrollView {
        return NestedScrollView(context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
        }
    }

    fun buildHorizontalActionButton(
        context: Context,
        theme: ThemeColors,
        icon: MezonIcon,
        title: String,
        onPress: Runnable
    ): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val pad = LayoutHelper.dp(actionIconPaddingDp)
        val circle = android.widget.FrameLayout(context).apply {
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.channelPanelBg)
                setStroke(LayoutHelper.dp(1), theme.borderDim)
            }
        }
        val iconSz = LayoutHelper.dp(actionIconDp)
        val iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val d = icon.getDrawable(context)
            if (!icon.shouldKeepOriginalFill()) {
                d.colorFilter = PorterDuffColorFilter(theme.textStrong, PorterDuff.Mode.SRC_IN)
            }
            setImageDrawable(d)
        }
        circle.addView(iconView, LayoutHelper.createFrame(iconSz, iconSz, Gravity.CENTER))
        column.addView(circle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL))

        val label = TextView(context).apply {
            text = title
            textSize = 10f
            setTextColor(theme.textStrong)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        column.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL, 0f, 10f, 0f, 0f))
        column.setOnClickListener { onPress.run() }
        column.isClickable = true
        return column
    }

    fun buildMezonSection(
        context: Context,
        theme: ThemeColors,
        titleText: String?,
        rows: List<View>
    ): LinearLayout {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (!titleText.isNullOrBlank()) {
            outer.addView(
                TextView(context).apply {
                    text = titleText
                    setTextColor(theme.colorText)
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, LayoutHelper.dp(10f))
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
            )
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(theme.channelPanelBg)
                cornerRadius = CreateClanRnUiTokens.clanSettingsMenuCornerPx()
            }
            clipToOutline = true
        }

        val visRows = rows.filter { it.visibility != View.GONE }

        visRows.forEachIndexed { index, child ->
            card.addView(child)
            if (index < visRows.lastIndex) {
                val sep = View(context).apply { setBackgroundColor(theme.tertiary) }
                card.addView(sep, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1))
            }
        }

        outer.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        return outer
    }

    fun buildMezonChevronRow(
        context: Context,
        theme: ThemeColors,
        icon: MezonIcon,
        title: String,
        textColor: Int?,
        onPress: Runnable
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(13f), LayoutHelper.dp(14f), LayoutHelper.dp(13f))
            setBackgroundColor(theme.border)
        }
        val iconIv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val d = icon.getDrawable(context)
            d.mutate()
            if (!icon.shouldKeepOriginalFill()) {
                d.colorFilter = PorterDuffColorFilter(theme.colorText, PorterDuff.Mode.SRC_IN)
            }
            setImageDrawable(d)
        }
        row.addView(iconIv, LayoutHelper.createLinear(menuRowLeadingIconDp, menuRowLeadingIconDp, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 10f, 0f))

        val text = TextView(context).apply {
            text = title
            textSize = 15f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(textColor ?: theme.colorText)
        }
        row.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))

        val chev = ImageView(context).apply {
            val d = MezonIcon.chevronSmallRightIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(theme.colorText, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        row.addView(chev, LayoutHelper.createLinear(menuRowChevronDp, menuRowChevronDp, 0f, Gravity.CENTER_VERTICAL))

        row.setOnClickListener { onPress.run() }
        row.isClickable = true
        return row
    }

    fun buildMezonChevronSubtitleRow(
        context: Context,
        theme: ThemeColors,
        icon: MezonIcon,
        title: String,
        subtitle: String,
        onPress: Runnable
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(13f), LayoutHelper.dp(14f), LayoutHelper.dp(13f))
            setBackgroundColor(theme.border)
        }
        val iconIv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val d = icon.getDrawable(context)
            d.mutate()
            if (!icon.shouldKeepOriginalFill()) {
                d.colorFilter = PorterDuffColorFilter(theme.colorText, PorterDuff.Mode.SRC_IN)
            }
            setImageDrawable(d)
        }
        row.addView(iconIv, LayoutHelper.createLinear(menuRowLeadingIconDp, menuRowLeadingIconDp, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 10f, 0f))

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        texts.addView(
            TextView(context).apply {
                text = title
                textSize = 15f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                setTextColor(theme.colorText)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )
        texts.addView(
            TextView(context).apply {
                text = subtitle
                textSize = 12f
                setTextColor(CreateClanRnUiTokens.textDisabled(theme))
                setPadding(0, LayoutHelper.dp(4f), 0, 0)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))

        val chev = ImageView(context).apply {
            val d = MezonIcon.chevronSmallRightIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(theme.colorText, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        row.addView(chev, LayoutHelper.createLinear(menuRowChevronDp, menuRowChevronDp, 0f, Gravity.CENTER_VERTICAL))

        row.setOnClickListener { onPress.run() }
        row.isClickable = true
        return row
    }

    fun buildMezonChevronRowWithoutIcon(
        context: Context,
        theme: ThemeColors,
        title: String,
        textColor: Int?,
        onPress: Runnable
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(13f), LayoutHelper.dp(14f), LayoutHelper.dp(13f))
            setBackgroundColor(theme.border)
        }
        val text = TextView(context).apply {
            text = title
            textSize = 15f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(textColor ?: theme.colorText)
        }
        row.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))

        val chev = ImageView(context).apply {
            val d = MezonIcon.chevronSmallRightIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(theme.colorText, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        row.addView(chev, LayoutHelper.createLinear(menuRowChevronDp, menuRowChevronDp, 0f, Gravity.CENTER_VERTICAL))

        row.setOnClickListener { onPress.run() }
        row.isClickable = true
        return row
    }
}
