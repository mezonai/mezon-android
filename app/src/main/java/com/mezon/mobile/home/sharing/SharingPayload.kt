package com.mezon.mobile.home.sharing

import android.net.Uri
import com.mezon.mobile.home.chat.AttachmentInfo

sealed class SharingPayload {
    data class FromDevice(
        val uris: List<Uri>,
        val text: String?,
        val mimeType: String?
    ) : SharingPayload()

    data class FromExistingAttachment(
        val attachment: AttachmentInfo
    ) : SharingPayload()

    data class ForwardFromChat(
        val sourceChannelId: Long,
        val sourceClanId: Long,
        val sourceChannelType: Int
    ) : SharingPayload()
}
