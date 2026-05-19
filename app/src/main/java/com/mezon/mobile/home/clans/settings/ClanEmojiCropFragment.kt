package com.mezon.mobile.home.clans.settings

import android.graphics.Bitmap
import android.os.Bundle
import com.mezon.mobile.R
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.ui.shared.ImageTransformFragment
import java.io.File

class ClanEmojiCropFragment : ImageTransformFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"
        private const val MAX_EMOJI_QUALITY = 95
        private const val MAX_EMOJI_QUALITY_FLOOR = 40
        private const val EMOJI_QUALITY_STEP = 10
        private const val MAX_EMOJI_BYTES = 256 * 1024
        private const val EMOJI_EXPORT_PX = 128

        fun newInstance(clanId: Long, uriString: String): ClanEmojiCropFragment =
            ClanEmojiCropFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CLAN_ID, clanId)
                    putString(ImageTransformFragment.ARG_URI, uriString)
                }
            }
    }

    private var clanId = 0L

    override fun onFragmentCreate(): Boolean {
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        if (clanId == 0L) return false
        return super.onFragmentCreate()
    }

    override fun maxSourceBytes(): Long = MAX_EMOJI_BYTES.toLong()

    override fun maxExportBytes(): Int = MAX_EMOJI_BYTES

    override fun exportWidthPx(): Int = EMOJI_EXPORT_PX

    override fun exportHeightPx(): Int = EMOJI_EXPORT_PX

    override fun cacheFilePrefix(): String = "clan_emoji_crop"

    override fun onSourceTooLarge() {
        MezonToast.show(
            this,
            ToastOverlay.ToastType.ERROR,
            getString(R.string.clan_emoji_error_size_limit),
        )
        finishFragment()
    }

    override fun onDecodeFailed() {
        MezonToast.show(
            this,
            ToastOverlay.ToastType.ERROR,
            getString(R.string.clan_emoji_upload_failed),
        )
        finishFragment()
    }

    override fun onExportTooLarge() {
        MezonToast.show(
            this,
            ToastOverlay.ToastType.ERROR,
            getString(R.string.clan_emoji_error_export_too_large),
        )
    }

    override fun writeJpegUnderCap(bitmap: Bitmap, maxBytes: Int): File? {
        val ctx = getContext() ?: return null
        val file = File(ctx.cacheDir, "${cacheFilePrefix()}_${System.currentTimeMillis()}.webp")
        var quality = MAX_EMOJI_QUALITY
        while (quality >= MAX_EMOJI_QUALITY_FLOOR) {
            java.io.FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.WEBP, quality, stream)
            }
            val len = file.length()
            if (len in 1..maxBytes.toLong()) return file
            quality -= EMOJI_QUALITY_STEP
        }
        file.delete()
        return super.writeJpegUnderCap(bitmap, maxBytes)
    }

    override fun onExportReady(jpegFile: File, onWorkFinished: () -> Unit) {
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.clanEmojiCropExportReady,
            clanId,
            jpegFile.absolutePath,
        )
        onWorkFinished()
        finishFragment()
    }
}
