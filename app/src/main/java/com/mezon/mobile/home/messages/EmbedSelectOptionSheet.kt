package com.mezon.mobile.home.messages

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.ActionButton
import com.mezon.mobile.ui.cells.SearchCell
import com.mezon.mobile.ui.cells.TextSettingsCell
import com.mezon.mobile.util.EmbedFormUtil
import com.mezon.mobile.util.EmbedSelectOptionSpec
import com.mezon.mobile.util.EmbedSelectSpec

object EmbedSelectOptionSheet {

    fun show(
        context: Context,
        theme: ThemeColors,
        title: CharSequence,
        spec: EmbedSelectSpec,
        messageId: Long,
        componentId: String,
        onInvalidate: () -> Unit,
        onSingleSelectionNotify: (String) -> Unit,
        onMultiValueAddedNotify: (String) -> Unit,
    ) {
        val act = AndroidUtilities.findActivity(context) ?: return
        AndroidUtilities.checkDisplaySize(act)
        val options = spec.options
        val sheetHeightPx = sheetHeightPx(act)

        val dialog = Dialog(act)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
        }

        val handle = View(act).apply {
            background = GradientDrawable().apply {
                setColor(theme.onSurface and 0x4DFFFFFF)
                cornerRadius = LayoutHelper.dpf(2f)
            }
        }
        val handleContainer = FrameLayout(act).apply {
            addView(handle, LayoutHelper.createFrame(40, 4, Gravity.CENTER, topMargin = 8f, bottomMargin = 8f))
        }
        root.addView(handleContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        if (title.isNotBlank()) {
            val padH = LayoutHelper.dp(16f)
            val titleView = TextView(act).apply {
                setText(title)
                setTextColor(theme.onSurface)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(padH, LayoutHelper.dp(4), padH, LayoutHelper.dp(8))
            }
            root.addView(
                titleView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
            )
        }

        val contentColumn = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(
            contentColumn,
            LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0).apply { weight = 1f },
        )

        val hPad = LayoutHelper.dp(16)
        contentColumn.setPadding(hPad, 0, hPad, LayoutHelper.dp(12))

        val search = SearchCell(act, theme).apply {
            setPlaceholder(act.getString(R.string.common_search_placeholder))
        }

        val optionsRoot = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = NestedScrollView(act).apply {
            isFillViewport = true
            clipToPadding = false
            addView(
                optionsRoot,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        scroll.layoutParams = LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0).apply { weight = 1f }

        fun optionDisplayLabel(opt: EmbedSelectOptionSpec): String =
            opt.label.ifEmpty { opt.value }

        fun matchesQuery(opt: EmbedSelectOptionSpec, query: String): Boolean {
            val q = query.trim()
            if (q.isEmpty()) return true
            return optionDisplayLabel(opt).contains(q, ignoreCase = true) ||
                opt.value.contains(q, ignoreCase = true)
        }

        fun buildOptionRows(query: String) {
            optionsRoot.removeAllViews()
            val filtered = options.filter { matchesQuery(it, query) }
            if (filtered.isEmpty()) {
                val empty = TextView(act).apply {
                    text = act.getString(R.string.embed_select_no_results)
                    setTextColor(theme.onSurfaceVariant)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    gravity = Gravity.CENTER
                    setPadding(0, LayoutHelper.dp(24), 0, LayoutHelper.dp(24))
                }
                optionsRoot.addView(
                    empty,
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
                )
                return
            }

            if (spec.isMulti) {
                filtered.forEachIndexed { index, opt ->
                    val row = LinearLayout(act).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        val ph = LayoutHelper.dp(12)
                        setPadding(ph, LayoutHelper.dp(10), ph, LayoutHelper.dp(10))
                    }
                    val tv = TextView(act).apply {
                        text = optionDisplayLabel(opt)
                        setSingleLine(false)
                        maxLines = 4
                        setTextColor(theme.onSurface)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    }
                    val cb = CheckBox(act)
                    row.addView(
                        tv,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    row.addView(cb, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    cb.isChecked = EmbedFormUtil.isValueSelected(messageId, componentId, opt.value)
                    val optVal = opt.value
                    var suppressCb = false
                    cb.setOnCheckedChangeListener { _, isChecked ->
                        if (suppressCb) return@setOnCheckedChangeListener
                        val has = EmbedFormUtil.isValueSelected(messageId, componentId, optVal)
                        if (isChecked && !has) {
                            val n = EmbedFormUtil.getValuesForComponent(messageId, componentId).size
                            if (n >= spec.maxPick) {
                                suppressCb = true
                                cb.isChecked = false
                                suppressCb = false
                                return@setOnCheckedChangeListener
                            }
                            EmbedFormUtil.toggleMultiValue(messageId, componentId, optVal)
                            onMultiValueAddedNotify(optVal)
                        } else if (!isChecked && has) {
                            EmbedFormUtil.toggleMultiValue(messageId, componentId, optVal)
                        }
                        onInvalidate()
                    }
                    val rip = TypedValue()
                    if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, rip, true)) {
                        row.foreground = ContextCompat.getDrawable(act, rip.resourceId)
                    }
                    row.isClickable = true
                    row.isFocusable = true
                    row.setOnClickListener { cb.performClick() }

                    optionsRoot.addView(
                        row,
                        LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
                    )
                    if (index < filtered.lastIndex) {
                        val div = View(act).apply { setBackgroundColor(theme.dividerColor) }
                        optionsRoot.addView(
                            div,
                            LayoutHelper.createLinear(
                                LayoutHelper.MATCH_PARENT,
                                1,
                                0f,
                                Gravity.START,
                                LayoutHelper.dp(16).toFloat(),
                                0f,
                                LayoutHelper.dp(16).toFloat(),
                                0f,
                            ),
                        )
                    }
                }
            } else {
                filtered.forEachIndexed { index, opt ->
                    val isLast = index == filtered.lastIndex
                    val cell = TextSettingsCell(act, theme).apply {
                        setTextAndValue(optionDisplayLabel(opt), divider = !isLast)
                        setCanClick(false)
                        setOnClickListener {
                            EmbedFormUtil.setValue(messageId, componentId, opt.value)
                            onSingleSelectionNotify(opt.value)
                            onInvalidate()
                            dialog.dismiss()
                        }
                    }
                    optionsRoot.addView(
                        cell,
                        LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
                    )
                }
            }
        }

        search.onTextChanged = { buildOptionRows(it) }

        contentColumn.addView(
            search,
            LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                0f,
                Gravity.START,
                0f,
                0f,
                0f,
                8f,
            ),
        )
        contentColumn.addView(scroll)

        buildOptionRows("")

        if (spec.isMulti) {
            val done = ActionButton(act, theme).apply {
                setText(act.getString(R.string.common_confirm))
                setOnClickListener {
                    dialog.dismiss()
                    onInvalidate()
                }
            }
            contentColumn.addView(
                done,
                LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT,
                    0f,
                    Gravity.CENTER_HORIZONTAL,
                    0f,
                    8f,
                    0f,
                    0f,
                ),
            )
        }

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, sheetHeightPx)
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private fun sheetHeightPx(context: Context): Int {
        val h = when {
            AndroidUtilities.displaySize.y > 0 -> AndroidUtilities.displaySize.y
            else -> context.resources.displayMetrics.heightPixels
        }
        return (h / 3f).toInt().coerceAtLeast(LayoutHelper.dp(160))
    }
}
