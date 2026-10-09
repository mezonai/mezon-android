package com.mezon.mobile.home.sharing

import java.text.Normalizer
import java.util.Locale

/** Pure ranking of immutable snapshots; callers run this outside the UI thread. */
internal object SharingSearchRanking {
    data class Candidate<T>(
        val item: T,
        val displayName: String,
        val username: String = "",
        val clanName: String = "",
    )

    private const val UNMATCHED_RANK = 15

    fun <T> rank(
        candidates: List<Candidate<T>>,
        query: String,
        includeUnmatched: Boolean = true,
        checkActive: () -> Unit = {},
    ): List<T> {
        val needle = normalize(query)
        if (needle.isEmpty()) return candidates.map { it.item }
        val queryWords = words(needle)
        // Fixed buckets preserve source order for ties without comparator-based sorting.
        val buckets = Array(UNMATCHED_RANK + 1) { ArrayList<T>() }
        for ((index, candidate) in candidates.withIndex()) {
            // Stop superseded searches without a cancellation check for every string operation.
            if ((index and 63) == 0) checkActive()
            val name = normalize(candidate.displayName)
            var rank = match(name, needle, queryWords)?.times(2) ?: UNMATCHED_RANK
            if (rank != 0 && candidate.username.isNotEmpty()) {
                val username = normalize(candidate.username)
                if (username != name) {
                    match(username, needle, queryWords)?.let { rank = minOf(rank, it * 2 + 1) }
                }
            }
            // Clan context is weaker than every destination-name or username match.
            if (rank == UNMATCHED_RANK && candidate.clanName.isNotEmpty()) {
                match(normalize(candidate.clanName), needle, queryWords)?.let { rank = 10 + it }
            }
            if (includeUnmatched || rank != UNMATCHED_RANK) buckets[rank].add(candidate.item)
        }
        return ArrayList<T>(candidates.size).apply {
            for (bucket in buckets) addAll(bucket)
        }
    }

    private fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKD).lowercase(Locale.ROOT)
        val result = StringBuilder(folded.length)
        var pendingSpace = false
        var index = 0
        while (index < folded.length) {
            val codePoint = folded.codePointAt(index)
            index += Character.charCount(codePoint)
            when (Character.getType(codePoint)) {
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt() -> continue
            }
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = result.isNotEmpty()
            } else {
                if (pendingSpace) result.append(' ')
                pendingSpace = false
                result.appendCodePoint(if (codePoint == 'đ'.code) 'd'.code else codePoint)
            }
        }
        return result.toString()
    }

    private fun words(text: String): List<String> {
        val result = ArrayList<String>()
        var wordStart = -1
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isLetterOrDigit(codePoint)) {
                if (wordStart == -1) wordStart = index
            } else if (wordStart != -1) {
                result.add(text.substring(wordStart, index))
                wordStart = -1
            }
            index += Character.charCount(codePoint)
        }
        if (wordStart != -1) result.add(text.substring(wordStart))
        return result
    }

    private fun match(text: String, query: String, queryWords: List<String>): Int? {
        if (text.isEmpty()) return null
        if (text == query) return 0
        if (text.startsWith(query)) return 1

        var searchStart = 0
        var containsQuery = false
        while (true) {
            val index = text.indexOf(query, searchStart)
            if (index < 0) break
            containsQuery = true
            if (index == 0) return 1
            if (!Character.isLetterOrDigit(text.codePointBefore(index))) return 2
            searchStart = index + query.length
        }

        // Ordered partial words: "ng van" matches "Nguyễn Văn", without fuzzy matching.
        if (queryWords.size > 1) {
            val textWords = words(text)
            var nextWord = 0
            var matchedWords = 0
            for (queryWord in queryWords) {
                while (nextWord < textWords.size && !textWords[nextWord].startsWith(queryWord)) nextWord++
                if (nextWord == textWords.size) break
                matchedWords++
                nextWord++
            }
            if (matchedWords == queryWords.size) return 3
        }
        return if (containsQuery) 4 else null
    }
}
