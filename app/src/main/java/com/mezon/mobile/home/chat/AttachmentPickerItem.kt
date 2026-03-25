package com.mezon.mobile.home.chat

import android.net.Uri

/** Simple attachment description for uploads. */
data class AttachmentPickerItem(
    val uri: Uri,
    val filename: String,
    val mimeType: String,
    val size: Long,
    val width: Int = 0,
    val height: Int = 0,
    val duration: Int = 0
)

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
    companion object {
        const val IMAGE_MAX_FILE_SIZE = 50L * 1024 * 1024
        const val MAX_FILE_SIZE = 1024L * 1024 * 1024
        const val GALLERY_MAX_SELECTION = 20
    }
}
