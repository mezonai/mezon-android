package com.mezon.mobile.util

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private data class FormEntry(
    var isMultiple: Boolean,
    val values: MutableList<String> = mutableListOf(),
)

object EmbedFormUtil {
    private val messageForm = ConcurrentHashMap<Long, ConcurrentHashMap<String, FormEntry>>()

    fun clearAll() {
        messageForm.clear()
    }

    fun clearMessage(messageId: Long) {
        messageForm.remove(messageId)
    }

    fun retainMessages(messageIds: Set<Long>) {
        for (id in messageForm.keys) {
            if (!messageIds.contains(id)) messageForm.remove(id)
        }
    }

    fun setValue(messageId: Long, componentId: String, value: String) {
        if (componentId.isEmpty()) return
        val row = messageForm.getOrPut(messageId) { ConcurrentHashMap() }
        row[componentId] = FormEntry(false, mutableListOf(value))
    }

    fun setMultiValues(messageId: Long, componentId: String, values: List<String>) {
        if (componentId.isEmpty()) return
        val row = messageForm.getOrPut(messageId) { ConcurrentHashMap() }
        row[componentId] = FormEntry(true, values.distinct().toMutableList())
    }

    fun toggleMultiValue(messageId: Long, componentId: String, value: String) {
        if (componentId.isEmpty()) return
        val row = messageForm.getOrPut(messageId) { ConcurrentHashMap() }
        val e = row.getOrPut(componentId) { FormEntry(true, mutableListOf()) }
        e.isMultiple = true
        val i = e.values.indexOf(value)
        if (i >= 0) e.values.removeAt(i) else e.values.add(value)
    }

    fun isValueSelected(messageId: Long, componentId: String, value: String): Boolean =
        messageForm[messageId]?.get(componentId)?.values?.contains(value) == true

    fun isComponentEmpty(messageId: Long, componentId: String): Boolean =
        messageForm[messageId]?.get(componentId)?.values.isNullOrEmpty()

    fun getValue(messageId: Long, componentId: String): String? =
        messageForm[messageId]?.get(componentId)?.values?.firstOrNull()

    fun getValuesForComponent(messageId: Long, componentId: String): List<String> =
        messageForm[messageId]?.get(componentId)?.values?.toList() ?: emptyList()

    fun reconcileComponentValues(
        messageId: Long,
        componentId: String,
        allowedValues: Collection<String>,
        multiple: Boolean,
    ) {
        if (componentId.isEmpty()) return
        val row = messageForm[messageId] ?: return
        val allowed = allowedValues.toHashSet()
        row.compute(componentId) { _, current ->
            if (current == null) return@compute null
            val retained = current.values.filter { it in allowed }.distinct()
            when {
                retained.isEmpty() -> null
                multiple -> FormEntry(true, retained.toMutableList())
                else -> FormEntry(false, mutableListOf(retained.first()))
            }
        }
        if (row.isEmpty()) messageForm.remove(messageId, row)
    }

    fun buildExtraDataJson(messageId: Long): String {
        val row = messageForm[messageId] ?: return ""
        if (row.isEmpty()) return ""
        val o = JSONObject()
        for ((k, e) in row) {
            if (e.values.isEmpty()) continue
            if (e.isMultiple) {
                val arr = JSONArray()
                for (v in e.values) arr.put(v)
                o.put(k, arr)
            } else {
                o.put(k, e.values[0])
            }
        }
        return if (o.length() == 0) "" else o.toString()
    }
}

data class EmbedInputComponentSpec(
    val placeholder: String,
    val defaultValue: String,
    val textarea: Boolean,
    val disabled: Boolean,
    val numberInput: Boolean,
    val dateInput: Boolean,
    val required: Boolean,
)

data class EmbedSelectOptionSpec(
    val label: String,
    val value: String,
    val defaultSelected: Boolean,
)

data class EmbedSelectSpec(
    val options: List<EmbedSelectOptionSpec>,
    val isMulti: Boolean,
    val minPick: Int,
    val maxPick: Int,
    val disabled: Boolean,
    val initialSelection: List<String>,
)

data class EmbedRadioOptionSpec(
    val label: String,
    val description: String,
    val value: String,
    val disabled: Boolean,
    val groupName: String,
    val extraData: List<String>,
)

data class EmbedRadioSpec(
    val options: List<EmbedRadioOptionSpec>,
    val multi: Boolean,
    val maxOptions: Int?,
    val disabled: Boolean,
)

data class EmbedAnimationSpec(
    val urlImage: String,
    val urlPosition: String,
    val pool: List<List<String>>,
    val durationSec: Float,
    val repeat: Int?,
    val vertical: Boolean,
    val isStaticResult: Boolean,
)

sealed class EmbedFieldInteractive {
    abstract val componentId: String

    data class Input(
        override val componentId: String,
        val input: EmbedInputComponentSpec,
    ) : EmbedFieldInteractive()

    data class Select(
        override val componentId: String,
        val input: EmbedSelectSpec,
    ) : EmbedFieldInteractive()

    data class Radio(
        override val componentId: String,
        val input: EmbedRadioSpec,
    ) : EmbedFieldInteractive()

    data class Animation(
        override val componentId: String,
        val input: EmbedAnimationSpec,
    ) : EmbedFieldInteractive()
}
