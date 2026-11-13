package com.mezon.mobile.home.profile

import android.graphics.BitmapFactory
import android.os.Bundle
import com.mezon.mobile.R
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.ui.shared.ImageTransformFragment
import com.mezon.mobile.util.FileUtils
import com.mezon.mobile.util.PROFILE_COVER_HEIGHT_PX
import com.mezon.mobile.util.PROFILE_COVER_WIDTH_PX
import com.mezon.mobile.util.profileCoverImgproxyUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ProfileCoverTransformFragment : ImageTransformFragment() {

    companion object {
        private const val MAX_COVER_BYTES = 10 * 1024 * 1024

        fun newInstance(uriString: String): ProfileCoverTransformFragment =
            ProfileCoverTransformFragment().apply {
                arguments = Bundle().apply {
                    putString(ImageTransformFragment.ARG_URI, uriString)
                }
            }
    }

    var onCoverUploaded: ((String) -> Unit)? = null

    private lateinit var accountController: AccountController
    private lateinit var ioDispatcher: CoroutineDispatcher
    private lateinit var mainDispatcher: CoroutineDispatcher

    override fun onInject(entryPoint: FragmentEntryPoint) {
        accountController = entryPoint.accountController()
        ioDispatcher = entryPoint.ioDispatcher()
        mainDispatcher = entryPoint.mainDispatcher()
    }

    override fun cropAspectWidth(): Float = PROFILE_COVER_WIDTH_PX.toFloat()

    override fun cropAspectHeight(): Float = PROFILE_COVER_HEIGHT_PX.toFloat()

    override fun exportWidthPx(): Int = PROFILE_COVER_WIDTH_PX

    override fun exportHeightPx(): Int = PROFILE_COVER_HEIGHT_PX

    override fun maxSourceBytes(): Long = MAX_COVER_BYTES.toLong()

    override fun maxExportBytes(): Int = MAX_COVER_BYTES

    override fun cacheFilePrefix(): String = "profile_cover"

    override fun onSourceTooLarge() {
        showError(R.string.edit_profile_cover_upload_failed)
        finishFragment()
    }

    override fun onDecodeFailed() {
        showError(R.string.image_editor_failed)
        finishFragment()
    }

    override fun onExportTooLarge() {
        showError(R.string.edit_profile_cover_upload_failed)
    }

    override fun onExportReady(jpegFile: File, onWorkFinished: () -> Unit) {
        val appContext = requireContext().applicationContext
        val resolver = appContext.contentResolver
        fragmentScope.launch(mainDispatcher) {
            runCatching {
                val (bytes, originalFilename) = withContext(ioDispatcher) {
                    val bytes = jpegFile.readBytes()
                    ensureActive()
                    bytes to FileUtils.getPickedFileName(resolver, imageUri)
                }
                runCatching { jpegFile.delete() }
                val url = accountController.uploadCoverJpeg(bytes, originalFilename)
                withContext(ioDispatcher) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }?.let { preview ->
                    MezonImageLoader.getInstance(appContext).cacheBitmap(
                        profileCoverImgproxyUrl(url), PROFILE_COVER_WIDTH_PX, PROFILE_COVER_HEIGHT_PX, preview
                    )
                }
                onWorkFinished()
                onCoverUploaded?.invoke(url)
                finishFragment()
            }.onFailure { error ->
                runCatching { jpegFile.delete() }
                if (error is CancellationException) throw error
                onWorkFinished()
                showError(R.string.edit_profile_cover_upload_failed)
            }
        }
    }

    private fun showError(messageRes: Int) {
        MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(messageRes))
    }
}
