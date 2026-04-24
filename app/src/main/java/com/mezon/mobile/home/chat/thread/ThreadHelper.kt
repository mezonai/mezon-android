package com.mezon.mobile.home.chat.thread

object ThreadStatus {
    const val JOINED = 1
    const val ACTIVE_PUBLIC = 2
    const val ACTIVE_PRIVATE = 3
}

private const val THIRTY_DAYS_SECONDS = 30L * 24 * 60 * 60

const val THREAD_ARCHIVE_DURATION_SECONDS = 7L * 24 * 60 * 60

data class ThreadSection(
    val title: String,
    val threads: List<ThreadInfo>
)

fun getJoinedThreads(threads: List<ThreadInfo>): List<ThreadInfo> {
    val cutoff = System.currentTimeMillis() / 1000 - THIRTY_DAYS_SECONDS
    return threads.filter { it.active == ThreadStatus.JOINED && it.lastMessageTs > cutoff }
}

fun getActiveThreads(threads: List<ThreadInfo>): List<ThreadInfo> {
    val cutoff = System.currentTimeMillis() / 1000 - THIRTY_DAYS_SECONDS
    return threads.filter { it.active == ThreadStatus.ACTIVE_PUBLIC && it.lastMessageTs > cutoff }
}

fun getOlderThreads(threads: List<ThreadInfo>): List<ThreadInfo> {
    val cutoff = System.currentTimeMillis() / 1000 - THIRTY_DAYS_SECONDS
    return threads.filter { it.lastMessageTs > 0 && it.lastMessageTs <= cutoff }
}

fun buildThreadSections(threads: List<ThreadInfo>): List<ThreadSection> {
    val sections = mutableListOf<ThreadSection>()
    val joined = getJoinedThreads(threads)
    if (joined.isNotEmpty()) {
        val label = if (joined.size > 1) "${joined.size} Joined Threads" else "1 Joined Thread"
        sections.add(ThreadSection(label, joined))
    }
    val active = getActiveThreads(threads)
    if (active.isNotEmpty()) {
        val label = if (active.size > 1) "${active.size} Other Active Threads" else "1 Other Active Thread"
        sections.add(ThreadSection(label, active))
    }
    val older = getOlderThreads(threads)
    if (older.isNotEmpty()) {
        val label = if (older.size > 1) "${older.size} Older Threads" else "1 Older Thread"
        sections.add(ThreadSection(label, older))
    }
    return sections
}
