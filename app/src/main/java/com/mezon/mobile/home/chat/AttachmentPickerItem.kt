package com.mezon.mobile.home.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

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
    }
}
