package com.mezon.mobile.home.clans.settings

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class RoleColorPickerBottomSheet(
    context: Context,
    private val theme: ThemeColors,
    private val palette: List<String>,
    initialDraftHexNoHash: String,
    private val onSave: (String) -> Unit,
) : BottomSheet(context, needFocusable = true) {

    private val initialNormalized = normalizeHex(initialDraftHexNoHash)
    private var selectedNormalized: String = initialNormalized
    private val cellByHex = LinkedHashMap<String, TextView>()
    private lateinit var saveBtn: TextView

    companion object {
        fun normalizeHex(raw: String): String {
            return raw.trim().removePrefix("#").lowercase()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fixNavigationBar(theme.surface)
    }

    init {
        val screenH = AndroidUtilities.displaySize.y
        val minH = (screenH * 0.5f).toInt().coerceAtLeast(LayoutHelper.dp(360f))

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = minH
            setBackgroundColor(theme.surface)
            setPadding(LayoutHelper.dp(20f), LayoutHelper.dp(8f), LayoutHelper.dp(20f), LayoutHelper.dp(20f))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(LayoutHelper.dp(60f), LayoutHelper.dp(40f)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
            },
        )
        header.addView(
            TextView(context).apply {
                text = context.getString(R.string.clan_roles_color_picker_title)
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(theme.textStrong)
                gravity = Gravity.CENTER
            },
            LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL)
        )
        saveBtn = TextView(context).apply {
            text = context.getString(R.string.clan_roles_color_save)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minimumWidth = LayoutHelper.dp(60f)
            setPadding(LayoutHelper.dp(10f), LayoutHelper.dp(6f), LayoutHelper.dp(10f), LayoutHelper.dp(6f))
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                onSave(selectedNormalized)
                dismiss()
            }
        }
        header.addView(saveBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL))
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 16f))

        val gridHolder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(LayoutHelper.dp(20f), LayoutHelper.dp(10f), LayoutHelper.dp(20f), LayoutHelper.dp(10f))
        }
        val gap = LayoutHelper.dp(10f)
        val cellPx = LayoutHelper.dp(40f)
        var row: LinearLayout? = null
        palette.forEachIndexed { i, hexRaw ->
            if (i % 5 == 0) {
                row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                gridHolder.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL))
            }
            val normalized = normalizeHex(hexRaw)
            val cell = FrameLayout(context).apply {
                val lp = LinearLayout.LayoutParams(cellPx, cellPx).apply {
                    leftMargin = gap
                    topMargin = gap
                    rightMargin = gap
                    bottomMargin = gap
                }
                layoutParams = lp
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(parseHexSafe(hexRaw))
                }
                isClickable = true
                setOnClickListener {
                    selectedNormalized = normalized
                    syncChecks()
                    refreshSaveEnabled()
                }
            }
            val check = TextView(context).apply {
                text = "✓"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
            }
            cell.addView(check, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER))
            row?.addView(cell)
            cellByHex[normalized] = check
        }
        syncChecks()
        root.addView(gridHolder, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val resetBtn = TextView(context).apply {
            text = context.getString(R.string.clan_roles_color_reset)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(LayoutHelper.dp(10f), LayoutHelper.dp(6f), LayoutHelper.dp(10f), LayoutHelper.dp(6f))
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(20f)
                setColor(theme.surfaceVariant)
            }
            setOnClickListener {
                selectedNormalized = ""
                syncChecks()
                refreshSaveEnabled()
            }
        }
        footer.addView(resetBtn, LayoutHelper.createLinear(80, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL, 0f, 20f, 0f, 0f))
        root.addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        refreshSaveEnabled()
        setCustomView(root)
    }

    private fun parseHexSafe(hexWithMaybeHash: String): Int {
        val h = hexWithMaybeHash.trim().removePrefix("#")
        return runCatching { Color.parseColor("#$h") }.getOrElse { Color.parseColor("#99aab5") }
    }

    private fun syncChecks() {
        cellByHex.forEach { (hex, tv) ->
            tv.visibility = if (hex.isNotEmpty() && hex == selectedNormalized) View.VISIBLE else View.GONE
        }
    }

    private fun refreshSaveEnabled() {
        val changed = selectedNormalized != initialNormalized
        saveBtn.isEnabled = changed
        saveBtn.background = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dpf(20f)
            setColor(if (changed) theme.blurple else theme.surfaceVariant)
        }
        saveBtn.alpha = if (changed) 1f else 0.85f
    }
}
