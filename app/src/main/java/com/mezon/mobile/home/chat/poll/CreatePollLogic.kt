package com.mezon.mobile.home.chat.poll

import com.mezon.mobile.home.chat.EmojiItem
import com.mezon.mobile.network.CHANNEL_TYPE_DM

/** Form state aligned with web `CreatePollModal`. */
data class CreatePollFormState(
    val question: String = "",
    val answers: List<String> = listOf("", ""),
    val answerEmojiIds: List<String> = listOf("", ""),
    val duration: String = DEFAULT_POLL_DURATION_HOURS,
    val allowMultipleAnswers: Boolean = false,
    val emojiPickerIndex: Int? = null
)

data class PollSubmitPayload(
    val question: String,
    val answers: List<String>,
    val expireHours: Int,
    val pollType: Int
)

const val DEFAULT_POLL_DURATION_HOURS = "24"
const val POLL_QUESTION_MAX_LENGTH = 300
/** UI slots: always start with 2 rows; trash hidden until size > this. */
const val POLL_ANSWER_MIN_SLOTS = 2

/** Non-empty answers required to enable Post — same as web `CreatePollModal` (not 1). */
const val POLL_ANSWER_MIN_FOR_POST = POLL_ANSWER_MIN_SLOTS

const val POLL_ANSWER_MAX_SLOTS = 20

val POLL_DURATION_OPTIONS: List<Pair<String, Int>> = listOf(
    "1" to com.mezon.mobile.R.string.poll_duration_1_hour,
    "4" to com.mezon.mobile.R.string.poll_duration_4_hours,
    "8" to com.mezon.mobile.R.string.poll_duration_8_hours,
    "24" to com.mezon.mobile.R.string.poll_duration_24_hours,
    "72" to com.mezon.mobile.R.string.poll_duration_3_days,
    "168" to com.mezon.mobile.R.string.poll_duration_1_week
)


/** Web `FileSelectionButton`: hidden only for `CHANNEL_TYPE_DM` (1:1 DM). */
fun canCreatePoll(channelType: Int): Boolean = channelType != CHANNEL_TYPE_DM

/**
 * Web `canPost`: question non-empty (trim) and **≥ [POLL_ANSWER_MIN_FOR_POST]** non-empty answers.
 * One filled answer alone cannot post — QA case “single answer” is expected to fail (by design).
 */
fun canPostPoll(state: CreatePollFormState): Boolean {
    val nonEmpty = state.answers.count { it.trim().isNotEmpty() }
    return state.question.trim().isNotEmpty() && nonEmpty >= POLL_ANSWER_MIN_FOR_POST
}

fun emojiIdForApi(emoji: EmojiItem): String =
    if (emoji.isForSale && emoji.src.isNotEmpty()) {
        emoji.src.substringAfterLast('/').substringBeforeLast('.')
    } else {
        emoji.id
    }

fun buildPollSubmitPayload(state: CreatePollFormState): PollSubmitPayload? {
    if (!canPostPoll(state)) return null
    val pairs = state.answers.mapIndexed { i, text -> i to text }
        .filter { (_, text) -> text.trim().isNotEmpty() }
    val filteredAnswers = pairs.map { it.second }
    val filteredEmojiIds = pairs.map { (i, _) -> state.answerEmojiIds.getOrElse(i) { "" } }
    val answersForApi = filteredAnswers.mapIndexed { i, text ->
        val trimmed = text.trim()
        val emojiId = filteredEmojiIds[i]
        if (emojiId.isNotEmpty()) "[e:$emojiId] $trimmed" else trimmed
    }
    val hours = state.duration.toIntOrNull() ?: 24
    val type = if (state.allowMultipleAnswers) 1 else 0
    return PollSubmitPayload(
        question = state.question.trim(),
        answers = answersForApi,
        expireHours = hours,
        pollType = type
    )
}
