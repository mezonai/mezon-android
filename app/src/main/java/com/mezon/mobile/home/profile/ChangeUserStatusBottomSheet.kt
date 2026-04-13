package com.mezon.mobile.home.profile

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class ChangeUserStatusBottomSheet(
    context: Context,
    private val currentStatus: String,
    private val customStatus: String,
    private val onStatusSelected: (String) -> Unit,
    private val onCustomStatusSelected: () -> Unit,
    private val onClearCustomStatus: () -> Unit
) : BottomSheet(context) {

    private val themeColors = ThemeColors.instance

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = LayoutHelper.dp(16)
            setPadding(pad, pad, pad, pad)
        }

        val titleText = TextView(context).apply {
            text = context.getString(R.string.status_change_online_status)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(themeColors.onSurface)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(titleText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = LayoutHelper.dp(24) })

        val statusLabel = TextView(context).apply {
            text = context.getString(R.string.status_online_status)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurfaceVariant)
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(statusLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = LayoutHelper.dp(12) })

        val optionsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(themeColors.surfaceVariant)
            }
            background = bg
            setPadding(LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8))
        }
        root.addView(optionsContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        fun createOptionRow(label: String, value: String, icon: MezonIcon): View {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(LayoutHelper.dp(12), LayoutHelper.dp(18), LayoutHelper.dp(12), LayoutHelper.dp(18))
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                foreground = context.getDrawable(outValue.resourceId)
                isClickable = true
                isFocusable = true
            }

            val iconView = ImageView(context).apply {
                setImageResource(icon.resId)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            row.addView(iconView, LinearLayout.LayoutParams(LayoutHelper.dp(20), LayoutHelper.dp(20)))

            val labelView = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(themeColors.onSurface)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(LayoutHelper.dp(12), 0, 0, 0)
            }
            row.addView(labelView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val checkbox = FrameLayout(context).apply {
                val normalizedCurrent = currentStatus.lowercase().replace("_", " ")
                val normalizedValue = value.lowercase().replace("_", " ")
                val isSelected = normalizedValue == normalizedCurrent
                val colorSelected = ContextCompat.getColor(context, R.color.checkbox_selected)
                val colorUnselected = ContextCompat.getColor(context, R.color.checkbox_unselected)
                val outerColor = if (isSelected) colorSelected else colorUnselected
                val outerBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(LayoutHelper.dp(2f).toInt(), outerColor)
                }
                background = outerBg
                if (isSelected) {
                    val innerCircle = View(context).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(colorSelected)
                        }
                    }
                    addView(innerCircle, FrameLayout.LayoutParams(LayoutHelper.dp(12), LayoutHelper.dp(12), Gravity.CENTER))
                }
            }
            row.addView(checkbox, LinearLayout.LayoutParams(LayoutHelper.dp(20), LayoutHelper.dp(20)))

            row.setOnClickListener {
                onStatusSelected(value)
                dismiss()
            }
            return row
        }

        fun addOptionWithSeparator(label: String, value: String, icon: MezonIcon, isLast: Boolean = false) {
            optionsContainer.addView(createOptionRow(label, value, icon))
            if (!isLast) {
                val separator = View(context).apply {
                    setBackgroundColor(themeColors.dividerColor)
                }
                optionsContainer.addView(separator, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(0.5f)).apply {
                    leftMargin = -LayoutHelper.dp(8)
                    rightMargin = -LayoutHelper.dp(8)
                })
            }
        }

        addOptionWithSeparator(context.getString(R.string.status_online), "Online", MezonIcon.onlineStatusIcon)
        addOptionWithSeparator(context.getString(R.string.status_idle), "Idle", MezonIcon.idleStatusIcon)
        addOptionWithSeparator(context.getString(R.string.status_do_not_disturb), "Do Not Disturb", MezonIcon.disturbStatusIcon)
        addOptionWithSeparator(context.getString(R.string.status_invisible), "Invisible", MezonIcon.offlineStatusIcon, isLast = true)

        val customStatusBtn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(themeColors.surfaceVariant)
            }
            background = bg
            setPadding(LayoutHelper.dp(20), LayoutHelper.dp(16), LayoutHelper.dp(20), LayoutHelper.dp(16))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onCustomStatusSelected()
                dismiss()
            }
        }

        val smileIcon = ImageView(context).apply {
            setImageResource(MezonIcon.faceIcon.resId)
            setColorFilter(themeColors.onSurfaceVariant)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        customStatusBtn.addView(smileIcon, LinearLayout.LayoutParams(LayoutHelper.dp(24), LayoutHelper.dp(24)).apply { rightMargin = LayoutHelper.dp(12) })

        val customLabel = TextView(context).apply {
            text = customStatus.ifEmpty { context.getString(R.string.status_set_custom_status) }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(themeColors.onSurface)
            typeface = Typeface.DEFAULT_BOLD
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 1
        }
        customStatusBtn.addView(customLabel, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f 
        ))

        if (customStatus.isNotEmpty()) {
            val clearIcon = ImageView(context).apply {
                tag = "clear_status_icon"
                setImageResource(MezonIcon.closeIcon.resId)
                setColorFilter(themeColors.onSurfaceVariant)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                isClickable = true
                isFocusable = true
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
                background = context.getDrawable(outValue.resourceId)
                setOnClickListener {
                    onClearCustomStatus()
                    dismiss()
                }
            }
            customStatusBtn.addView(clearIcon, LinearLayout.LayoutParams(LayoutHelper.dp(24), LayoutHelper.dp(24)).apply { leftMargin = LayoutHelper.dp(8) })
        }

        root.addView(customStatusBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(24) })

        val scrollView = android.widget.ScrollView(context).apply {
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
}
