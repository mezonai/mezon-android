package com.mezon.mobile.home.clans.settings

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mezon.api.OnboardingItem

private const val MIN_MISSION_RULE_TITLE = 7
class OnboardingQuestionEditorSheet(
    context: Context,
    private val existing: OnboardingItem? = null,
    private val onSave: (title: String, answers: List<OnboardingAnswerDraft>) -> Unit,
) : BottomSheet(context, needFocusable = true) {

    private val theme = ThemeColors.instance
    private lateinit var titleInput: InputCell
    private val answerInputs = mutableListOf<InputCell>()
    private lateinit var answersContainer: LinearLayout

    init {
        setTitle(
            context.getString(
                if (existing != null) R.string.onboarding_edit_question
                else R.string.onboarding_add_question
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), LayoutHelper.dp(16f))
        }

        titleInput = InputCell(context, theme).apply {
            setLabel(context.getString(R.string.onboarding_question_title))
            setText(existing?.title.orEmpty())
        }
        root.addView(titleInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        answersContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val existingAnswers = existing?.answersList.orEmpty()
        if (existingAnswers.isEmpty()) {
            addAnswerRow("")
        } else {
            existingAnswers.forEach { addAnswerRow(it.title) }
        }
        root.addView(answersContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(12f)
        })

        val addAnswerBtn = TextView(context).apply {
            text = context.getString(R.string.onboarding_add_answer)
            setTextColor(theme.textLink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, LayoutHelper.dp(10f), 0, 0)
            setOnClickListener { addAnswerRow("") }
        }
        root.addView(addAnswerBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        val saveBtn = primaryButton(context, context.getString(R.string.common_save)) {
            val title = titleInput.getText().trim()
            if (title.isBlank()) {
                titleInput.setError(context.getString(R.string.onboarding_question_required))
                return@primaryButton
            }
            val answers = answerInputs.mapNotNull { cell ->
                val t = cell.getText().trim()
                if (t.isBlank()) null else OnboardingAnswerDraft(title = t)
            }
            if (answers.isEmpty()) {
                return@primaryButton
            }
            onSave(title, answers)
            dismiss()
        }
        root.addView(saveBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(16f)
        })

        scroll.addView(root)
        setCustomView(scroll)
        super.onCreate(savedInstanceState)
    }

    private fun addAnswerRow(initial: String) {
        val cell = InputCell(context, theme).apply {
            setLabel(context.getString(R.string.onboarding_answer_title))
            setText(initial)
        }
        answerInputs.add(cell)
        answersContainer.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(8f)
        })
    }
}

class OnboardingMissionEditorSheet(
    context: Context,
    private val channels: List<ClanChannelEntity>,
    private val existing: OnboardingItem? = null,
    private val onSave: (title: String, content: String, channelId: Long, taskType: Int) -> Unit,
) : BottomSheet(context, needFocusable = true) {

    private val theme = ThemeColors.instance
    private lateinit var titleInput: InputCell
    private lateinit var contentInput: InputCell
    private var selectedChannelId: Long = existing?.channelId?.takeIf { it != 0L }
        ?: channels.firstOrNull()?.channelId
        ?: 0L

    init {
        setTitle(
            context.getString(
                if (existing != null) R.string.onboarding_edit_mission
                else R.string.onboarding_add_mission
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), LayoutHelper.dp(16f))
        }

        titleInput = InputCell(context, theme).apply {
            setLabel(context.getString(R.string.onboarding_mission_title))
            setText(existing?.title.orEmpty())
        }
        root.addView(titleInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        contentInput = InputCell(context, theme).apply {
            setLabel(context.getString(R.string.onboarding_mission_content))
            setText(existing?.content.orEmpty())
        }
        root.addView(contentInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(8f)
        })

        val channelLabel = TextView(context).apply {
            text = context.getString(R.string.onboarding_mission_channel)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(theme.textDisabled)
            setPadding(0, LayoutHelper.dp(12f), 0, LayoutHelper.dp(4f))
        }
        root.addView(channelLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val channelList = channels
        val channelRow = TextView(context).apply {
            text = channelList.firstOrNull { ch -> ch.channelId == selectedChannelId }?.channelLabel
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
        root.addView(channelRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val saveBtn = primaryButton(context, context.getString(R.string.common_save)) {
            val title = titleInput.getText().trim()
            val content = contentInput.getText().trim()
            when {
                title.length < MIN_MISSION_RULE_TITLE -> {
                    titleInput.setError(context.getString(R.string.onboarding_title_min_length))
                }
                selectedChannelId == 0L -> Unit
                else -> {
                    onSave(title, content, selectedChannelId, existing?.taskType?.takeIf { it != 0 }
                        ?: MissionType.SEND_MESSAGE.apiValue)
                    dismiss()
                }
            }
        }
        root.addView(saveBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(16f)
        })

        setCustomView(root)
        super.onCreate(savedInstanceState)
    }
}

class OnboardingRuleEditorSheet(
    context: Context,
    private val existing: OnboardingItem? = null,
    private val onSave: (title: String, content: String, imageUrl: String?, localFilePath: String?) -> Unit,
) : BottomSheet(context, needFocusable = true) {

    private val theme = ThemeColors.instance
    private lateinit var titleInput: InputCell
    private lateinit var contentInput: InputCell
    private var existingImageUrl: String? = existing?.imageUrl?.takeIf { it.isNotBlank() }

    init {
        setTitle(
            context.getString(
                if (existing != null) R.string.onboarding_edit_rule
                else R.string.onboarding_add_rule
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), LayoutHelper.dp(16f))
        }

        titleInput = InputCell(context, theme).apply {
            setLabel(context.getString(R.string.onboarding_rule_title))
            setText(existing?.title.orEmpty())
        }
        root.addView(titleInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        contentInput = InputCell(context, theme).apply {
            setLabel(context.getString(R.string.onboarding_rule_content))
            setText(existing?.content.orEmpty())
        }
        root.addView(contentInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(8f)
        })

        val saveBtn = primaryButton(context, context.getString(R.string.common_save)) {
            val title = titleInput.getText().trim()
            val content = contentInput.getText().trim()
            if (title.length < MIN_MISSION_RULE_TITLE) {
                titleInput.setError(context.getString(R.string.onboarding_title_min_length))
                return@primaryButton
            }
            onSave(title, content, existingImageUrl, null)
            dismiss()
        }
        root.addView(saveBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(16f)
        })

        setCustomView(root)
        super.onCreate(savedInstanceState)
    }
}

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

private fun fieldBg() = GradientDrawable().apply {
    cornerRadius = LayoutHelper.dpf(10f)
    setColor(ThemeColors.instance.border)
}
