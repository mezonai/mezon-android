package com.mezon.mobile.home.chat.poll

import android.content.Context
import android.graphics.Rect
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AdjustPanLayoutHelper
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.ui.theme.ThemeMode
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.chat.EmojiController
import com.mezon.mobile.home.chat.EmojiItem
import com.mezon.mobile.ui.cells.BackupImageView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.SwitchView
import com.mezon.mobile.util.getEmojiUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CreatePollFragment : BaseFragment() {

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CLAN_ID = "clanId"

        fun newInstance(channelId: Long, clanId: Long): CreatePollFragment =
            CreatePollFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHANNEL_ID, channelId)
                    putLong(ARG_CLAN_ID, clanId)
                }
            }
    }

    var onPollSubmit: (suspend (PollSubmitPayload) -> Boolean)? = null

    private var isSubmitting = false

    private var channelId: Long = 0L
    private var clanId: Long = 0L

    private lateinit var emojiController: EmojiController

    private var formState = CreatePollFormState()
    private val answerRows = ArrayList<AnswerRow>()

    private lateinit var answersContainer: LinearLayout
    private lateinit var addAnswerLink: TextView
    private lateinit var durationValueView: TextView
    private lateinit var multipleSwitch: SwitchView
    private lateinit var postButton: TextView
    private lateinit var postButtonBackground: GradientDrawable
    private lateinit var scrollView: ScrollView
    private lateinit var formContainer: LinearLayout
    private lateinit var rootFrame: FrameLayout
    private lateinit var pollHeader: View

    private var adjustPanHelper: AdjustPanLayoutHelper? = null
    private var emojiPickerSheet: PollAnswerEmojiPickerSheet? = null

    private val fieldRadiusPx by lazy { LayoutHelper.dpf(8f) }
    private val postRadiusPx by lazy { LayoutHelper.dpf(10f) }

    private val colorScreen get() = themeColors.chatBackground
    private val colorField get() = themeColors.tertiary
    private val colorLabel get() = themeColors.onSurfaceVariant
    private val colorText get() = themeColors.onSurface
    private val colorHint get() = themeColors.onSurfaceVariant
    private val colorLink get() = themeColors.primary
    private val colorPostDisabled get() = when (themeColors.resolvedMode) {
        ThemeMode.LIGHT -> themeColors.borderDim
        else -> themeColors.tertiary
    }

    private data class AnswerRow(
        val root: LinearLayout,
        val emojiPreview: BackupImageView,
        val emojiPlaceholder: ImageView,
        val editText: EditText
    )

    override fun onInject(entryPoint: FragmentEntryPoint) {
        emojiController = entryPoint.emojiController()
    }

    override fun onFragmentCreate(): Boolean {
        hasOwnBackground = true
        channelId = arguments?.getLong(ARG_CHANNEL_ID, 0L) ?: 0L
        clanId = arguments?.getLong(ARG_CLAN_ID, 0L) ?: 0L
        emojiController.loadEmojis()
        return super.onFragmentCreate()
    }

    override fun createView(context: Context): View {
        val contentPadDp = 14
        val padH = LayoutHelper.dp(contentPadDp)
        val sectionGap = LayoutHelper.dp(14)
        val labelGap = LayoutHelper.dp(6)

        pollHeader = buildPollHeader(context, contentPadDp)

        formContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, LayoutHelper.dp(8), padH, LayoutHelper.dp(76))
        }
        val body = formContainer

        body.addView(sectionLabel(context, R.string.poll_question))
        body.addView(buildQuestionField(context), sectionMargin(labelGap))

        body.addView(buildAnswersSectionHeader(context), sectionMargin(sectionGap))
        answersContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(answersContainer, sectionMargin(labelGap))

        addAnswerLink = TextView(context).apply {
            text = getString(R.string.poll_add_answer)
            setTextColor(colorLink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, LayoutHelper.dp(6), 0, 0)
            setOnClickListener { addAnswerSlot() }
        }
        body.addView(addAnswerLink)

        body.addView(sectionLabel(context, R.string.poll_duration), sectionMargin(sectionGap))
        durationValueView = TextView(context).apply {
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val durationRow = buildSelectRow(context) { showDurationPicker() }
        durationRow.addView(durationValueView, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))
        val chevronPx = LayoutHelper.dp(9)
        durationRow.addView(
            iconView(context, MezonIcon.chevronDownSmallIcon, chevronPx, colorLabel),
            LayoutHelper.createLinear(chevronPx, chevronPx)
        )
        body.addView(durationRow, sectionMargin(labelGap))

        multipleSwitch = SwitchView(context, themeColors).apply {
            onCheckedChange = { formState = formState.copy(allowMultipleAnswers = it) }
        }
        val multipleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, LayoutHelper.dp(12), 0, 0)
        }
        multipleRow.addView(
            TextView(context).apply {
                text = getString(R.string.poll_allow_multiple)
                setTextColor(colorText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            },
            LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f)
        )
        multipleRow.addView(multipleSwitch)
        body.addView(multipleRow)

        scrollView = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            setBackgroundColor(colorScreen)
            addView(
                body,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        postButtonBackground = GradientDrawable().apply {
            cornerRadius = postRadiusPx
            setColor(colorPostDisabled)
        }
        postButton = TextView(context).apply {
            gravity = Gravity.CENTER
            text = getString(R.string.poll_post)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorText)
            minimumHeight = LayoutHelper.dp(42)
            setPadding(LayoutHelper.dp(14), LayoutHelper.dp(10), LayoutHelper.dp(14), LayoutHelper.dp(10))
            background = postButtonBackground
            setOnClickListener { submitPoll() }
        }

        rootFrame = FrameLayout(context).apply {
            setBackgroundColor(colorScreen)
            addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
            addView(
                postButton,
                LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.BOTTOM,
                    leftMargin = contentPadDp.toFloat(),
                    rightMargin = contentPadDp.toFloat(),
                    bottomMargin = 12f
                )
            )
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorScreen)
            addView(pollHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(rootFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        }

        updateDurationLabel()
        rebuildAnswerRows(context)
        refreshPostButton()

        adjustPanHelper = object : AdjustPanLayoutHelper(root) {
            override fun onTransitionStart(keyboardVisible: Boolean, contentHeight: Int) {
                if (keyboardVisible) scrollFocusedFieldIntoView()
            }
        }

        return root
    }

    override fun onFragmentDestroy() {
        adjustPanHelper?.onDetach()
        adjustPanHelper = null
        super.onFragmentDestroy()
    }

    override fun onBackPressed(): Boolean {
        if (emojiPickerSheet != null) {
            dismissEmojiPickerSheet()
            formState = formState.copy(emojiPickerIndex = null)
            return false
        }
        return super.onBackPressed()
    }

    private fun rebuildAnswerRows(context: Context) {
        answersContainer.removeAllViews()
        answerRows.clear()
        val gap = LayoutHelper.dp(6)
        for (i in formState.answers.indices) {
            val row = createAnswerRow(context, i)
            answerRows.add(row)
            answersContainer.addView(
                row.root,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                    if (i > 0) topMargin = gap
                }
            )
        }
        addAnswerLink.visibility =
            if (formState.answers.size >= POLL_ANSWER_MAX_SLOTS) View.GONE else View.VISIBLE
    }

    private fun createAnswerRow(context: Context, index: Int): AnswerRow {
        val emojiSize = LayoutHelper.dp(14)
        val emojiBtnW = LayoutHelper.dp(26)
        val emojiPreview = BackupImageView(context).apply { setOmitEmptyPlaceholder(true) }
        val emojiPlaceholder = ImageView(context).apply {
            setImageResource(R.drawable.ic_emoji_icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            colorFilter = PorterDuffColorFilter(colorLabel, PorterDuff.Mode.SRC_IN)
        }

        val emojiButton = FrameLayout(context).apply {
            minimumWidth = emojiBtnW
            addView(emojiPreview, LayoutHelper.createFrame(emojiSize, emojiSize, Gravity.CENTER))
            addView(emojiPlaceholder, LayoutHelper.createFrame(emojiSize, emojiSize, Gravity.CENTER))
            setOnClickListener { openEmojiPickerForAnswer(index) }
        }
        refreshAnswerEmojiPreview(index, emojiPreview, emojiPlaceholder)

        val editText = EditText(context).apply {
            background = null
            hint = getString(R.string.poll_answer_placeholder)
            setHintTextColor(colorHint)
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isSingleLine = true
            maxLines = 1
            includeFontPadding = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(LayoutHelper.dp(4), 0, 0, 0)
            setText(formState.answers.getOrElse(index) { "" })
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val list = formState.answers.toMutableList()
                    while (list.size <= index) list.add("")
                    list[index] = s?.toString() ?: ""
                    formState = formState.copy(answers = list)
                    refreshPostButton()
                }
            })
            registerEditorFocusScroll(this)
        }

        val trashIconPx = LayoutHelper.dp(10)
        val deleteSlotPx = LayoutHelper.dp(18)
        val deleteButton = FrameLayout(context).apply {
            contentDescription = getString(R.string.poll_remove_answer)
            visibility = if (formState.answers.size > POLL_ANSWER_MIN_SLOTS) View.VISIBLE else View.GONE
            setOnClickListener { removeAnswerSlot(index) }
            addView(
                ImageView(context).apply {
                    setImageResource(MezonIcon.trashIcon.resId)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    colorFilter = PorterDuffColorFilter(colorLabel, PorterDuff.Mode.SRC_IN)
                },
                LayoutHelper.createFrame(trashIconPx, trashIconPx, Gravity.CENTER)
            )
        }

        val rowRoot = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = fieldBackground()
            minimumHeight = LayoutHelper.dp(38)
            setPadding(LayoutHelper.dp(8), LayoutHelper.dp(6), LayoutHelper.dp(4), LayoutHelper.dp(6))
        }
        rowRoot.addView(emojiButton, LayoutHelper.createLinear(emojiBtnW, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL))
        rowRoot.addView(editText, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))
        rowRoot.addView(deleteButton, LayoutHelper.createLinear(deleteSlotPx, deleteSlotPx, 0f, Gravity.CENTER_VERTICAL))

        return AnswerRow(rowRoot, emojiPreview, emojiPlaceholder, editText)
    }

    private fun buildQuestionField(context: Context): FrameLayout {
        val pad = LayoutHelper.dp(10)
        val counter = TextView(context).apply {
            text = getString(R.string.poll_question_counter, 0, POLL_QUESTION_MAX_LENGTH)
            setTextColor(colorHint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        val edit = EditText(context).apply {
            background = null
            hint = getString(R.string.poll_question_placeholder)
            setHintTextColor(colorHint)
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isSingleLine = false
            maxLines = 4
            includeFontPadding = false
            minHeight = LayoutHelper.dp(44)
            setPadding(0, 0, 0, LayoutHelper.dp(14))
            setText(formState.question)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString() ?: ""
                    if (text.length > POLL_QUESTION_MAX_LENGTH) {
                        setText(text.substring(0, POLL_QUESTION_MAX_LENGTH))
                        setSelection(POLL_QUESTION_MAX_LENGTH)
                        return
                    }
                    formState = formState.copy(question = text)
                    counter.text = getString(R.string.poll_question_counter, text.length, POLL_QUESTION_MAX_LENGTH)
                    refreshPostButton()
                }
            })
            registerEditorFocusScroll(this)
        }
        return FrameLayout(context).apply {
            background = fieldBackground()
            setPadding(pad, pad, pad, pad)
            addView(edit, FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(
                counter,
                FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.END or Gravity.BOTTOM)
            )
        }
    }

    private fun refreshAnswerEmojiPreview(index: Int, preview: BackupImageView, placeholder: ImageView) {
        val emojiId = formState.answerEmojiIds.getOrElse(index) { "" }
        if (emojiId.isEmpty()) {
            preview.visibility = View.GONE
            placeholder.visibility = View.VISIBLE
            preview.setImage(null)
            return
        }
        placeholder.visibility = View.GONE
        preview.visibility = View.VISIBLE
        getEmojiUrl(emojiId)?.let { preview.setImage(it) }
    }

    private fun addAnswerSlot() {
        if (formState.answers.size >= POLL_ANSWER_MAX_SLOTS) return
        formState = formState.copy(
            answers = formState.answers + "",
            answerEmojiIds = formState.answerEmojiIds + ""
        )
        fragmentView?.context?.let { rebuildAnswerRows(it) }
        refreshPostButton()
    }

    private fun removeAnswerSlot(index: Int) {
        if (formState.answers.size <= POLL_ANSWER_MIN_SLOTS) return
        val newAnswers = formState.answers.filterIndexed { i, _ -> i != index }
        val newEmojis = formState.answerEmojiIds.filterIndexed { i, _ -> i != index }
        var picker = formState.emojiPickerIndex
        when {
            picker == index -> picker = null
            picker != null && picker > index -> picker = picker - 1
        }
        formState = formState.copy(answers = newAnswers, answerEmojiIds = newEmojis, emojiPickerIndex = picker)
        if (picker == null) dismissEmojiPickerSheet()
        fragmentView?.context?.let { rebuildAnswerRows(it) }
        refreshPostButton()
    }

    private fun openEmojiPickerForAnswer(index: Int) {
        if (formState.emojiPickerIndex == index && emojiPickerSheet != null) {
            dismissEmojiPickerSheet()
            formState = formState.copy(emojiPickerIndex = null)
            return
        }
        dismissEmojiPickerSheet()
        formState = formState.copy(emojiPickerIndex = index)
        val ctx = fragmentView?.context ?: return
        val sheet = PollAnswerEmojiPickerSheet(
            ctx,
            themeColors,
            emojiController,
            notificationCenter
        ) { emoji -> applyEmojiToAnswer(index, emoji) }
        sheet.setOnHideListener {
            emojiPickerSheet = null
            if (formState.emojiPickerIndex == index) {
                formState = formState.copy(emojiPickerIndex = null)
            }
        }
        emojiPickerSheet = sheet
        sheet.show()
    }

    private fun dismissEmojiPickerSheet() {
        emojiPickerSheet?.dismiss()
        emojiPickerSheet = null
    }

    private fun applyEmojiToAnswer(index: Int, emoji: EmojiItem) {
        val ids = formState.answerEmojiIds.toMutableList()
        while (ids.size <= index) ids.add("")
        ids[index] = emojiIdForApi(emoji)
        formState = formState.copy(answerEmojiIds = ids, emojiPickerIndex = null)
        dismissEmojiPickerSheet()
        if (index < answerRows.size) {
            val row = answerRows[index]
            refreshAnswerEmojiPreview(index, row.emojiPreview, row.emojiPlaceholder)
        } else {
            fragmentView?.context?.let { rebuildAnswerRows(it) }
        }
    }

    private fun showDurationPicker() {
        val activity = getParentActivity() ?: return
        val labels: Array<CharSequence> = POLL_DURATION_OPTIONS
            .map { getString(it.second) as CharSequence }
            .toTypedArray()
        val currentIdx = POLL_DURATION_OPTIONS.indexOfFirst { it.first == formState.duration }.coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle(getString(R.string.poll_duration))
            .setSingleChoiceItems(labels, currentIdx) { which ->
                formState = formState.copy(duration = POLL_DURATION_OPTIONS[which].first)
                updateDurationLabel()
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }

    private fun updateDurationLabel() {
        val labelRes = POLL_DURATION_OPTIONS.firstOrNull { it.first == formState.duration }?.second
            ?: R.string.poll_duration_24_hours
        durationValueView.text = getString(labelRes)
    }

    private fun refreshPostButton() {
        val canPost = canPostPoll(formState)
        val enabled = canPost && !isSubmitting
        postButton.text = if (isSubmitting) {
            getString(R.string.poll_posting)
        } else {
            getString(R.string.poll_post)
        }
        postButtonBackground.setColor(
            when {
                isSubmitting -> themeColors.primary
                enabled -> themeColors.primary
                else -> colorPostDisabled
            }
        )
        postButton.setTextColor(
            if (isSubmitting || enabled) themeColors.onPrimary else colorLabel
        )
        postButton.isEnabled = enabled
        postButton.isClickable = enabled
        postButton.alpha = if (isSubmitting) 0.75f else 1f
        setPollFormBusy(isSubmitting)
    }

    private fun setPollFormBusy(busy: Boolean) {
        scrollView.setOnTouchListener(if (busy) { _, _ -> true } else null)
        formContainer.isEnabled = !busy
        formContainer.alpha = if (busy) 0.6f else 1f
        addAnswerLink.isEnabled = !busy
        multipleSwitch.isEnabled = !busy
    }

    private fun submitPoll() {
        val payload = buildPollSubmitPayload(formState) ?: return
        val submit = onPollSubmit ?: return
        if (isSubmitting) return
        isSubmitting = true
        refreshPostButton()
        fragmentScope.launch(Dispatchers.Main) {
            val success = try {
                submit(payload)
            } catch (_: Exception) {
                false
            }
            isSubmitting = false
            if (success) {
                finishFragment()
            } else {
                refreshPostButton()
            }
        }
    }

    private fun registerEditorFocusScroll(editText: EditText) {
        editText.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) scrollEditorIntoView(view)
        }
    }

    private fun scrollEditorIntoView(editor: View) {
        scrollView.post {
            scrollView.requestChildRectangleOnScreen(
                editor,
                Rect(0, 0, editor.width, editor.height),
                true
            )
        }
    }

    private fun scrollFocusedFieldIntoView() {
        val focused = fragmentView?.findFocus() ?: return
        var target: View = focused
        var parent = target.parent as? View
        while (parent != null && parent !== formContainer) {
            target = parent
            parent = parent.parent as? View
        }
        scrollEditorIntoView(target)
    }

    private fun buildPollHeader(context: Context, contentPadDp: Int): View {
        val barH = 40
        val backIconDp = 24
        val backMarginDp = (contentPadDp - 4f * (backIconDp / 24f)).coerceAtLeast(0f)
        return FrameLayout(context).apply {
            setBackgroundColor(colorScreen)
            setPadding(0, AndroidUtilities.statusBarHeight, 0, 0)
            minimumHeight = LayoutHelper.dp(barH) + AndroidUtilities.statusBarHeight

            addView(
                TextView(context).apply {
                    text = getString(R.string.poll_create_title)
                    setTextColor(colorText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    maxLines = 1
                },
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, barH, Gravity.CENTER)
            )

            val backButton = FrameLayout(context).apply {
                contentDescription = getString(R.string.common_go_back)
                setOnClickListener { finishFragment() }
                addView(
                    ImageView(context).apply {
                        setImageResource(R.drawable.ic_arrow_back)
                        imageTintList = android.content.res.ColorStateList.valueOf(colorText)
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                    },
                    LayoutHelper.createFrame(backIconDp, backIconDp, Gravity.START or Gravity.CENTER_VERTICAL)
                )
            }
            addView(
                backButton,
                LayoutHelper.createFrame(
                    40, barH,
                    Gravity.START or Gravity.CENTER_VERTICAL,
                    backMarginDp, 0f, 0f, 0f
                )
            )
        }
    }

    private fun fieldBorderColor(): Int = when (themeColors.resolvedMode) {
        ThemeMode.LIGHT -> themeColors.outlineVariant
        ThemeMode.ABYSS -> themeColors.outline
        else -> themeColors.outlineVariant
    }

    private fun fieldBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(colorField)
        cornerRadius = fieldRadiusPx
        setStroke(LayoutHelper.dp(1), fieldBorderColor())
    }

    private fun buildSelectRow(context: Context, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = fieldBackground()
            minimumHeight = LayoutHelper.dp(38)
            setPadding(LayoutHelper.dp(10), LayoutHelper.dp(8), LayoutHelper.dp(10), LayoutHelper.dp(8))
            setOnClickListener { onClick() }
        }

    private fun sectionLabel(context: Context, textRes: Int): TextView = TextView(context).apply {
        text = getString(textRes)
        setTextColor(colorLabel)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }

    private fun buildAnswersSectionHeader(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionLabel(context, R.string.poll_answers))
            addView(
                TextView(context).apply {
                    text = getString(R.string.poll_answers_min_required)
                    setTextColor(colorLabel)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setPadding(0, LayoutHelper.dp(2), 0, 0)
                }
            )
        }

    private fun sectionMargin(topPx: Int): LinearLayout.LayoutParams =
        LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = topPx
        }

    private fun tintedIcon(context: Context, icon: MezonIcon, sizePx: Int, tint: Int): Drawable =
        tintedIcon(context, icon.resId, sizePx, tint)

    private fun tintedIcon(context: Context, resId: Int, sizePx: Int, tint: Int): Drawable {
        val d = context.getDrawable(resId)?.mutate() ?: return GradientDrawable()
        d.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        d.setBounds(0, 0, sizePx, sizePx)
        return d
    }

    private fun iconView(context: Context, resId: Int, sizePx: Int, tint: Int): ImageView =
        ImageView(context).apply {
            val d = context.getDrawable(resId)?.mutate()
            d?.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
            d?.setBounds(0, 0, sizePx, sizePx)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.CENTER
        }

    private fun iconView(context: Context, icon: MezonIcon, sizePx: Int, tint: Int): ImageView =
        ImageView(context).apply {
            setImageDrawable(tintedIcon(context, icon, sizePx, tint))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            adjustViewBounds = true
            maxWidth = sizePx
            maxHeight = sizePx
        }
}
