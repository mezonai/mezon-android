package com.mezon.mobile.home.chat.poll

import com.mezon.mobile.home.chat.MessageEntity

data class PollLocalState(
    val selection: Set<Int> = emptySet(),
    val showResultsPreview: Boolean = false,
    val optimisticMyIndices: List<Int>? = null,
    val optionsExpanded: Boolean = false,
    val displayMergedPoll: ParsedPoll? = null,
) {
    fun fingerprint(): Int {
        var h = selection.size
        for (ix in selection.sorted()) h = h * 31 + ix
        h = h * 31 + showResultsPreview.hashCode()
        h = h * 31 + (optimisticMyIndices?.hashCode() ?: 0)
        h = h * 31 + optionsExpanded.hashCode()
        h = h * 31 + (displayMergedPoll.hashCode())
        return h
    }
}

sealed class PollTap {
    data class ToggleOption(val answerIndex: Int) : PollTap()
    data object PrimaryAction : PollTap()
    /** "View details" link — opens full voter breakdown modal. */
    data object ViewDetails : PollTap()
    data object ToggleExpandOptions : PollTap()
}

fun PollLocalState.withSelection(transform: (Set<Int>) -> Set<Int>): PollLocalState =
    copy(selection = transform(selection))

interface ChatPollBridge {
    fun getLocalState(messageId: Long): PollLocalState
    fun stateFingerprint(messageId: Long): Int
    fun onPollTap(msg: MessageEntity, parsed: ParsedPoll, tap: PollTap)
    fun pollForLayout(messageId: Long, contentParsed: ParsedPoll): ParsedPoll
}
