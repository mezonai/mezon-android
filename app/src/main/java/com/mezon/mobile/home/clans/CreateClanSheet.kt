package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

/**
 * "Tạo Clan của bạn" — full-screen fragment, presented via presentFragment().
 *
 * Callers set [onCreateCustom] / [onCreateFromTemplate] before presenting.
 */
class CreateClanFragment : BaseFragment() {

    companion object {
        const val TEMPLATE_CUSTOM = "custom"
        const val TEMPLATE_GAMING = "gaming"
        const val TEMPLATE_FRIENDS = "friends"
        const val TEMPLATE_STUDY = "study"
        const val TEMPLATE_SCHOOL_CLUB = "school_club"
        const val TEMPLATE_LOCAL_COMMUNITY = "local_community"
        const val TEMPLATE_ARTIST = "artist"
    }

    var onCreateCustom: (() -> Unit)? = null
    var onCreateFromTemplate: ((templateId: String) -> Unit)? = null

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        // ── Top bar (X + title) ──────────────────────────────────────────
        val topBar = FrameLayout(context).apply {
            val statusBarH = AndroidUtilities.statusBarHeight
            setPadding(LayoutHelper.dp(16), statusBarH + LayoutHelper.dp(12), LayoutHelper.dp(16), LayoutHelper.dp(8))
        }

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setOnClickListener { finishFragment() }
        }
        topBar.addView(closeBtn, FrameLayout.LayoutParams(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL
        ))

        val titleView = TextView(context).apply {
            text = context.getString(R.string.clan_create_title)
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        topBar.addView(titleView, FrameLayout.LayoutParams(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER
        ))

        root.addView(topBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        // ── Scrollable content ───────────────────────────────────────────
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(8), LayoutHelper.dp(16), LayoutHelper.dp(24))
        }

        // Subtitle
        content.addView(
            TextView(context).apply {
                text = context.getString(R.string.clan_create_subtitle)
                setTextColor(themeColors.onSurfaceVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setPadding(LayoutHelper.dp(8), LayoutHelper.dp(4), LayoutHelper.dp(8), LayoutHelper.dp(20))
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

        // "Tạo mẫu riêng" item
        content.addView(
            buildTemplateRow(context, "✨", context.getString(R.string.clan_template_custom), TEMPLATE_CUSTOM),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 8f)
        )

        // Section header
        content.addView(
            TextView(context).apply {
                text = context.getString(R.string.clan_create_from_template)
                setTextColor(themeColors.onSurfaceVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                setPadding(LayoutHelper.dp(4), LayoutHelper.dp(12), LayoutHelper.dp(4), LayoutHelper.dp(8))
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

        // Template rows
        val templates = listOf(
            Triple("🎮", context.getString(R.string.clan_template_gaming), TEMPLATE_GAMING),
            Triple("👫", context.getString(R.string.clan_template_friends), TEMPLATE_FRIENDS),
            Triple("📚", context.getString(R.string.clan_template_study), TEMPLATE_STUDY),
            Triple("🏫", context.getString(R.string.clan_template_school_club), TEMPLATE_SCHOOL_CLUB),
            Triple("🏘", context.getString(R.string.clan_template_local_community), TEMPLATE_LOCAL_COMMUNITY),
            Triple("🎨", context.getString(R.string.clan_template_artist), TEMPLATE_ARTIST),
        )
        templates.forEachIndexed { index, (emoji, label, id) ->
            val isLast = index == templates.lastIndex
            content.addView(
                buildTemplateRow(context, emoji, label, id),
                LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    0f, 0, 0f, 0f, 0f, if (isLast) 0f else 8f
                )
            )
        }

        val scrollView = ScrollView(context).apply {
            addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
        root.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        return root
    }

    private fun buildTemplateRow(
        context: Context,
        emoji: String,
        label: String,
        templateId: String
    ): View {
        val borderColor = themeColors.getColor(ThemeColors.key_sheetItemBackground)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(LayoutHelper.dp(1), borderColor)
                cornerRadius = LayoutHelper.dp(12).toFloat()
            }
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(16), LayoutHelper.dp(16), LayoutHelper.dp(16))
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = context.getDrawable(outValue.resourceId)
            setOnClickListener {
                finishFragment()
                if (templateId == TEMPLATE_CUSTOM) onCreateCustom?.invoke()
                else onCreateFromTemplate?.invoke(templateId)
            }
        }

        // Emoji
        row.addView(
            TextView(context).apply {
                text = emoji
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                gravity = Gravity.CENTER
            },
            LayoutHelper.createLinear(40, 40)
        )

        // Label
        row.addView(
            TextView(context).apply {
                text = label
                setTextColor(themeColors.onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(LayoutHelper.dp(12), 0, 0, 0)
            },
            LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f)
        )

        return row
    }
}
