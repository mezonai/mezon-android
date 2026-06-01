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
import kotlinx.coroutines.launch
import java.util.concurrent.ThreadLocalRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StickerSettingsController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val emojiController: EmojiController,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun imageStickersForClan(clanId: Long): List<StickerItem> =
        emojiController.imageStickersForClan(clanId)

    fun loadStickers() = emojiController.loadStickers()

    fun create(
        clanId: Long,
        name: String,
        imageBytes: ByteArray,
        mimeType: String,
        isGif: Boolean,
        isForSale: Boolean,
        onDone: (success: Boolean, error: String?) -> Unit,
    ) {
        scope.launch(io) {
            try {
                val recordId = ThreadLocalRandom.current().nextLong(10_000_000_000_000L, Long.MAX_VALUE / 4)
                val ext = if (isGif) "gif" else "webp"
                val url = uploadStickerBytes(imageBytes, recordId, mimeType, ext)
                val id = idFromUploadUrl(url) ?: recordId
                sessionManager.withAutoRefresh { s ->
                    api.addClanSticker(
                        s.apiUrl, s.token,
                        id = id,
                        clanId = clanId,
                        source = url,
                        shortname = name.trim(),
                        category = STICKER_CATEGORY,
                        mediaType = StickerItem.MEDIA_TYPE_STICKER,
                        isForSale = isForSale,
                    )
                }
                emojiController.invalidateStickerCacheAndReload()
                onDone(true, null)
            } catch (e: Exception) {
                onDone(false, e.message)
            }
        }
    }

    fun rename(
        clanId: Long,
        sticker: StickerItem,
        newName: String,
        onDone: (success: Boolean, error: String?) -> Unit,
    ) {
        scope.launch(io) {
            try {
                sessionManager.withAutoRefresh { s ->
                    api.updateClanStickerById(
                        s.apiUrl, s.token,
                        id = sticker.id.toLong(),
                        clanId = clanId,
                        source = sticker.src,
                        shortname = newName.trim(),
                        category = sticker.category.ifBlank { STICKER_CATEGORY },
                    )
                }
                emojiController.invalidateStickerCacheAndReload()
                onDone(true, null)
            } catch (e: Exception) {
                onDone(false, e.message)
            }
        }
    }

    fun delete(
        clanId: Long,
        sticker: StickerItem,
        onDone: (success: Boolean, error: String?) -> Unit,
    ) {
        scope.launch(io) {
            try {
                sessionManager.withAutoRefresh { s ->
                    api.deleteClanStickerById(
                        s.apiUrl, s.token,
                        id = sticker.id.toLong(),
                        clanId = clanId,
                        stickerLabel = sticker.shortname,
                    )
                }
                emojiController.invalidateStickerCacheAndReload()
                onDone(true, null)
            } catch (e: Exception) {
                onDone(false, e.message)
            }
        }
    }

    private suspend fun uploadStickerBytes(bytes: ByteArray, recordId: Long, mime: String, ext: String): String {
        val filename = "stickers/$recordId.$ext"
        return sessionManager.withAutoRefresh { s ->
            val presign = api.uploadAttachmentFile(
                s.apiUrl, s.token, filename, mime, bytes.size,
                STICKER_DIMENSION, STICKER_DIMENSION,
            )
            api.putFileToPresignedUrl(presign.url, bytes, mime)
            "${BuildConfig.MEZON_BASE_IMG_URL}/${presign.filename}"
        }
    }

    private fun idFromUploadUrl(url: String): Long? {
        val name = url.substringAfterLast('/').substringBeforeLast('.')
        return name.toLongOrNull()
    }
}
