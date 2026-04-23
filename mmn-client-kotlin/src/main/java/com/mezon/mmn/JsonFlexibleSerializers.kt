package com.mezon.mmn

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal object JsonRpcIdSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("JsonRpcId", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        val e = (decoder as JsonDecoder).decodeJsonElement()
        if (e is JsonNull) return 0L
        val p = e as? JsonPrimitive ?: return 0L
        p.longOrNull?.let { return it }
        p.content.trim().toLongOrNull()?.let { return it }
        p.doubleOrNull?.let { d ->
            if (d.isFinite() && d % 1.0 == 0.0) {
                val l = d.toLong()
                if (l.toDouble() == d) return l
            }
        }
        return 0L
    }

    override fun serialize(encoder: Encoder, value: Long) {
        (encoder as JsonEncoder).encodeJsonElement(JsonPrimitive(value))
    }
}

internal object FlexibleStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return elementToFlexibleString(element)
    }

    override fun serialize(encoder: Encoder, value: String) {
        (encoder as JsonEncoder).encodeJsonElement(JsonPrimitive(value))
    }
}

internal object FlexibleLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        val e = (decoder as JsonDecoder).decodeJsonElement()
        if (e is JsonNull) return 0L
        val p = e as? JsonPrimitive ?: return 0L
        p.longOrNull?.let { return it }
        p.content.trim().let { s ->
            s.toLongOrNull()?.let { return it }
            s.toULongOrNull()?.let { u ->
                if (u <= Long.MAX_VALUE.toULong()) return u.toLong()
            }
        }
        p.doubleOrNull?.let { d ->
            if (d.isFinite() && d % 1.0 == 0.0) {
                val l = d.toLong()
                if (l.toDouble() == d) return l
            }
        }
        return 0L
    }

    override fun serialize(encoder: Encoder, value: Long) {
        (encoder as JsonEncoder).encodeJsonElement(JsonPrimitive(value))
    }
}

internal object MmnAccountDecimalsSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("MmnAccountDecimals", PrimitiveKind.INT)

    private const val DefaultDecimals = 6

    override fun deserialize(decoder: Decoder): Int {
        val e = (decoder as JsonDecoder).decodeJsonElement()
        if (e is JsonNull) return DefaultDecimals
        val p = e as? JsonPrimitive ?: return DefaultDecimals
        p.intOrNull?.let { return it }
        p.longOrNull?.let { l -> if (l in Int.MIN_VALUE..Int.MAX_VALUE) return l.toInt() }
        p.content.trim().toIntOrNull()?.let { return it }
        p.doubleOrNull?.let { d ->
            if (d.isFinite() && d % 1.0 == 0.0) {
                val i = d.toInt()
                if (i.toDouble() == d) return i
            }
        }
        return DefaultDecimals
    }

    override fun serialize(encoder: Encoder, value: Int) {
        (encoder as JsonEncoder).encodeJsonElement(JsonPrimitive(value))
    }
}

private fun elementToFlexibleString(element: JsonElement): String {
    if (element is JsonNull) return ""
    val p = element as? JsonPrimitive ?: return element.toString()
    if (p.isString) return p.content
    p.longOrNull?.let { return it.toString() }
    p.doubleOrNull?.let { d ->
        if (d.isFinite() && d % 1.0 == 0.0) {
            val l = d.toLong()
            if (l.toDouble() == d) return l.toString()
        }
        return p.content
    }
    p.booleanOrNull?.let { return it.toString() }
    return p.content
}
