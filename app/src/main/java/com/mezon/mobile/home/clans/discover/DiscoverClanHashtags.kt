package com.mezon.mobile.home.clans.discover

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

internal fun parseDiscoverClanHashtags(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()

    val value = raw.trim()
    val jsonTags = if (value.startsWith('[')) {
        runCatching {
            (Json.parseToJsonElement(value) as? JsonArray)?.map { it.jsonPrimitive.content }
        }.getOrNull()
    } else null
    val tags = jsonTags ?: when {
        value.contains(',') -> value.split(',')
        value.startsWith('#') -> value.split(Regex("\\s+"))
        else -> listOf(value)
    }
    return tags.map { it.trim().removePrefix("#").trim() }.filter { it.isNotEmpty() }.distinct()
}
