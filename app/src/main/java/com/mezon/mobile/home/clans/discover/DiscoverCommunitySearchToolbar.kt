package com.mezon.mobile.home.clans.discover

import com.mezon.mobile.ui.cells.withDefaultIconTint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

fun buildDiscoverCommunitySearchToolbar(
    context: Context,
    themeColors: ThemeColors,
    debounceHandler: Handler,
    onSearchCommitted: () -> Unit,
    onQrClick: () -> Unit,
    onAddFriendClick: () -> Unit
): Pair<LinearLayout, Runnable> {
    val wrap = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            LayoutHelper.dp(12),
            LayoutHelper.dp(8),
            LayoutHelper.dp(12),
            LayoutHelper.dp(10)
        )
    }
    val navBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val searchWrap = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val innerPad = LayoutHelper.dp(12)
        setPadding(innerPad, 0, innerPad, 0)
        background = GradientDrawable().apply {
            setColor(themeColors.secondaryLight)
            cornerRadius = LayoutHelper.dp(12f).toFloat()
            setStroke(LayoutHelper.dp(1), themeColors.outlineVariant)
        }
        layoutParams = LinearLayout.LayoutParams(0, LayoutHelper.dp(40), 1f)
    }
    searchWrap.addView(ImageView(context).apply {
        setImageDrawable(MezonIcon.loadDrawable(context, R.drawable.mz_search_24, ThemeColors.defaultIconTint))
        val iconSize = LayoutHelper.dp(18)
        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
            rightMargin = LayoutHelper.dp(8)
        }
    })
    val edit = EditText(context).apply {
        hint = context.getString(R.string.discover_explore_communities)
        setHintTextColor(themeColors.onSurfaceVariant)
        setTextColor(themeColors.onSurface)
        textSize = 14f
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        setPadding(0, 0, 0, 0)
        setLineSpacing(0f, 1f)
        isSingleLine = true
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        layoutParams = LinearLayout.LayoutParams(0, LayoutHelper.MATCH_PARENT, 1f)
        setText(DiscoverFilterHolder.searchQuery)
    }
    val debounceRunnable = Runnable {
        DiscoverFilterHolder.searchQuery = edit.text?.toString().orEmpty()
        onSearchCommitted()
    }
    edit.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            debounceHandler.removeCallbacks(debounceRunnable)
            debounceHandler.postDelayed(debounceRunnable, 300)
        }
    })
    searchWrap.addView(edit)
    navBar.addView(searchWrap)

    fun iconButton(icon: Int): ImageView {
        return ImageView(context).apply {
            val circleBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.tertiary)
            }
            background = RippleDrawable(
                ColorStateList.valueOf(themeColors.onSurface and 0x1AFFFFFF),
                circleBg,
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFFFFFFF.toInt())
                }
            )
            setImageDrawable(MezonIcon.loadDrawable(context, icon).withDefaultIconTint(context, icon, ThemeColors.defaultIconTint))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = LayoutHelper.dp(8)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LayoutHelper.dp(32), LayoutHelper.dp(32)).apply {
                leftMargin = LayoutHelper.dp(8)
            }
        }
    }
    iconButton(R.drawable.mz_qr_24).apply {
        setOnClickListener { onQrClick() }
        navBar.addView(this)
    }
    iconButton(R.drawable.mz_user_plus_24).apply {
        setOnClickListener { onAddFriendClick() }
        navBar.addView(this)
    }
    wrap.addView(navBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
    return wrap to debounceRunnable
}
