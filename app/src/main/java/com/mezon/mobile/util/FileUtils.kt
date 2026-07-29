package com.mezon.mobile.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

class ContentUriTooLargeException(val maxAllowedBytes: Int) : RuntimeException()

object FileUtils {

    suspend fun downloadMediaToGallery(context: android.content.Context, url: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val filename = url.substringAfterLast('/').substringBefore('?').ifEmpty { "download.jpg" }
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val destFile = java.io.File(downloadsDir, filename)
            
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connect()
            if (conn.responseCode !in 200..299) {
                return@withContext false
            }
            conn.inputStream.use { input ->
                java.io.FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            android.media.MediaScannerConnection.scanFile(context.applicationContext, arrayOf(destFile.absolutePath), null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    private const val STREAM_READ_CHUNK_BYTES = 8192


    fun getFileColor(filename: String, fallbackColor: Int): Int {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> 0xFFE53935.toInt()
            "doc", "docx" -> 0xFF1E88E5.toInt()
            "xls", "xlsx" -> 0xFF43A047.toInt()
            "ppt", "pptx" -> 0xFFF4511E.toInt()
            "zip", "rar", "7z", "tar", "gz" -> 0xFFFDD835.toInt()
            "mp3", "wav", "aac", "flac", "ogg" -> 0xFFE040FB.toInt()
            "txt", "csv", "log" -> 0xFF78909C.toInt()
            "apk" -> 0xFF66BB6A.toInt()
            "json", "xml", "html", "css", "js", "ts", "kt", "java", "py" -> 0xFF26C6DA.toInt()
            else -> fallbackColor
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return ""
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "%.1f MB".format(bytes / (1024f * 1024f))
        }
    }

    fun getPickedFileSize(cr: ContentResolver, uri: Uri): Long {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: return -1L
            return runCatching { File(path).length() }.getOrDefault(-1L)
        }
        val fromPfd = runCatching {
            cr.openFileDescriptor(uri, "r")?.use { pfd ->
                val s = pfd.statSize
                if (s >= 0) s else -1L
            } ?: -1L
        }.getOrDefault(-1L)
        if (fromPfd >= 0) return fromPfd
        return runCatching {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
    }

    fun readContentUriBytesCapped(cr: ContentResolver, uri: Uri, maxBytes: Int): ByteArray {
        require(maxBytes > 0)
        val input = cr.openInputStream(uri) ?: error("cannot open uri")
        return input.use { readStreamCappedIntoByteArray(it, maxBytes) }
    }

    private fun readStreamCappedIntoByteArray(input: InputStream, maxBytes: Int): ByteArray {
        val chunk = ByteArray(STREAM_READ_CHUNK_BYTES)
        val out = ByteArrayOutputStream(chunk.size.coerceAtMost(maxBytes))
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n <= 0) break
            if (total > maxBytes - n) {
                throw ContentUriTooLargeException(maxBytes)
            }
            out.write(chunk, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
