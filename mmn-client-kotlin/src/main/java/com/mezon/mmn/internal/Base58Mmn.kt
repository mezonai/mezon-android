package com.mezon.mmn.internal

import java.math.BigInteger

internal object Base58Mmn {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val decodeMap = IntArray(256) { -1 }.apply {
        ALPHABET.forEachIndexed { i, c -> this[c.code] = i }
    }

    fun encode(input: ByteArray): String {
        val inputCopy = input.copyOf()
        if (inputCopy.isEmpty()) return ""
        var zeros = 0
        while (zeros < inputCopy.size && inputCopy[zeros].toInt() == 0) zeros++
        val encoded = ByteArray(inputCopy.size * 2)
        var outputStart = encoded.size
        var inputStart = zeros
        while (inputStart < inputCopy.size) {
            var remainder = 0
            for (i in inputStart until inputCopy.size) {
                val temp = remainder * 256 + (inputCopy[i].toInt() and 0xFF)
                inputCopy[i] = (temp / 58).toByte()
                remainder = temp % 58
            }
            encoded[--outputStart] = remainder.toByte()
            if (inputCopy[inputStart].toInt() == 0) inputStart++
        }
        while (outputStart < encoded.size && encoded[outputStart].toInt() == 0) outputStart++
        while (--zeros >= 0) encoded[--outputStart] = 0
        val sb = StringBuilder(encoded.size - outputStart)
        for (i in outputStart until encoded.size) sb.append(ALPHABET[encoded[i].toInt()])
        return sb.toString()
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return byteArrayOf()
        var leadingOnes = 0
        while (leadingOnes < input.length && input[leadingOnes] == '1') {
            leadingOnes++
        }
        if (leadingOnes == input.length) {
            return ByteArray(leadingOnes)
        }
        val rest = input.substring(leadingOnes)
        var acc = BigInteger.ZERO
        var mult = BigInteger.ONE
        for (c in rest.reversed()) {
            val d = if (c.code < decodeMap.size) decodeMap[c.code] else -1
            require(d >= 0) { "Invalid base58" }
            acc = acc + mult * BigInteger.valueOf(d.toLong())
            mult = mult * BigInteger.valueOf(58)
        }
        val numBytes = if (acc == BigInteger.ZERO) {
            byteArrayOf()
        } else {
            var b = acc.toByteArray()
            if (b.isNotEmpty() && b[0] == 0.toByte() && b.size > 1) {
                b = b.copyOfRange(1, b.size)
            }
            b
        }
        return ByteArray(leadingOnes) { 0 } + numBytes
    }
}
