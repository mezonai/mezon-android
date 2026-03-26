package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class SearchCell(context: Context, private val theme: ThemeColors) : LinearLayout(context) {

    val editText: EditText
    private val clearButton: ImageView
    private val cancelButton: TextView
    private val searchIcon: ImageView
    private val badgeView: TextView
    private val barContainer: LinearLayout
    var onTextChanged: ((String) -> Unit)? = null
    var onCancelClick: (() -> Unit)? = null
    var onBadgeRemoved: (() -> Unit)? = null
    var showCancel = false
        set(value) {
            field = value
            cancelButton.visibility = if (value) View.VISIBLE else View.GONE
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        barContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(theme.surfaceVariant)
                cornerRadius = LayoutHelper.dpf(20f)
            }
            val padH = LayoutHelper.dp(12)
            setPadding(padH, LayoutHelper.dp(8), padH, LayoutHelper.dp(8))
        }

        searchIcon = ImageView(context).apply {
            val drawable = MezonIcon.searchIcon.getDrawable(context)
            drawable.colorFilter = android.graphics.PorterDuffColorFilter(theme.onSurface, android.graphics.PorterDuff.Mode.SRC_IN)
            setImageDrawable(drawable)
        }
        barContainer.addView(searchIcon, LayoutHelper.createLinear(20, 20, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 8f, 0f))

        badgeView = TextView(context).apply {
            textSize = 12f
            setTextColor(theme.onPrimary)
            background = GradientDrawable().apply {
                setColor(theme.blurple)
                cornerRadius = LayoutHelper.dpf(10f)
            }
            val padH = LayoutHelper.dp(8)
            val padV = LayoutHelper.dp(2)
            setPadding(padH, padV, padH, padV)
            visibility = View.GONE
            isSingleLine = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        barContainer.addView(badgeView, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f,
            Gravity.CENTER_VERTICAL, 0f, 0f, 6f, 0f
        ))

        editText = EditText(context).apply {
            setTextColor(theme.onSurface)
            setHintTextColor(theme.onSurfaceVariant)
            hint = "Search"
            textSize = 15f
            background = null
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        barContainer.addView(editText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        clearButton = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(theme.onSurfaceVariant)
            visibility = View.GONE
            setOnClickListener {
                editText.text?.clear()
            }
        }
        barContainer.addView(clearButton, LayoutHelper.createLinear(18, 18, 0f, Gravity.CENTER_VERTICAL, 8f, 0f, 0f, 0f))

        addView(barContainer, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        cancelButton = TextView(context).apply {
            text = "Cancel"
            setTextColor(theme.primary)
            textSize = 14f
            visibility = View.GONE
            setOnClickListener {
                editText.text?.clear()
                onCancelClick?.invoke()
            }
        }
        addView(cancelButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 12f, 0f, 0f, 0f))

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                clearButton.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
                onTextChanged?.invoke(text)
            }
        })

        editText.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_DEL
                && event.action == android.view.KeyEvent.ACTION_DOWN
                && editText.text.isNullOrEmpty()
                && badgeView.visibility == View.VISIBLE
            ) {
                removeBadge()
                onBadgeRemoved?.invoke()
                true
            } else false
        }
    }

    fun setPlaceholder(text: String) {
        editText.hint = text
    }

    fun getText(): String = editText.text?.toString() ?: ""

    fun setBadge(text: String) {
        badgeView.text = text
        badgeView.visibility = View.VISIBLE
    }

    fun removeBadge() {
        badgeView.text = ""
        badgeView.visibility = View.GONE
    }

    fun hasBadge(): Boolean = badgeView.visibility == View.VISIBLE
}
