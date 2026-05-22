package com.mezon.mobile.home.chat.thread

object ThreadStatus {
    const val ARCHIVED = 0
    const val JOINED = 1
    const val ACTIVE_PUBLIC = 2
    const val ACTIVE_PRIVATE = 3
}

const val THREAD_ARCHIVE_DURATION_SECONDS = 7L * 24 * 60 * 60

data class ThreadSection(
    val title: String,
    val threads: List<ThreadInfo>
)

data class ThreadListBuckets(
    val joined: List<ThreadInfo>,
    val other: List<ThreadInfo>,
    val archived: List<ThreadInfo>
)

fun filterThreadList(threads: List<ThreadInfo>): ThreadListBuckets {
    val joined = ArrayList<ThreadInfo>()
    val other = ArrayList<ThreadInfo>()
    val archived = ArrayList<ThreadInfo>()
    for (thread in threads) {
        when (thread.active) {
            ThreadStatus.JOINED -> joined.add(thread)
            ThreadStatus.ARCHIVED -> archived.add(thread)
            else -> other.add(thread)
        }
    }
    return ThreadListBuckets(joined, other, archived)
}

fun buildThreadSections(
    joinedTitle: String,
    activeTitle: String,
    archivedTitle: String,
    threads: List<ThreadInfo>
): List<ThreadSection> {
    val buckets = filterThreadList(threads)
    val sections = mutableListOf<ThreadSection>()
    if (buckets.joined.isNotEmpty()) {
        sections.add(ThreadSection(joinedTitle, buckets.joined))
    }
    if (buckets.other.isNotEmpty()) {
        sections.add(ThreadSection(activeTitle, buckets.other))
    }
    if (buckets.archived.isNotEmpty()) {
        sections.add(ThreadSection(archivedTitle, buckets.archived))
    }
    return sections
}
