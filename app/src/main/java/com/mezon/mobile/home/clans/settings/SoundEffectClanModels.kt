package com.mezon.mobile.home.clans.settings

data class SoundEffectListUiState(
    val clanId: Long = 0L,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

data class SoundUploadDraft(
    val name: String = "",
    val localFilePath: String? = null,
    val existingUrl: String? = null,
    val existingSoundId: Long? = null,
    val trimStartSec: Float = 0f,
    val trimEndSec: Float = 0f,
) {
    fun isNameValid(): Boolean = name.trim().length in 3..64
    fun isEditOnly(): Boolean = existingSoundId != null && localFilePath == null
}

enum class SoundUploadMode { CREATE, EDIT }

const val MAX_SOUND_TRIM_SEC = 10f
const val MAX_SOUND_FILE_BYTES = 1_048_576
const val SOUND_CATEGORY = "Among Us"
