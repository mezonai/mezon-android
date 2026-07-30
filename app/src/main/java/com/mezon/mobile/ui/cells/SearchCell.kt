package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class SearchCell(context: Context, private val theme: ThemeColors) : LinearLayout(context) {

    lateinit var editText: EditText
        private set
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
            minimumHeight = LayoutHelper.dp(40)
            background = GradientDrawable().apply {
                setColor(theme.surfaceVariant)
                cornerRadius = LayoutHelper.dpf(50f)
            }
            val padH = LayoutHelper.dp(15)
            setPadding(padH, 0, padH, 0)
        }

        searchIcon = ImageView(context).apply {
            setImageDrawable(MezonIcon.searchIcon.getDrawable(context))
        }
        barContainer.addView(searchIcon, LayoutHelper.createLinear(20, 20, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 10f, 0f))

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

        editText = object : EditText(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                val result = super.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP) {
                    AndroidUtilities.showKeyboard(this)
                }
                return result
            }
        }.apply {
            setTextColor(theme.onSurface)
            setHintTextColor(theme.onSurfaceVariant)
            hint = context.getString(R.string.common_search)
            textSize = 14f
            background = null
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        barContainer.addView(editText, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f))

        clearButton = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(theme.onSurfaceVariant)
            visibility = View.GONE
            setOnClickListener {
                editText.text?.clear()
            }
        }
        barContainer.addView(clearButton, LayoutHelper.createLinear(18, 18, 0f, Gravity.CENTER_VERTICAL, 8f, 0f, 0f, 0f))

        barContainer.setOnClickListener {
            editText.requestFocus()
            AndroidUtilities.showKeyboard(editText)
        }
        addView(barContainer, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        cancelButton = TextView(context).apply {
            text = context.getString(R.string.common_cancel)
            setTextColor(theme.primary)
            textSize = 14f
            visibility = View.GONE
            setOnClickListener {
                editText.text?.clear()
                onCancelClick?.invoke()
            }
        }
        addView(cancelButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 15f, 0f, 0f, 0f))

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        badgeView.maxWidth = (w * BADGE_MAX_WIDTH_RATIO).toInt()
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

    fun focusInput() {
        editText.post {
            editText.requestFocus()
            AndroidUtilities.showKeyboard(editText)
        }
    }

    companion object {
        private const val BADGE_MAX_WIDTH_RATIO = 0.3f
    }
}
