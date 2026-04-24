package com.mezon.mobile.home.profile

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.session.LocaleManager

class LanguageSettingFragment : BaseFragment() {

    private lateinit var userController: UserController
    private val languages = listOf(
        LocaleManager.ENGLISH,
        LocaleManager.VIETNAMESE
    )

    override fun onInject(entryPoint: FragmentEntryPoint) {
        userController = entryPoint.userController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        observe(NotificationCenter.languageChanged) { _, _, _ ->
            if (fragmentView == null) return@observe
            rebuildList()
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView == null) return@observe
            fragmentView?.setBackgroundColor(themeColors.background)
            rebuildList()
        }
        return true
    }

    private lateinit var listContainer: LinearLayout

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(8), LayoutHelper.dp(16), LayoutHelper.dp(16))
        }

        listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(themeColors.getColor(ThemeColors.key_sheetItemBackground))
            }
        }
        root.addView(listContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        rebuildList()
        return wrapWithActionBar(getString(R.string.setting_language_title), root)
    }

    private fun rebuildList() {
        if (!::listContainer.isInitialized) return
        listContainer.removeAllViews()
        val currentTag = userController.languageTag.ifEmpty { LocaleManager.ENGLISH }

        languages.forEachIndexed { index, tag ->
            val label = when (tag) {
                LocaleManager.ENGLISH -> getString(R.string.setting_language_english)
                LocaleManager.VIETNAMESE -> getString(R.string.setting_language_vietnamese)
                else -> tag
            }
            val isSelected = tag == currentTag
            val showDivider = index < languages.lastIndex

            val rowWrapper = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
            }

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    LayoutHelper.dp(16), LayoutHelper.dp(14),
                    LayoutHelper.dp(16), LayoutHelper.dp(14)
                )
                isClickable = true
                isFocusable = true
                val outValue = android.util.TypedValue()
                requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                foreground = androidx.core.content.ContextCompat.getDrawable(requireContext(), outValue.resourceId)
                setOnClickListener {
                    if (tag != userController.languageTag) {
                        userController.applyLanguage(tag)
                    }
                }
            }

            val nameView = TextView(requireContext()).apply {
                text = label
                textSize = 16f
                setTextColor(themeColors.onSurface)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(nameView)

            if (isSelected) {
                val checkIcon = ImageView(requireContext()).apply {
                    setImageResource(R.drawable.ic_checkmark_small_icon)
                    colorFilter = PorterDuffColorFilter(themeColors.primary, PorterDuff.Mode.SRC_IN)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
                row.addView(checkIcon, LinearLayout.LayoutParams(LayoutHelper.dp(20), LayoutHelper.dp(20)))
            }

            rowWrapper.addView(row)

            if (showDivider) {
                val divider = View(requireContext()).apply {
                    setBackgroundColor(themeColors.getColor(ThemeColors.key_divider))
                }
                rowWrapper.addView(divider, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(1)
                ).apply { leftMargin = LayoutHelper.dp(16) })
            }

            listContainer.addView(rowWrapper, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }
}
