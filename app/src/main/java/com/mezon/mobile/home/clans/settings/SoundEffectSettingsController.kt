package com.mezon.mobile.home.clans.settings

import com.mezon.mobile.BuildConfig
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.chat.EmojiController
import com.mezon.mobile.home.chat.StickerItem
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SoundEffectSettingsController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val emojiController: EmojiController,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SoundEffectListUiState())
    val state: StateFlow<SoundEffectListUiState> = _state.asStateFlow()

    fun load(clanId: Long) {
        _state.update { it.copy(clanId = clanId, isLoading = true, errorMessage = null) }
        scope.launch(io) {
            try {
                emojiController.loadStickers()
                _state.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    fun soundsForClan(clanId: Long): List<StickerItem> =
        emojiController.soundsForClan(clanId)

    fun uploadNew(
        clanId: Long,
        displayName: String,
        wavBytes: ByteArray,
        recordId: Long,
        onDone: (success: Boolean, error: String?) -> Unit,
    ) {
        scope.launch(io) {
            try {
                val url = uploadSoundBytes(wavBytes, recordId)
                val id = idFromUploadUrl(url) ?: recordId
                sessionManager.withAutoRefresh { s ->
                    api.addClanSticker(
                        s.apiUrl, s.token,
                        id = id,
                        clanId = clanId,
                        source = url,
                        shortname = displayName.trim(),
                        category = SOUND_CATEGORY,
                        mediaType = StickerItem.MEDIA_TYPE_AUDIO,
                    )
                }
                emojiController.invalidateStickerCacheAndReload()
                load(clanId)
                onDone(true, null)
            } catch (e: Exception) {
                onDone(false, e.message)
            }
        }
    }

    fun updateNameOnly(
        clanId: Long,
        sound: StickerItem,
        newName: String,
        onDone: (success: Boolean, error: String?) -> Unit,
    ) {
        scope.launch(io) {
            try {
                sessionManager.withAutoRefresh { s ->
                    api.updateClanStickerById(
                        s.apiUrl, s.token,
                        id = sound.id.toLong(),
                        clanId = clanId,
                        source = sound.src,
                        shortname = newName.trim(),
                        category = sound.category.ifBlank { SOUND_CATEGORY },
                    )
                }
                emojiController.invalidateStickerCacheAndReload()
                load(clanId)
                onDone(true, null)
            } catch (e: Exception) {
                onDone(false, e.message)
            }
        }
    }

    fun delete(
        clanId: Long,
        sound: StickerItem,
        onDone: (success: Boolean, error: String?) -> Unit,
    ) {
        scope.launch(io) {
            try {
                sessionManager.withAutoRefresh { s ->
                    api.deleteClanStickerById(
                        s.apiUrl, s.token,
                        id = sound.id.toLong(),
                        clanId = clanId,
                        stickerLabel = sound.shortname,
                    )
                }
                emojiController.invalidateStickerCacheAndReload()
                load(clanId)
                onDone(true, null)
            } catch (e: Exception) {
                onDone(false, e.message)
            }
        }
    }

    private suspend fun uploadSoundBytes(wav: ByteArray, recordId: Long): String {
        val filename = "sounds/$recordId.wav"
        return sessionManager.withAutoRefresh { s ->
            val presign = api.uploadAttachmentFile(
                s.apiUrl, s.token, filename, "audio/wav", wav.size, 0, 0,
            )
            api.putFileToPresignedUrl(presign.url, wav, "audio/wav")
            "${BuildConfig.MEZON_BASE_IMG_URL}/${presign.filename}"
        }
    }

    private fun idFromUploadUrl(url: String): Long? {
        val name = url.substringAfterLast('/').substringBeforeLast('.')
        return name.toLongOrNull()
    }
}
