package com.mezon.mobile.home.clans.settings

data class StickerUploadDraft(
    val name: String = "",
    val localImagePath: String? = null,
    val existingStickerId: Long? = null,
    val existingSourceUrl: String? = null,
    val isForSale: Boolean = false,
) {
    fun isNameValid(): Boolean = name.trim().length in 3..64
    fun isEditOnly(): Boolean = existingStickerId != null && localImagePath == null
}

const val MAX_STICKER_FILE_BYTES = 512_000
const val STICKER_DIMENSION = 320
const val STICKER_CATEGORY = "Among Us"
