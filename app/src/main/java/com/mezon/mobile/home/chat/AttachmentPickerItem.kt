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

