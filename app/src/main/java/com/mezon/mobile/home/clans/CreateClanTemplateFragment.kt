package com.mezon.mobile.home.clans

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.ui.cells.MezonIcon

class CreateClanTemplateFragment : BaseFragment() {
    private lateinit var contentContainer: LinearLayout
    private var baseTopPadding = 0
    private var baseHorizontalPadding = 0
    private var baseBottomPadding = 0

    override fun createView(context: Context): View {
        val root = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                CreateClanRnUiTokens.screenGradientColors(themeColors)
            )
        }
        baseTopPadding = LayoutHelper.dp(20)
        baseHorizontalPadding = LayoutHelper.dp(20)
        baseBottomPadding = LayoutHelper.dp(20)
        contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(baseHorizontalPadding, baseTopPadding, baseHorizontalPadding, baseBottomPadding)
            clipChildren = false
            clipToPadding = false
        }
        contentContainer.addView(buildHeader(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(20)
        })
        contentContainer.addView(buildCreateOwnItem(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(15)
        })
        val templatesTitle = TextView(context).apply {
            text = getString(R.string.clan_template_start_from_template)
            setTextColor(CreateClanRnUiTokens.menuText(themeColors))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = true
        }
        contentContainer.addView(templatesTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(5)
        })
        for (template in CreateClanTemplates.templates) {
            contentContainer.addView(buildTemplateItem(context, template), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                bottomMargin = LayoutHelper.dp(5)
            })
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            addView(contentContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        root.clipChildren = false
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        applySafeAreaTop(AndroidUtilities.statusBarHeight)
        fragmentView = root
        return root
    }

    override fun onInsets(insets: Rect) {
        super.onInsets(insets)
        applySafeAreaTop(maxOf(insets.top, AndroidUtilities.statusBarHeight))
    }

    private fun applySafeAreaTop(topInset: Int) {
        if (!::contentContainer.isInitialized) return
        contentContainer.setPadding(
            baseHorizontalPadding,
            baseTopPadding + topInset,
            baseHorizontalPadding,
            baseBottomPadding
        )
    }

    private fun buildHeader(context: Context): View {
        val container = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val closeWrap = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            clipChildren = false
            val rippleMask = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
            }
            background = RippleDrawable(
                ColorStateList.valueOf(CreateClanRnUiTokens.menuText(themeColors) and 0x1AFFFFFF),
                ColorDrawable(Color.TRANSPARENT),
                rippleMask
            )
            setOnClickListener { finishFragment() }
        }
        closeWrap.addView(
            ImageView(context).apply {
                setImageDrawable(MezonIcon.closeIcon.getDrawable(context, CreateClanRnUiTokens.closeIcon(themeColors)))
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            },
            FrameLayout.LayoutParams(LayoutHelper.dp(28), LayoutHelper.dp(28), android.view.Gravity.CENTER)
        )
        container.addView(
            closeWrap,
            FrameLayout.LayoutParams(LayoutHelper.dp(44), LayoutHelper.dp(44), Gravity.TOP or Gravity.START).apply {
                marginStart = 0
                topMargin = -LayoutHelper.dp(8)
            }
        )
        val title = TextView(context).apply {
            text = getString(R.string.clan_template_title)
            setTextColor(CreateClanRnUiTokens.textStrong(themeColors))
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, 700, false)
            gravity = android.view.Gravity.CENTER
        }
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(10)
        })
        val description = TextView(context).apply {
            text = getString(R.string.clan_template_description)
            setTextColor(CreateClanRnUiTokens.textDisabled(themeColors))
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, 500, false)
            gravity = android.view.Gravity.CENTER
        }
        content.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        container.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return container
    }

    private fun buildCreateOwnItem(context: Context): View {
        val row = buildTemplateRow(context, getString(R.string.clan_template_create_my_own), R.drawable.ic_sparkle)
        row.setOnClickListener {
            val fragment = CreateClanCustomizeFragment()
            presentFragment(fragment)
        }
        return row
    }

    private fun buildTemplateItem(context: Context, template: ClanTemplateSpec): View {
        val row = buildTemplateRow(context, getString(template.titleResId), template.iconResId)
        row.setOnClickListener {
            val fragment = CreateClanCustomizeFragment().apply {
                selectedTemplate = template
            }
            presentFragment(fragment)
        }
        return row
    }

    private fun buildTemplateRow(context: Context, title: String, iconResId: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(10), LayoutHelper.dp(10), LayoutHelper.dp(10), LayoutHelper.dp(10))
            isClickable = true
            isFocusable = true
            val radius = LayoutHelper.dp(8).toFloat()
            val cardBg = GradientDrawable().apply {
                setColor(CreateClanRnUiTokens.menuItemBackground(themeColors))
                cornerRadius = radius
                setStroke(LayoutHelper.dp(1), CreateClanRnUiTokens.menuBorder(themeColors))
            }
            val rippleMask = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = radius
            }
            background = RippleDrawable(
                ColorStateList.valueOf(themeColors.onSurface and 0x1AFFFFFF),
                cardBg,
                rippleMask
            )
        }
        val icon = ImageView(context).apply {
            setImageResource(iconResId)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        row.addView(
            icon,
            LayoutHelper.createLinear(20, 20, 0f, Gravity.CENTER_VERTICAL)
        )
        val textWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, LayoutHelper.dp(20), LayoutHelper.dp(20), LayoutHelper.dp(20))
        }
        val label = TextView(context).apply {
            text = title
            setTextColor(CreateClanRnUiTokens.menuText(themeColors))
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            includeFontPadding = false
        }
        textWrap.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        row.addView(
            textWrap,
            LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 10f, 0f, 0f, 0f)
        )
        return row
    }
}
