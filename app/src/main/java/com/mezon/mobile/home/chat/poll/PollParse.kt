package com.mezon.mobile.home.chat.poll

import com.mezon.mezon.api.CreatePollResponse
import org.json.JSONArray
import org.json.JSONObject

data class PollAnswerItem(val index: Int, val label: String)

data class PollVoterEntry(val answerIndex: Int, val userIds: List<Long>)

data class ParsedPoll(
    val pollId: Long,
    val question: String,
    val answers: List<PollAnswerItem>,
    val countsByIndex: Map<Int, Int>,
    val totalVotes: Int,
    val expireAtSeconds: Long,
    val isClosed: Boolean,
    val isMultiple: Boolean,
    val voterDetails: List<PollVoterEntry>
) {
    fun countFor(answerIndex: Int): Int = countsByIndex[answerIndex] ?: 0
}

/** Message `content` JSON from [CreatePollResponse] for optimistic UI before websocket. */
fun buildPollMessageContent(response: CreatePollResponse): String {
    val obj = JSONObject()
    obj.put("question", response.question)
    if (response.pollId != 0L) obj.put("poll_id", response.pollId)
    if (response.exp != 0L) obj.put("expire_at", response.exp)
    obj.put("type", response.type.number)
    obj.put("is_closed", response.isClosed)
    if (response.totalVotes > 0) obj.put("total_votes", response.totalVotes)
    val sortedAnswers = response.answersList.sortedBy { it.index }
    val answersArr = JSONArray()
    for (a in sortedAnswers) {
        answersArr.put(
            JSONObject().apply {
                put("index", a.index)
                put("label", a.label)
            }
        )
    }
    obj.put("answers", answersArr)
    val countsArr = JSONArray()
    for (i in sortedAnswers.indices) {
        countsArr.put(if (i < response.answerCountsCount) response.getAnswerCounts(i) else 0)
    }
    obj.put("answer_counts", countsArr)
    return obj.toString()
}

fun isPollContentJson(content: String): Boolean {
    if (content.isBlank()) return false
    return try {
        val obj = JSONObject(content)
        val answers = obj.optJSONArray("answers")
        answers != null && answers.length() > 0
    } catch (_: Exception) {
        false
    }
}

private fun parseLongFlexible(v: Any?): Long = when (v) {
    is Number -> v.toLong()
    is String -> v.toLongOrNull() ?: 0L
    else -> 0L
}

private fun parseUserIdsArray(arr: JSONArray?): List<Long> {
    if (arr == null || arr.length() == 0) return emptyList()
    val out = ArrayList<Long>(arr.length())
    for (i in 0 until arr.length()) {
        when (val v = arr.opt(i)) {
            is Number -> out.add(v.toLong())
            is String -> v.toLongOrNull()?.let { out.add(it) }
            else -> {}
        }
    }
    return out
}

private fun parseCounts(obj: JSONObject, answersInJsonOrder: List<PollAnswerItem>): Map<Int, Int> {
    val raw = obj.opt("answer_counts") ?: return emptyMap()
    val map = mutableMapOf<Int, Int>()
    when (raw) {
        is JSONObject -> {
            val keys = raw.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val idx = k.toIntOrNull() ?: continue
                map[idx] = raw.optInt(k, 0)
            }
        }
        is JSONArray -> {
            for (i in 0 until raw.length()) {
                val ans = answersInJsonOrder.getOrNull(i) ?: continue
                map[ans.index] = raw.optInt(i, 0)
            }
        }
    }
    return map
}

private fun parseVoterDetails(obj: JSONObject): List<PollVoterEntry> {
    val arr = obj.optJSONArray("voter_details") ?: return emptyList()
    val out = ArrayList<PollVoterEntry>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val ai = o.optInt("answer_index", o.optInt("answerIndex", -1))
        if (ai < 0) continue
        val users = o.optJSONArray("user_ids") ?: o.optJSONArray("userIds")
        out.add(PollVoterEntry(ai, parseUserIdsArray(users)))
    }
    return out
}

fun parsePollContent(raw: String): ParsedPoll? {
    if (raw.isBlank()) return null
    return try {
        val obj = JSONObject(raw)
        val question = obj.optString("question").ifBlank { obj.optString("t") }
        val answersArr = obj.optJSONArray("answers") ?: return null
        val items = ArrayList<PollAnswerItem>(answersArr.length())
        for (i in 0 until answersArr.length()) {
            when (val el = answersArr.opt(i)) {
                is String -> items.add(PollAnswerItem(i, el))
                is JSONObject -> {
                    val idx = el.optInt("index", i)
                    val label = el.optString("label").ifBlank { el.optString("text") }
                    items.add(PollAnswerItem(idx, label.ifBlank { " " }))
                }
            }
        }
        if (items.isEmpty()) return null
        val sorted = items.sortedBy { it.index }
        val counts = parseCounts(obj, items)
        val totalFromField = obj.optInt("total_votes", obj.optInt("totalVotes", -1))
        val totalVotes = if (totalFromField >= 0) totalFromField
        else counts.values.sum().coerceAtLeast(0)
        val exp = when {
            obj.has("expire_at") -> obj.optLong("expire_at", 0L)
            obj.has("expireAt") -> obj.optLong("expireAt", 0L)
            obj.has("exp") -> obj.optLong("exp", 0L)
            else -> 0L
        }
        val pollId = when {
            obj.has("poll_id") -> parseLongFlexible(obj.opt("poll_id"))
            obj.has("pollId") -> parseLongFlexible(obj.opt("pollId"))
            else -> 0L
        }
        val type = obj.optInt("type", 0)
        ParsedPoll(
            pollId = pollId,
            question = question.ifBlank { " " },
            answers = sorted,
            countsByIndex = counts,
            totalVotes = totalVotes,
            expireAtSeconds = exp,
            isClosed = obj.optBoolean("is_closed", obj.optBoolean("isClosed", false)),
            isMultiple = type == 1,
            voterDetails = parseVoterDetails(obj)
        )
    } catch (_: Exception) {
        null
    }
}

fun votedAnswerIndices(parsed: ParsedPoll, currentUserId: Long): List<Int> {
    if (currentUserId == 0L) return emptyList()
    val hits = mutableListOf<Int>()
    for (d in parsed.voterDetails) {
        if (d.userIds.contains(currentUserId)) hits.add(d.answerIndex)
    }
    return hits.distinct()
}

fun mergePollFromGetResponse(base: ParsedPoll, resp: com.mezon.mezon.api.GetPollResponse): ParsedPoll {
    val answers = resp.answersList.map { PollAnswerItem(it.index, it.label) }.sortedBy { it.index }
    val counts = mutableMapOf<Int, Int>()
    val al = resp.answersList
    val ac = resp.answerCountsList
    val n = minOf(al.size, ac.size)
    for (i in 0 until n) {
        counts[al[i].index] = ac[i]
    }
    val voters = resp.voterDetailsList.map {
        PollVoterEntry(it.answerIndex, it.userIdsList)
    }
    return ParsedPoll(
        pollId = if (resp.pollId != 0L) resp.pollId else base.pollId,
        question = resp.question.ifEmpty { base.question },
        answers = if (answers.isNotEmpty()) answers else base.answers,
        countsByIndex = if (counts.isNotEmpty()) counts else base.countsByIndex,
        totalVotes = if (resp.totalVotes > 0) resp.totalVotes else base.totalVotes,
        expireAtSeconds = if (resp.exp > 0) resp.exp else base.expireAtSeconds,
        isClosed = resp.isClosed || base.isClosed,
        isMultiple = resp.type == com.mezon.mezon.api.PollType.MULTIPLE || base.isMultiple,
        voterDetails = if (voters.isNotEmpty()) voters else base.voterDetails
    )
}
