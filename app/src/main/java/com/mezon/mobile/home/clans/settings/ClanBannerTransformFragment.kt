package com.mezon.mobile.home.clans.settings

import android.os.Bundle
import com.mezon.mobile.R
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.ui.shared.ImageTransformFragment
import com.mezon.mobile.util.FileUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ClanBannerTransformFragment : ImageTransformFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"
        private const val MAX_BANNER_BYTES = 10 * 1024 * 1024
        private const val EXPORT_W = 1920
        private const val EXPORT_H = 1080

        fun newInstance(clanId: Long, uriString: String): ClanBannerTransformFragment =
            ClanBannerTransformFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CLAN_ID, clanId)
                    putString(ImageTransformFragment.ARG_URI, uriString)
                }
            }
    }

    private var clanId = 0L
    private lateinit var clansController: ClansController
    private lateinit var ioDispatcher: CoroutineDispatcher
    private lateinit var mainDispatcher: CoroutineDispatcher

    override fun onInject(entryPoint: FragmentEntryPoint) {
        clansController = entryPoint.clansController()
        ioDispatcher = entryPoint.ioDispatcher()
        mainDispatcher = entryPoint.mainDispatcher()
    }

    override fun onFragmentCreate(): Boolean {
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        if (clanId == 0L) return false
        return super.onFragmentCreate()
    }

    override fun cropAspectWidth(): Float = 16f

    override fun cropAspectHeight(): Float = 9f

    override fun exportWidthPx(): Int = EXPORT_W

    override fun exportHeightPx(): Int = EXPORT_H

    override fun maxSourceBytes(): Long = MAX_BANNER_BYTES.toLong()

    override fun maxExportBytes(): Int = MAX_BANNER_BYTES

    override fun cacheFilePrefix(): String = "clan_banner"

    override fun onSourceTooLarge() {
        MezonToast.show(
            this,
            ToastOverlay.ToastType.ERROR,
            getString(R.string.clan_image_too_large, 10),
        )
        finishFragment()
    }

    override fun onDecodeFailed() {
        MezonToast.show(
            this,
            ToastOverlay.ToastType.ERROR,
            getString(R.string.clan_overview_save_error),
        )
        finishFragment()
    }

    override fun onExportTooLarge() {
        MezonToast.show(
            this,
            ToastOverlay.ToastType.ERROR,
            getString(R.string.clan_image_too_large, 10),
        )
    }

    override fun onExportReady(jpegFile: File, onWorkFinished: () -> Unit) {
        val resolver = getContext()?.contentResolver
        fragmentScope.launch(mainDispatcher) {
            runCatching {
                val bytes = withContext(ioDispatcher) { jpegFile.readBytes() }
                val originalFilename = withContext(ioDispatcher) {
                    resolver?.let { FileUtils.getPickedFileName(it, imageUri) }
                }
                runCatching { jpegFile.delete() }
                val url = clansController.uploadClanBannerJpeg(bytes, originalFilename)
                onWorkFinished()
                notificationCenter.postNotificationNameOnUIThread(
                    NotificationCenter.clanBannerCropped,
                    clanId,
                    url,
                )
                finishFragment()
            }.onFailure {
                runCatching { jpegFile.delete() }
                onWorkFinished()
                MezonToast.show(
                    this@ClanBannerTransformFragment,
                    ToastOverlay.ToastType.ERROR,
                    getString(R.string.clan_overview_save_error),
                )
                finishFragment()
            }
        }
    }
}
