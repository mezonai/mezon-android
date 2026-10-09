package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.theme.ThemeMode

class QuickMenuPickerBottomSheet(
    context: Context,
    private val menuNames: List<String>,
    private val onSelect: (String) -> Unit
) : BottomSheet(context) {

    private val theme: ThemeColors = ThemeColors.instance
    private var selectionHandled = false

    private val groupColor: Int
        get() = when (theme.resolvedMode) {
            ThemeMode.LIGHT -> 0xFFFFFFFF.toInt()
            ThemeMode.ABYSS -> 0xFF19153C.toInt()
            else -> 0xFF1C1D23.toInt()
        }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, LayoutHelper.dp(16), 0, LayoutHelper.dp(20))
        }

        rootLayout.addView(
            buildHeader(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = LayoutHelper.dp(16)
                rightMargin = LayoutHelper.dp(16)
                bottomMargin = LayoutHelper.dp(12)
            }
        )
        rootLayout.addView(
            buildMenuGroup(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = LayoutHelper.dp(10)
                rightMargin = LayoutHelper.dp(10)
            }
        )

        scrollView.addView(rootLayout, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setCustomView(scrollView)

        super.onCreate(savedInstanceState)
        fixNavigationBar()
    }

    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        header.addView(TextView(context).apply {
            text = context.getString(R.string.action_quick_menu)
            setTextColor(theme.getColor(ThemeColors.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        header.addView(TextView(context).apply {
            text = context.getString(R.string.quick_menu_picker_subtitle)
            setTextColor(theme.textDisabled)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, LayoutHelper.dp(2), 0, 0)
        })
        return header
    }

    private fun buildMenuGroup(): LinearLayout {
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(groupColor)
                cornerRadius = LayoutHelper.dp(10).toFloat()
            }
            clipToOutline = true
        }
        for ((index, name) in menuNames.withIndex()) {
            group.addView(buildMenuRow(name))
            if (index < menuNames.size - 1) {
                group.addView(View(context).apply {
                    setBackgroundColor(theme.getColor(ThemeColors.key_divider))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { leftMargin = LayoutHelper.dp(54) }
                })
            }
        }
        return group
    }

    private fun buildMenuRow(name: String): FrameLayout {
        val row = FrameLayout(context).apply {
            setBackgroundColor(groupColor)
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = context.getDrawable(outValue.resourceId)
            isClickable = true
            isFocusable = true
        }

        val iconBg = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(ICON_BACKGROUND_COLOR)
                cornerRadius = LayoutHelper.dp(16).toFloat()
            }
        }
        iconBg.addView(
            ImageView(context).apply {
                setImageResource(MezonIcon.quickAction.resId)
                setColorFilter(ICON_TINT_COLOR)
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LayoutHelper.createFrame(16, 16, Gravity.CENTER)
        )
        row.addView(iconBg, LayoutHelper.createFrame(32, 32,
            Gravity.CENTER_VERTICAL or Gravity.START, 12f, 0f, 0f, 0f))

        row.addView(
            TextView(context).apply {
                text = name
                setTextColor(theme.getColor(ThemeColors.key_dialogTextBlack))
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                Gravity.CENTER_VERTICAL or Gravity.START, 54f, 0f, 16f, 0f
            )
        )

        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(52)
        )
        row.setOnClickListener {
            if (selectionHandled) return@setOnClickListener
            selectionHandled = true
            dismiss()
            onSelect(name)
        }
        return row
    }

    private companion object {
        const val ICON_BACKGROUND_COLOR = 0x2E3B82F6
        val ICON_TINT_COLOR = 0xFF60A5FA.toInt()
    }
}
