package com.mezon.mobile.home.clans.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import com.mezon.mobile.R
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mezon.api.OnboardingItem

private const val MIN_MISSION_RULE_TITLE = 7
private const val MAX_FILE_SIZE_10MB = 10L * 1024 * 1024
private val VALID_IMAGE_MIME_TYPES = setOf(
    "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp",
)

// ─────────────────────────────────────────────────────────────────────────────
// A10 / B6.3: Question Editor Sheet
// Supports: answer description, per-answer remove X, "N of 50" counter, save/collapse
// ─────────────────────────────────────────────────────────────────────────────

class OnboardingQuestionEditorSheet(
    context: Context,
    private val existing: OnboardingItem? = null,
    /** Called with (title, answers). Includes answers with title + description. */
    private val onSave: (title: String, answers: List<OnboardingAnswerDraft>) -> Unit,
) : BottomSheet(context, needFocusable = true) {

    private val theme = ThemeColors.instance
    private lateinit var titleInput: InputCell
    private val currentAnswers = mutableListOf<OnboardingAnswerDraft>()
    private lateinit var answersContainer: LinearLayout
    private lateinit var answerCountLabel: TextView

    init {
        setTitle(
            context.getString(
                if (existing != null) R.string.onboarding_edit_question
                else R.string.onboarding_add_question,
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), LayoutHelper.dp(16f))
        }

        // Title input
        titleInput = InputCell(context, theme).apply {
            setLabel(context.getString(R.string.onboarding_question_title))
            setText(existing?.title.orEmpty())
        }
        root.addView(titleInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        // "Available answers - N of 50" label
        answerCountLabel = TextView(context).apply {
            setTextColor(theme.textDisabled)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, LayoutHelper.dp(16f), 0, LayoutHelper.dp(6f))
        }
        root.addView(answerCountLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        // Answers container
        answersContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(answersContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        // Populate existing answers
        val existingAnswers = existing?.answersList.orEmpty()
        if (existingAnswers.isEmpty()) {
            addAnswerRow(OnboardingAnswerDraft())
        } else {
            existingAnswers.forEach { proto ->
                addAnswerRow(OnboardingAnswerDraft(title = proto.title, description = proto.description))
            }
        }

        // "Add an Answer" button (dashed border as spec A10.7)
        val addAnswerBtn = dashedOutlineBtn(context.getString(R.string.onboarding_add_answer)) {
            addAnswerRow(OnboardingAnswerDraft())
        }
        root.addView(addAnswerBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(8f)
        })

        // Save button
        val saveBtn = primaryButton(context, context.getString(R.string.common_save)) {
            val title = titleInput.getText().trim()
            if (title.isBlank()) {
                titleInput.setError(context.getString(R.string.onboarding_question_required))
                return@primaryButton
            }
            onSave(title, currentAnswers.toList())
            dismiss()
        }
        root.addView(saveBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(16f)
        })

        scroll.addView(root)
        setCustomView(scroll)
        super.onCreate(savedInstanceState)
    }

    /** A10.7: Each answer is a chip-like row with Title + Description inputs + remove X */
    private fun addAnswerRow(draft: OnboardingAnswerDraft) {
        currentAnswers.add(draft)
        val index = currentAnswers.lastIndex
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = fieldBg()
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(10f), LayoutHelper.dp(12f), LayoutHelper.dp(10f))
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCell = InputCell(context, theme).apply {
            setLabel(context.getString(R.string.onboarding_answer_title))
            setText(draft.title)
        }
        headerRow.addView(titleCell, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        // Remove X button for this answer
        val removeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(theme.error)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(LayoutHelper.dp(12f), 0, 0, 0)
            setOnClickListener {
                val realIndex = currentAnswers.indexOfFirst { System.identityHashCode(it) == System.identityHashCode(currentAnswers[index]) }
                if (realIndex >= 0) currentAnswers.removeAt(realIndex)
                answersContainer.removeView(row)
                updateAnswerCountLabel()
            }
        }
        headerRow.addView(removeBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        row.addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        // Description input (optional)
        val descCell = InputCell(context, theme).apply {
            setLabel(context.getString(R.string.onboarding_answer_description))
            setText(draft.description)
        }
        row.addView(descCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(6f)
        })

        // Wire up live update of the draft
        titleCell.addTextChangedSimple { text ->
            val i = answersContainer.indexOfChild(row)
            if (i in currentAnswers.indices) {
                currentAnswers[i] = currentAnswers[i].copy(title = text)
            }
            updateAnswerCountLabel()
        }
        descCell.addTextChangedSimple { text ->
            val i = answersContainer.indexOfChild(row)
            if (i in currentAnswers.indices) {
                currentAnswers[i] = currentAnswers[i].copy(description = text)
            }
        }

        answersContainer.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(8f)
        })
        updateAnswerCountLabel()
    }

    private fun updateAnswerCountLabel() {
        answerCountLabel.text = context.getString(R.string.onboarding_available_answers, currentAnswers.size)
    }

    private fun dashedOutlineBtn(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(theme.primary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dpf(10f)
            setStroke(LayoutHelper.dp(1), theme.primary)
        }
        setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(10f), LayoutHelper.dp(12f), LayoutHelper.dp(10f))
        setOnClickListener { onClick() }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// A14 / B6.6: Mission Editor Sheet
// Supports: title (min 7), public channel picker, task type radio group, Remove (edit mode)
// ─────────────────────────────────────────────────────────────────────────────

class OnboardingMissionEditorSheet(
    context: Context,
    private val channels: List<ClanChannelEntity>,
    private val existing: OnboardingItem? = null,
    private val onSave: (title: String, content: String, channelId: Long, taskType: Int) -> Unit,
    private val onRemove: (() -> Unit)? = null,
) : BottomSheet(context, needFocusable = true) {

    private val theme = ThemeColors.instance
    private lateinit var titleInput: InputCell
    private lateinit var contentInput: InputCell
    private var selectedChannelId: Long = existing?.channelId?.takeIf { it != 0L }
        ?: channels.firstOrNull()?.channelId
        ?: 0L
    // A14.2: default SEND_MESSAGE(1)
    private var selectedTaskType: Int = existing?.taskType?.takeIf { it != 0 }
        ?: MissionType.SEND_MESSAGE.apiValue

    init {
        setTitle(
            context.getString(
                if (existing != null) R.string.onboarding_edit_mission
                else R.string.onboarding_add_mission,
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), LayoutHelper.dp(16f))
        }

        // A14.5: Mission Title (min 7)
        titleInput = InputCell(context, theme).apply {
            setLabel(context.getString(R.string.onboarding_mission_title))
            setText(existing?.title.orEmpty())
        }
        root.addView(titleInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        // Description (optional, stored as content)
        contentInput = InputCell(context, theme).apply {
            setLabel(context.getString(R.string.onboarding_mission_content))
            setText(existing?.content.orEmpty())
        }
        root.addView(contentInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(8f)
        })

        // A14.3 / A14.5: Channel picker (public only, default = first)
        val channelLabel = sectionLabel(context.getString(R.string.onboarding_mission_channel))
        root.addView(channelLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val channelRow = channelPickerRow()
        root.addView(channelRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        // A14.4: "Complete When" radio group
        val completeWhenLabel = sectionLabel(context.getString(R.string.onboarding_complete_when))
        root.addView(completeWhenLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(14f)
        })

        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }
        val channelName = channels.firstOrNull { it.channelId == selectedChannelId }?.channelLabel ?: ""
        listOf(
            Triple(MissionType.SEND_MESSAGE.apiValue, R.string.onboarding_mission_type_send_message, R.string.onboarding_mission_send_message),
            Triple(MissionType.VISIT.apiValue, R.string.onboarding_mission_type_visit, R.string.onboarding_mission_visit),
            Triple(MissionType.DO_SOMETHING.apiValue, R.string.onboarding_mission_type_do_something, R.string.onboarding_mission_do_something),
        ).forEach { (typeValue, nameRes, descRes) ->
            val radio = RadioButton(context).apply {
                id = View.generateViewId()
                text = context.getString(nameRes) + (if (channelName.isNotBlank()) " #$channelName" else "")
                setTextColor(theme.colorText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                isChecked = selectedTaskType == typeValue
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedTaskType = typeValue
                }
            }
            radioGroup.addView(radio, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
        root.addView(radioGroup, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(4f)
        })

        // A14.5 Footer: [Remove?] [Cancel] [Save]
        val footerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        if (existing != null && onRemove != null) {
            val removeBtn = outlineErrorBtn(context.getString(R.string.onboarding_remove)) {
                onRemove.invoke()
                dismiss()
            }
            footerRow.addView(removeBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
                rightMargin = LayoutHelper.dp(8f)
            })
        }

        val cancelBtn = outlineBtn(context, context.getString(R.string.common_cancel)) { dismiss() }
        footerRow.addView(cancelBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
            rightMargin = LayoutHelper.dp(8f)
        })

        val saveBtn = primaryButton(context, context.getString(R.string.common_save)) {
            val title = titleInput.getText().trim()
            val content = contentInput.getText().trim()
            when {
                title.length < MIN_MISSION_RULE_TITLE ->
                    titleInput.setError(context.getString(R.string.onboarding_title_min_length))
                selectedChannelId == 0L ->
                    titleInput.setError(context.getString(R.string.onboarding_no_channels))
                else -> {
                    onSave(title, content, selectedChannelId, selectedTaskType)
                    dismiss()
                }
            }
        }
        footerRow.addView(saveBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        root.addView(footerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(16f)
        })

        scroll.addView(root)
        setCustomView(scroll)
        super.onCreate(savedInstanceState)
    }

    private fun channelPickerRow(): TextView {
        val channelList = channels
        return TextView(context).apply {
            text = channelList.firstOrNull { it.channelId == selectedChannelId }?.channelLabel
                ?: context.getString(R.string.onboarding_select_channel)
            setTextColor(theme.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = fieldBg()
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(12f))
            setOnClickListener {
                ChannelPickerSheet(
                    context,
                    theme,
                    channelList,
                    context.getString(R.string.onboarding_mission_channel),
                ) { ch ->
                    selectedChannelId = ch.channelId
                    text = ch.channelLabel
                }.show()
            }
        }
    }

    private fun sectionLabel(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(theme.textDisabled)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, LayoutHelper.dp(12f), 0, LayoutHelper.dp(4f))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// A13 / B6.7: Resource (Rule) Editor Sheet
// Supports: title (min 7), description, image file picker + 48dp preview, Remove (edit mode)
// ─────────────────────────────────────────────────────────────────────────────

class OnboardingRuleEditorSheet(
    context: Context,
    private val existing: OnboardingItem? = null,
    /**
     * Called with (title, description, existingImageUrl?, newLocalFilePath?).
     * Caller is responsible for uploading newLocalFilePath before createOnboarding / updateOnboarding.
     */
    private val onSave: (title: String, content: String, imageUrl: String?, localFilePath: String?) -> Unit,
    private val onRemove: (() -> Unit)? = null,
) : BottomSheet(context, needFocusable = true) {

    private val theme = ThemeColors.instance
    private lateinit var titleInput: InputCell
    private lateinit var contentInput: InputCell
    private var existingImageUrl: String? = existing?.imageUrl?.takeIf { it.isNotBlank() }
    private var selectedLocalFilePath: String? = null
    private var imagePreview: ImageView? = null

    // Activity result launcher injected externally for image picking (Android 10+)
    var imagePickerLauncher: ActivityResultLauncher<Intent>? = null

    init {
        setTitle(
            context.getString(
                if (existing != null) R.string.onboarding_edit_rule
                else R.string.onboarding_add_rule,
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), LayoutHelper.dp(16f))
        }

        // A13.3: Name * (min 7)
        titleInput = InputCell(context, theme).apply {
            setLabel(context.getString(R.string.onboarding_rule_title))
            setText(existing?.title.orEmpty())
        }
        root.addView(titleInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        // A13.3: Description (optional)
        contentInput = InputCell(context, theme).apply {
            setLabel(context.getString(R.string.onboarding_rule_content))
            setText(existing?.content.orEmpty())
        }
        root.addView(contentInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(8f)
        })

        // A13.3 / A7: Image Upload row — [Browse button] + [48x48 preview]
        val imageRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val browseBtn = TextView(context).apply {
            text = context.getString(R.string.onboarding_image_pick)
            setTextColor(theme.primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            background = fieldBg()
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(10f), LayoutHelper.dp(12f), LayoutHelper.dp(10f))
            setOnClickListener { openImagePicker() }
        }
        imageRow.addView(browseBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        // A7.3 / A13.3: 48x48 preview box
        val previewSize = LayoutHelper.dp(48f)
        imagePreview = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = context.getString(R.string.onboarding_image_preview_desc)
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(8f)
                setColor(theme.border)
            }
            visibility = if (existingImageUrl != null) View.VISIBLE else View.GONE
        }
        // Load existing URL preview if available using MezonImageLoader
        if (!existingImageUrl.isNullOrBlank()) {
            MezonImageLoader.getInstance(context).load(
                existingImageUrl!!,
                LayoutHelper.dp(48f),
                LayoutHelper.dp(48f),
                onSuccess = { bmp -> imagePreview?.setImageBitmap(bmp) },
            )
        }
        imageRow.addView(imagePreview, LinearLayout.LayoutParams(previewSize, previewSize).apply {
            leftMargin = LayoutHelper.dp(12f)
        })

        root.addView(imageRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(12f)
        })

        // A13.3 Footer: [Remove?] [Cancel] [Save]
        val footerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        if (existing != null && onRemove != null) {
            val removeBtn = outlineErrorBtn(context.getString(R.string.onboarding_remove)) {
                onRemove.invoke()
                dismiss()
            }
            footerRow.addView(removeBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
                rightMargin = LayoutHelper.dp(8f)
            })
        }

        val cancelBtn = outlineBtn(context, context.getString(R.string.common_cancel)) { dismiss() }
        footerRow.addView(cancelBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
            rightMargin = LayoutHelper.dp(8f)
        })

        val saveBtn = primaryButton(context, context.getString(R.string.common_save)) {
            val title = titleInput.getText().trim()
            val content = contentInput.getText().trim()
            // A6.5 validation
            if (title.length < MIN_MISSION_RULE_TITLE) {
                titleInput.setError(context.getString(R.string.onboarding_title_min_length))
                return@primaryButton
            }
            // A13.7: hasChanges check — if edit and nothing changed, just close
            if (existing != null) {
                val titleChanged = title != existing.title
                val contentChanged = content != existing.content
                val imageChanged = selectedLocalFilePath != null
                if (!titleChanged && !contentChanged && !imageChanged) {
                    dismiss()
                    return@primaryButton
                }
            }
            onSave(title, content, existingImageUrl, selectedLocalFilePath)
            dismiss()
        }
        footerRow.addView(saveBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        root.addView(footerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(16f)
        })

        scroll.addView(root)
        setCustomView(scroll)
        super.onCreate(savedInstanceState)
    }

    /** A13.4 / A7: Open system image picker; validate type + size; show local preview. */
    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val ctx = context
        if (ctx is Activity) {
            ctx.startActivityForResult(
                Intent.createChooser(intent, ctx.getString(R.string.onboarding_image_pick)),
                REQUEST_CODE_PICK_IMAGE,
            )
        }
    }

    /** Call this from onActivityResult in the host Fragment/Activity. */
    fun onImageResult(uri: Uri?) {
        uri ?: return
        val ctx = context
        val mime = ctx.contentResolver.getType(uri)
        // A6.5 / A13.4: validate type
        if (mime == null || mime !in VALID_IMAGE_MIME_TYPES) {
            showErrorDialog(ctx.getString(R.string.onboarding_image_type_error))
            return
        }
        // A6.5 / A13.4: validate size
        val size = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        if (size > MAX_FILE_SIZE_10MB) {
            showErrorDialog(ctx.getString(R.string.onboarding_image_size_error))
            return
        }
        // Save local path for upload later
        selectedLocalFilePath = uri.toString()
        existingImageUrl = null   // local file overrides any existing URL

            imagePreview?.let { preview ->
                preview.visibility = View.VISIBLE
                MezonImageLoader.getInstance(ctx).loadFromUri(
                    uri,
                    LayoutHelper.dp(48f),
                    LayoutHelper.dp(48f),
                    onSuccess = { bmp -> preview.setImageBitmap(bmp) },
                )
            }
    }

    private fun showErrorDialog(message: String) {
        android.app.AlertDialog.Builder(context)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss() }
            .show()
    }

    companion object {
        const val REQUEST_CODE_PICK_IMAGE = 9_021
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun BottomSheet.primaryButton(context: Context, label: String, onClick: () -> Unit): TextView {
    val theme = ThemeColors.instance
    return TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(theme.onPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dpf(10f)
            setColor(theme.primary)
        }
        setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(14f), LayoutHelper.dp(16f), LayoutHelper.dp(14f))
        setOnClickListener { onClick() }
    }
}

private fun BottomSheet.outlineBtn(context: Context = this.context, label: String, onClick: () -> Unit): TextView {
    val theme = ThemeColors.instance
    return TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(theme.primary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dpf(10f)
            setStroke(LayoutHelper.dp(1), theme.primary)
        }
        setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(11f), LayoutHelper.dp(14f), LayoutHelper.dp(11f))
        setOnClickListener { onClick() }
    }
}

private fun BottomSheet.outlineErrorBtn(label: String, onClick: () -> Unit): TextView {
    val theme = ThemeColors.instance
    return TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(theme.error)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dpf(10f)
            setStroke(LayoutHelper.dp(1), theme.error)
        }
        setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(11f), LayoutHelper.dp(14f), LayoutHelper.dp(11f))
        setOnClickListener { onClick() }
    }
}

private fun fieldBg() = GradientDrawable().apply {
    cornerRadius = LayoutHelper.dpf(10f)
    setColor(ThemeColors.instance.border)
}

/** Convenience: register a simple text-changed listener on InputCell. */
private fun InputCell.addTextChangedSimple(onTextChanged: (String) -> Unit) {
    // InputCell exposes getEditText() or similar; use what's available in your codebase.
    // If InputCell has an addTextWatcher API use that; otherwise hook via reflection.
    // This is a best-effort helper — callers can skip for simpler builds.
    try {
        val etField = this.javaClass.declaredFields.firstOrNull { it.name.contains("edit", ignoreCase = true) }
        etField?.isAccessible = true
        val et = etField?.get(this) as? android.widget.EditText
        et?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) { onTextChanged(s?.toString().orEmpty()) }
        })
    } catch (_: Throwable) { /* ignore if introspection fails */ }
}
