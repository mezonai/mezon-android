package com.mezon.mobile.util

object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

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
}
