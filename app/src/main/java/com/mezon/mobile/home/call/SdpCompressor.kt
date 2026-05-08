package com.mezon.mobile.home.call

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object SdpCompressor {

    fun compress(input: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gzip ->
            gzip.write(input.toByteArray(Charsets.UTF_8))
        }
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    fun decompress(compressed: String): String {
        val bytes = Base64.decode(compressed, Base64.NO_WRAP)
        val bis = ByteArrayInputStream(bytes)
        return GZIPInputStream(bis).use { gzip ->
            gzip.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    fun canonicalizeWebRtcSdp(description: String): String {
        val t = description.trim()
        if (t.length < 3 || !t.startsWith("v=")) return description
        val lf = t.replace("\r\n", "\n").replace('\r', '\n')
        val joined = lf.split('\n').joinToString("\r\n")
        return if (joined.endsWith("\r\n")) joined else "$joined\r\n"
    }
}
