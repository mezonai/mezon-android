package com.mezon.mobile.home.clans.settings

import com.mezon.mezon.api.OnboardingAnswer
import com.mezon.mezon.api.OnboardingContent
import com.mezon.mezon.api.OnboardingItem
import com.mezon.mezon.api.onboardingAnswer
import com.mezon.mezon.api.onboardingContent

enum class OnboardingPage { MAIN, QUESTION, MISSION }

/** Web client enum: greeting=1, rule=2, task=3, question=4 */
enum class GuideType(val apiValue: Int) {
    GREETING(1),
    RULE(2),
    TASK(3),
    QUESTION(4),
    ;

    companion object {
        /** Server values follow web client (1–4); proto `0` = greeting. */
        fun fromServer(raw: Int): GuideType? = when (raw) {
            0, 1 -> GREETING
            2 -> RULE
            3 -> TASK
            4 -> QUESTION
            else -> null
        }
    }
}

enum class MissionType(val apiValue: Int) {
    SEND_MESSAGE(1),
    VISIT(2),
    DO_SOMETHING(3),
}

data class OnboardingAnswerDraft(
    val title: String = "",
    val description: String = "",
    val emoji: String = "",
    val imageUrl: String = "",
)

data class RuleDraft(
    val localId: String = java.util.UUID.randomUUID().toString(),
    val title: String = "",
    val content: String = "",
    val imageUrl: String? = null,
    val localFilePath: String? = null,
)

data class QuestionDraft(
    val localId: String = java.util.UUID.randomUUID().toString(),
    val title: String = "",
    val answers: List<OnboardingAnswerDraft> = emptyList(),
)

data class MissionDraft(
    val localId: String = java.util.UUID.randomUUID().toString(),
    val title: String = "",
    val content: String = "",
    val channelId: Long = 0L,
    val taskType: Int = MissionType.SEND_MESSAGE.apiValue,
)

data class OnboardingFormDraft(
    val questions: List<QuestionDraft> = emptyList(),
    val rules: List<RuleDraft> = emptyList(),
    val tasks: List<MissionDraft> = emptyList(),
)

data class OnboardingByClan(
    val greeting: OnboardingItem? = null,
    val questions: List<OnboardingItem> = emptyList(),
    val rules: List<OnboardingItem> = emptyList(),
    val missions: List<OnboardingItem> = emptyList(),
) {
    fun hasAnyItem(): Boolean =
        greeting != null || questions.isNotEmpty() || rules.isNotEmpty() || missions.isNotEmpty()
}

data class OnboardingSettingsUiState(
    val clanId: Long = 0L,
    val isOnboardingEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isEnableSetupOpen: Boolean = false,
    val currentPage: OnboardingPage = OnboardingPage.MAIN,
    val showHighlightNeedItem: Boolean = false,
    val onboardingByClan: OnboardingByClan = OnboardingByClan(),
    val draft: OnboardingFormDraft = OnboardingFormDraft(),
    val errorMessage: String? = null,
) {
    val hasDraftData: Boolean
        get() = draft.questions.isNotEmpty() || draft.rules.isNotEmpty() || draft.tasks.isNotEmpty()

    val hasAtLeastOneItem: Boolean
        get() = hasDraftData || onboardingByClan.hasAnyItem()

    val isDirty: Boolean
        get() = hasDraftData
}

fun OnboardingAnswerDraft.toProto(): OnboardingAnswer = onboardingAnswer {
    title = this@toProto.title
    description = this@toProto.description
    emoji = this@toProto.emoji
    imageUrl = this@toProto.imageUrl
}

fun QuestionDraft.toCreateContent(): OnboardingContent = onboardingContent {
    guideType = GuideType.QUESTION.apiValue
    title = this@toCreateContent.title.trim()
    answers.addAll(this@toCreateContent.answers.map { it.toProto() })
}

fun RuleDraft.toCreateContent(imageUrlResolved: String): OnboardingContent = onboardingContent {
    guideType = GuideType.RULE.apiValue
    title = this@toCreateContent.title.trim()
    content = this@toCreateContent.content.trim()
    if (imageUrlResolved.isNotBlank()) imageUrl = imageUrlResolved
}

fun MissionDraft.toCreateContent(): OnboardingContent = onboardingContent {
    guideType = GuideType.TASK.apiValue
    taskType = this@toCreateContent.taskType
    channelId = this@toCreateContent.channelId
    title = this@toCreateContent.title.trim()
    content = this@toCreateContent.content.trim()
}

fun OnboardingItem.guideTypeEnum(): GuideType? = GuideType.fromServer(guideType)

fun groupOnboardingItems(items: List<OnboardingItem>): OnboardingByClan {
    var greeting: OnboardingItem? = null
    val questions = mutableListOf<OnboardingItem>()
    val rules = mutableListOf<OnboardingItem>()
    val missions = mutableListOf<OnboardingItem>()
    for (item in items) {
        when (item.guideTypeEnum()) {
            GuideType.GREETING -> if (greeting == null) greeting = item
            GuideType.QUESTION -> questions.add(item)
            GuideType.RULE -> rules.add(item)
            GuideType.TASK -> missions.add(item)
            null -> Unit
        }
    }
    return OnboardingByClan(greeting, questions, rules, missions)
}
