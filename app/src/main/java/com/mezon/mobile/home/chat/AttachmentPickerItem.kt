package com.mezon.mobile.home.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

data class AttachmentPickerItem(
    val id: Long,
    val uri: Uri,
    val path: String,
    val filename: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val duration: Int,
    val isVideo: Boolean,
    val isSelected: Boolean = false,
    val selectionIndex: Int = -1
) {

    val isFileType: Boolean
        get() = !mimeType.startsWith("image/") && !mimeType.startsWith("video/")

    companion object {
        const val IMAGE_MAX_FILE_SIZE = 50L * 1024 * 1024
        const val MAX_FILE_SIZE = 1024L * 1024 * 1024
        const val GALLERY_MAX_SELECTION = 20

        fun maxFileSizeBytes(mimeType: String): Long {
            return if (mimeType.startsWith("image/", ignoreCase = true)) {
                IMAGE_MAX_FILE_SIZE
            } else {
                MAX_FILE_SIZE
            }
        }

        fun isOverSizeLimit(sizeBytes: Long, mimeType: String): Boolean {
            if (sizeBytes <= 0L) return false
            return sizeBytes > maxFileSizeBytes(mimeType)
        }

        fun fromDocumentUri(context: Context, uri: Uri): AttachmentPickerItem? {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri) ?: "application/octet-stream"
            var displayName = "file"
            var fileSize = 0L

            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: "file"
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                }
            }

            return AttachmentPickerItem(
                id = uri.hashCode().toLong(),
                uri = uri,
                path = uri.toString(),
                filename = displayName,
                mimeType = mimeType,
                width = 0,
                height = 0,
                size = fileSize,
                duration = 0,
                isVideo = false
            )
        }

        fun fromPlainText(context: Context, content: String): AttachmentPickerItem? {
            val bytes = content.toByteArray(Charsets.UTF_8)
            val filename = "${System.currentTimeMillis()}.txt"
            val outFile = try {
                val dir = File(context.cacheDir, "text_attachments").apply { mkdirs() }
                File(dir, filename).apply { writeBytes(bytes) }
            } catch (_: IOException) {
                return null
            }

            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, outFile)
            return AttachmentPickerItem(
                id = contentUri.hashCode().toLong(),
                uri = contentUri,
                path = contentUri.toString(),
                filename = filename,
                mimeType = "text/plain",
                width = 0,
                height = 0,
                size = bytes.size.toLong(),
                duration = 0,
                isVideo = false
            )
        }
    }
}
