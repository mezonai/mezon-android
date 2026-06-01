package com.mezon.mobile.home.chat.channelinfo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.messages.GroupAvatar
import com.mezon.mobile.home.messages.isDefaultGroupAvatarUrl
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon

class DmGroupEditBottomSheet(
    context: Context,
    private val themeColors: ThemeColors,
    private val initialName: String,
    initialAvatarUrl: String,
    private val onPickAvatar: () -> Unit,
    private val onSaveRequested: (trimmedName: String, changedName: String?, changedAvatar: String?) -> Unit
) : BottomSheet(context, needFocusable = true) {

    private val initialAvatar = normalizeAvatar(initialAvatarUrl)
    private var draftAvatar = initialAvatar
    private var previewBitmap: Bitmap? = null
    private var uploading = false
    private var saving = false

    private lateinit var avatarView: AvatarView
    private lateinit var avatarActionText: TextView
    private lateinit var avatarProgress: ProgressBar
    private lateinit var nameInput: EditText
    private lateinit var clearButton: ImageView
    private lateinit var saveButton: TextView
    private lateinit var nameCounterText: TextView

    init {
        containerHeight = (AndroidUtilities.displaySize.y * 0.9f).toInt()
        setCustomView(buildContent())
    }

    fun setDraftAvatar(url: String) {
        previewBitmap = null
        draftAvatar = normalizeAvatar(url)
        bindAvatar()
        updateSaveState()
    }

    fun setPreviewBitmap(bitmap: Bitmap?) {
        previewBitmap = bitmap
        bindAvatar()
    }

    fun setUploading(value: Boolean) {
        uploading = value
        avatarProgress.visibility = if (value) View.VISIBLE else View.GONE
        avatarView.alpha = if (value) 0.45f else 1f
        avatarActionText.isEnabled = !value && !saving
        updateSaveState()
    }

    fun setSaving(value: Boolean) {
        saving = value
        saveButton.text = context.getString(R.string.common_save)
        avatarActionText.isEnabled = !uploading && !value
        updateSaveState()
    }

    private fun buildContent(): View {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(20), LayoutHelper.dp(10), LayoutHelper.dp(20), LayoutHelper.dp(24))
            setBackgroundColor(themeColors.surface)
        }

        val title = TextView(context).apply {
            text = context.getString(R.string.dm_group_customize)
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        outer.addView(title, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT,
            LayoutHelper.WRAP_CONTENT
        ))

        outer.addView(buildAvatarArea(), LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT,
            LayoutHelper.WRAP_CONTENT
        ))

        val labelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(context).apply {
            text = context.getString(R.string.dm_group_name)
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        }
        labelRow.addView(label, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        nameCounterText = TextView(context).apply {
            setTextColor(themeColors.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            includeFontPadding = false
        }
        labelRow.addView(nameCounterText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        outer.addView(labelRow, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT,
            LayoutHelper.WRAP_CONTENT,
            bottomMargin = 10f
        ))

        outer.addView(buildInput(), LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT,
            LayoutHelper.WRAP_CONTENT
        ))

        saveButton = TextView(context).apply {
            text = context.getString(R.string.common_save)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            minHeight = LayoutHelper.dp(42)
            setPadding(0, LayoutHelper.dp(10), 0, LayoutHelper.dp(10))
            background = rounded(themeColors.blurple, 8f)
            setOnClickListener {
                val trimmed = nameInput.text?.toString().orEmpty().trim()
                val changedName = if (trimmed.isNotEmpty() && trimmed != initialName) trimmed else null
                val changedAvatar = if (draftAvatar != initialAvatar) draftAvatar else null
                onSaveRequested(trimmed, changedName, changedAvatar)
            }
        }
        outer.addView(saveButton, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT,
            LayoutHelper.WRAP_CONTENT,
            topMargin = 20f
        ))

        bindAvatar()
        updateClearButton()
        updateNameCounter()
        updateSaveState()
        return outer
    }

    private fun buildAvatarArea(): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, LayoutHelper.dp(20), 0, LayoutHelper.dp(20))
        }

        val avatarFrame = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (!uploading && !saving) onPickAvatar()
            }
        }
        avatarView = AvatarView(context).apply {
            setSizeDp(60)
            setInfo(0L, initialName)
        }
        avatarFrame.addView(avatarView, LayoutHelper.createFrame(60, 60, Gravity.CENTER))
        avatarProgress = ProgressBar(context).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        avatarFrame.addView(avatarProgress, LayoutHelper.createFrame(28, 28, Gravity.CENTER))
        container.addView(avatarFrame, LayoutHelper.createLinear(60, 60, gravity = Gravity.CENTER_HORIZONTAL))

        avatarActionText = TextView(context).apply {
            setTextColor(themeColors.textLink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(LayoutHelper.dp(8), LayoutHelper.dp(10), LayoutHelper.dp(8), 0)
            setOnClickListener {
                if (uploading || saving) return@setOnClickListener
                if (shouldShowUploadAction()) {
                    onPickAvatar()
                } else {
                    draftAvatar = ""
                    previewBitmap = null
                    bindAvatar()
                    updateSaveState()
                }
            }
        }
        container.addView(avatarActionText, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT,
            LayoutHelper.WRAP_CONTENT,
            gravity = Gravity.CENTER_HORIZONTAL
        ))

        return container
    }

    private fun buildInput(): View {
        val wrapper = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(themeColors.tertiary)
                setStroke(LayoutHelper.dp(1), themeColors.border)
                cornerRadius = LayoutHelper.dpf(10f)
            }
            setPadding(LayoutHelper.dp(4), 0, LayoutHelper.dp(4), 0)
            minimumHeight = LayoutHelper.dp(44)
        }

        nameInput = EditText(context).apply {
            setText(initialName)
            setSelection(text?.length ?: 0)
            setTextColor(themeColors.textStrong)
            setHintTextColor(themeColors.textDisabled)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = null
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            imeOptions = EditorInfo.IME_ACTION_DONE
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            minHeight = 0
            minimumHeight = 0
            filters = arrayOf(InputFilter.LengthFilter(64))
            setPadding(LayoutHelper.dp(10), 0, LayoutHelper.dp(38), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updateClearButton()
                    updateNameCounter()
                    updateSaveState()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        wrapper.addView(nameInput, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT,
            44,
            Gravity.CENTER_VERTICAL
        ))

        clearButton = ImageView(context).apply {
            val drawable = MezonIcon.circleXIcon.getDrawable(context)
            drawable.colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
            setImageDrawable(drawable)
            alpha = 0.85f
            setPadding(LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4))
            setOnClickListener {
                nameInput.setText("")
            }
        }
        wrapper.addView(clearButton, LayoutHelper.createFrame(26, 26, Gravity.END or Gravity.CENTER_VERTICAL, rightMargin = 8f))

        return wrapper
    }

    private fun bindAvatar() {
        avatarView.setInfo(0L, nameInputOrInitial())
        val preview = previewBitmap
        if (preview != null) {
            avatarView.setPhoto(preview)
        } else if (draftAvatar.isBlank()) {
            avatarView.setImageUrl(null)
            avatarView.setPhoto(GroupAvatar.bitmap(context))
        } else {
            avatarView.setImageUrl(draftAvatar)
        }
        avatarActionText.text = if (shouldShowUploadAction()) {
            context.getString(R.string.dm_group_upload_image)
        } else {
            context.getString(R.string.dm_group_remove_avatar)
        }
    }

    private fun updateClearButton() {
        if (!::clearButton.isInitialized) return
        clearButton.visibility = if (nameInput.text?.isNotEmpty() == true) View.VISIBLE else View.GONE
    }

    private fun updateSaveState() {
        if (!::saveButton.isInitialized) return
        val trimmed = nameInput.text?.toString().orEmpty().trim()
        val nameChanged = trimmed.isNotEmpty() && trimmed != initialName
        val avatarChanged = draftAvatar != initialAvatar
        val enabled = trimmed.isNotEmpty() && (nameChanged || avatarChanged) && !uploading && !saving
        saveButton.isEnabled = enabled
        saveButton.alpha = if (enabled) 1f else 0.5f
    }

    private fun updateNameCounter() {
        if (!::nameCounterText.isInitialized || !::nameInput.isInitialized) return
        val length = nameInput.text?.length ?: 0
        nameCounterText.text = context.getString(R.string.poll_question_counter, length, 64)
    }

    private fun shouldShowUploadAction(): Boolean =
        draftAvatar.isBlank() || isDefaultGroupAvatarUrl(draftAvatar)

    private fun nameInputOrInitial(): String =
        if (::nameInput.isInitialized) nameInput.text?.toString().orEmpty().ifBlank { initialName } else initialName

    private fun normalizeAvatar(url: String): String =
        if (isDefaultGroupAvatarUrl(url)) "" else url

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = LayoutHelper.dpf(radiusDp)
        }
}
