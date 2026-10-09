package com.mezon.mobile.home.profile

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.mezon.mobile.R
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.network.RealtimeServerChoice

class ServerChoiceBottomSheet(
    context: Context,
    private val currentChoice: RealtimeServerChoice,
    private val regionInUse: String?,
    private val onSelect: (RealtimeServerChoice) -> Unit
) : BottomSheet(context) {

    private val themeColors = ThemeColors.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = LayoutHelper.dp(16)
            setPadding(pad, pad, pad, pad)
        }

        val titleText = TextView(context).apply {
            text = context.getString(R.string.profile_server)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(themeColors.onSurface)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(titleText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = LayoutHelper.dp(24) })

        val optionsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(themeColors.getColor(ThemeColors.key_sheetItemBackground))
            }
            setPadding(LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8))
        }
        root.addView(optionsContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val choices = RealtimeServerChoice.entries
        choices.forEachIndexed { index, choice ->
            optionsContainer.addView(createOptionRow(choice))
            if (index < choices.lastIndex) {
                val separator = View(context).apply {
                    setBackgroundColor(themeColors.dividerColor)
                }
                optionsContainer.addView(separator, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(0.5f)
                ).apply {
                    leftMargin = -LayoutHelper.dp(8)
                    rightMargin = -LayoutHelper.dp(8)
                })
            }
        }

        val footerText = TextView(context).apply {
            text = context.getString(R.string.profile_server_footer)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(themeColors.onSurfaceVariant)
            setPadding(LayoutHelper.dp(4), 0, LayoutHelper.dp(4), 0)
        }
        root.addView(footerText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(12) })

        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        setCustomView(scrollView)
        setApplyTopPadding(true)
        setApplyBottomPadding(true)
        super.onCreate(savedInstanceState)
    }

    private fun createOptionRow(choice: RealtimeServerChoice): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12))
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = context.getDrawable(outValue.resourceId)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onSelect(choice)
                dismiss()
            }
        }

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        row.addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val titleView = TextView(context).apply {
            text = choice.regionName ?: context.getString(R.string.profile_server_auto)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(themeColors.onSurface)
            typeface = Typeface.DEFAULT_BOLD
        }
        textColumn.addView(titleView)

        val subtitleView = TextView(context).apply {
            text = subtitleOf(choice)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(themeColors.onSurfaceVariant)
        }
        textColumn.addView(subtitleView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(2) })

        row.addView(createRadio(choice == currentChoice), LinearLayout.LayoutParams(
            LayoutHelper.dp(20), LayoutHelper.dp(20)
        ).apply { leftMargin = LayoutHelper.dp(12) })
        return row
    }

    private fun createRadio(selected: Boolean): View = FrameLayout(context).apply {
        val colorSelected = ContextCompat.getColor(context, R.color.checkbox_selected)
        val colorUnselected = ContextCompat.getColor(context, R.color.checkbox_unselected)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(LayoutHelper.dp(2f), if (selected) colorSelected else colorUnselected)
        }
        if (selected) {
            val innerCircle = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorSelected)
                }
            }
            addView(innerCircle, FrameLayout.LayoutParams(LayoutHelper.dp(12), LayoutHelper.dp(12), Gravity.CENTER))
        }
    }

    private fun subtitleOf(choice: RealtimeServerChoice): String = when (choice) {
        RealtimeServerChoice.AUTO ->
            if (currentChoice == RealtimeServerChoice.AUTO && regionInUse != null) {
                context.getString(R.string.profile_server_in_use, regionInUse)
            } else {
                context.getString(R.string.profile_server_auto_hint)
            }
        RealtimeServerChoice.VN1, RealtimeServerChoice.VN2 -> context.getString(R.string.profile_server_vietnam)
        RealtimeServerChoice.US -> context.getString(R.string.profile_server_united_states)
    }
}
