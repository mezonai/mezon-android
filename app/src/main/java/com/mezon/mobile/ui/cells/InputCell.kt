package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.TextViewCompat
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class InputCell(context: Context, private val theme: ThemeColors) : LinearLayout(context) {

    private val labelView: TextView
    private val requiredMark: TextView
    val editText: EditText
    private val errorView: TextView
    private val clearButton: ImageView
    private val charCountView: TextView
    private val inputContainer: FrameLayout
    private val bgDrawable: GradientDrawable
    private var strokeWhenValid: Int? = null
    private var maxCharacter = 200
    private var isTextarea = false
    private var showCharacterCount = false
    var onTextChanged: ((String) -> Unit)? = null

    init {
        orientation = VERTICAL

        labelView = TextView(context).apply {
            setTextColor(theme.onSurfaceVariant)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            visibility = View.GONE
        }
        addView(labelView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 4f))

        requiredMark = TextView(context).apply {
            text = " *"
            setTextColor(theme.error)
            textSize = 14f
            visibility = View.GONE
        }

        bgDrawable = GradientDrawable().apply {
            setColor(theme.surfaceVariant)
            cornerRadius = LayoutHelper.dpf(12f)
            setStroke(LayoutHelper.dp(1), theme.outlineVariant)
        }.mutate() as GradientDrawable

        inputContainer = FrameLayout(context).apply {
            background = bgDrawable
            val pad = LayoutHelper.dp(12)
            setPadding(pad, pad, pad, pad)
        }

        editText = EditText(context).apply {
            setTextColor(theme.onSurface)
            setHintTextColor(theme.onSurfaceVariant and 0x80FFFFFF.toInt())
            textSize = 15f
            background = null
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        inputContainer.addView(editText, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL,
            rightMargin = 32f
        ))

        clearButton = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(theme.onSurfaceVariant)
            visibility = View.GONE
            setOnClickListener {
                editText.text?.clear()
            }
        }
        inputContainer.addView(clearButton, LayoutHelper.createFrame(
            20, 20, Gravity.CENTER_VERTICAL or Gravity.END
        ))

        addView(inputContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        charCountView = TextView(context).apply {
            setTextColor(theme.onSurfaceVariant)
            textSize = 11f
            gravity = Gravity.END
            visibility = View.GONE
        }
        addView(charCountView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.END, 0f, 2f, 0f, 0f))

        errorView = TextView(context).apply {
            setTextColor(theme.error)
            textSize = 12f
            visibility = View.GONE
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        addView(errorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 0f))

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                if (text.length > maxCharacter) {
                    editText.setText(text.substring(0, maxCharacter))
                    editText.setSelection(maxCharacter)
                    return
                }
                clearButton.visibility = if (text.isNotEmpty() && !isTextarea) View.VISIBLE else View.GONE
                if (isTextarea || showCharacterCount) charCountView.text = "${text.length}/$maxCharacter"
                onTextChanged?.invoke(text)
            }
        })
    }

    fun setLabel(label: String?, uppercase: Boolean = false, required: Boolean = false) {
        if (label != null) {
            labelView.text = if (uppercase) label.uppercase() else label
            labelView.visibility = View.VISIBLE
            requiredMark.visibility = if (required) View.VISIBLE else View.GONE
        } else {
            labelView.visibility = View.GONE
        }
    }

    fun setHint(hint: String) {
        editText.hint = hint
    }

    fun setText(text: String) {
        editText.setText(text)
    }

    fun getText(): String = editText.text?.toString() ?: ""

    fun setTextarea(textarea: Boolean, maxChars: Int = 200) {
        isTextarea = textarea
        maxCharacter = maxChars
        if (textarea) {
            editText.isSingleLine = false
            editText.maxLines = 6
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            editText.minHeight = LayoutHelper.dp(80)
            charCountView.visibility = View.VISIBLE
            charCountView.text = "0/$maxChars"
        }
    }

    fun setMaxCharacter(max: Int) {
        maxCharacter = max
        if (isTextarea || showCharacterCount) {
            charCountView.text = "${getText().length}/$maxCharacter"
        }
    }

    fun setShowCharacterCount(show: Boolean) {
        showCharacterCount = show
        charCountView.visibility = if (show || isTextarea) View.VISIBLE else View.GONE
        charCountView.text = "${getText().length}/$maxCharacter"
    }

    fun setInputContainerMinHeightDp(heightDp: Int) {
        inputContainer.minimumHeight = LayoutHelper.dp(heightDp)
        requestLayout()
    }


    fun setError(message: String?) {
        if (message != null) {
            errorView.text = message
            errorView.visibility = View.VISIBLE
            val iconPx = LayoutHelper.dp(16)
            val icon = AppCompatResources.getDrawable(context, R.drawable.ic_circle_information)
                ?.mutate()
                ?.also {
                    it.setBounds(0, 0, iconPx, iconPx)
                    it.setTint(theme.error)
                    it.setTintMode(PorterDuff.Mode.SRC_IN)
                }
            TextViewCompat.setCompoundDrawablesRelative(errorView, icon, null, null, null)
            errorView.compoundDrawablePadding = LayoutHelper.dp(6)
            errorView.setTypeface(Typeface.DEFAULT, Typeface.ITALIC)
            bgDrawable.setStroke(LayoutHelper.dp(1), theme.error)
        } else {
            errorView.visibility = View.GONE
            TextViewCompat.setCompoundDrawablesRelative(errorView, null, null, null, null)
            errorView.compoundDrawablePadding = 0
            errorView.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            bgDrawable.setStroke(LayoutHelper.dp(1), strokeWhenValid ?: theme.outlineVariant)
        }
    }

    fun setCellBackgroundColor(color: Int) {
        bgDrawable.setColor(color)
    }

    fun setCellStrokeColor(color: Int) {
        bgDrawable.setStroke(LayoutHelper.dp(1), color)
    }

    fun setLightInputAppearance(
        fill: Int = 0xFFFFFFFF.toInt(),
        stroke: Int = 0xFFD1D5DB.toInt(),
        text: Int = 0xFF374151.toInt(),
        hint: Int = 0xFF9CA3AF.toInt(),
        clearIcon: Int = 0xFF6B7280.toInt()
    ) {
        strokeWhenValid = stroke
        bgDrawable.setColor(fill)
        bgDrawable.setStroke(LayoutHelper.dp(1), stroke)
        editText.setTextColor(text)
        editText.setHintTextColor(hint)
        clearButton.setColorFilter(clearIcon)
    }

    fun refreshThemeColors() {
        labelView.setTextColor(theme.onSurfaceVariant)
        requiredMark.setTextColor(theme.error)
        bgDrawable.setColor(theme.surfaceVariant)
        val stroke = if (errorView.visibility == View.VISIBLE) theme.error else (strokeWhenValid ?: theme.outlineVariant)
        bgDrawable.setStroke(LayoutHelper.dp(1), stroke)
        editText.setTextColor(theme.onSurface)
        editText.setHintTextColor(theme.onSurfaceVariant and 0x80FFFFFF.toInt())
        clearButton.setColorFilter(theme.onSurfaceVariant)
        charCountView.setTextColor(theme.onSurfaceVariant)
        errorView.setTextColor(theme.error)
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        editText.isEnabled = enabled
        editText.alpha = if (enabled) 1f else 0.5f
        inputContainer.alpha = if (enabled) 1f else 0.55f
        clearButton.isEnabled = enabled
        clearButton.visibility = if (enabled && getText().isNotEmpty() && !isTextarea) View.VISIBLE else View.GONE
    }
}
