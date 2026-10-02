package com.mezon.mobile.ui.cells

import android.text.InputFilter
import android.text.Spanned

internal class Utf8ByteLengthFilter(
    private val maxBytes: Int,
) : InputFilter {

    init {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
    }

    override fun filter(
        source: CharSequence,
        start: Int,
        end: Int,
        dest: Spanned,
        dstart: Int,
        dend: Int,
    ): CharSequence? {
        val retainedText = buildString(dest.length - (dend - dstart)) {
            append(dest, 0, dstart)
            append(dest, dend, dest.length)
        }
        val remainingBytes = maxBytes - retainedText.utf8ByteCount()
        val replacement = source.subSequence(start, end)

        if (replacement.utf8ByteCount() <= remainingBytes) return null
        if (remainingBytes <= 0) return ""

        return replacement.utf8Prefix(remainingBytes)
    }
}

internal fun CharSequence.utf8ByteCount(): Int = toString().toByteArray(Charsets.UTF_8).size

internal fun CharSequence.utf8Prefix(maxBytes: Int): CharSequence {
    if (maxBytes <= 0) return ""

    var byteCount = 0
    var endIndex = 0
    while (endIndex < length) {
        val codePoint = Character.codePointAt(this, endIndex)
        val codePointBytes = when {
            codePoint <= 0x7F -> 1
            codePoint <= 0x7FF -> 2
            codePoint <= 0xFFFF -> 3
            else -> 4
        }
        if (byteCount + codePointBytes > maxBytes) break

        byteCount += codePointBytes
        endIndex += Character.charCount(codePoint)
    }
    return subSequence(0, endIndex)
}
