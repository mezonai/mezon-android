package com.mezon.mobile.home.stream

/** Keep surviving avatars in place, fill vacancies, and bound profile/image work. */
internal fun selectStreamingViewers(previous: List<Long>, joined: List<Long>, limit: Int = 5): List<Long> {
    val membership = joined.toHashSet()
    val result = previous.filter { it in membership }.distinct().take(limit).toMutableList()
    for (id in joined) {
        if (result.size >= limit) break
        if (id !in result) result.add(id)
    }
    return result
}
