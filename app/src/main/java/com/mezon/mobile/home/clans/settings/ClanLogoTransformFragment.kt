package com.mezon.mobile.home.clans.settings

import android.os.Bundle
import androidx.core.content.FileProvider
import com.mezon.mobile.R
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.profile.AccountController
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.ui.shared.ImageTransformFragment
import java.io.File

class ClanLogoTransformFragment : ImageTransformFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"
        private const val MAX_LOGO_BYTES = 1 * 1024 * 1024

        fun newInstance(clanId: Long, uriString: String): ClanLogoTransformFragment =
            ClanLogoTransformFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CLAN_ID, clanId)
                    putString(ImageTransformFragment.ARG_URI, uriString)
                }
            }
    }

    private var clanId = 0L
    private lateinit var accountController: AccountController
    private lateinit var clansController: ClansController

    override fun onInject(entryPoint: FragmentEntryPoint) {
        accountController = entryPoint.accountController()
        clansController = entryPoint.clansController()
    }

    override fun onFragmentCreate(): Boolean {
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        if (clanId == 0L) return false
        return super.onFragmentCreate()
    }

    override fun maxSourceBytes(): Long = MAX_LOGO_BYTES.toLong()

    override fun maxExportBytes(): Int = MAX_LOGO_BYTES

    override fun cacheFilePrefix(): String = "clan_logo"

    override fun onSourceTooLarge() {
        MezonToast.show(
            this,
            ToastOverlay.ToastType.ERROR,
            getString(R.string.clan_settings_logo_too_large),
        )
        finishFragment()
    }

    override fun onDecodeFailed() {
        MezonToast.show(
            this,
            ToastOverlay.ToastType.ERROR,
            getString(R.string.clan_settings_logo_update_failed),
        )
        finishFragment()
    }

    override fun onExportTooLarge() {
        MezonToast.show(
            this,
            ToastOverlay.ToastType.ERROR,
            getString(R.string.clan_settings_logo_too_large),
        )
    }

    override fun onExportReady(jpegFile: File, onWorkFinished: () -> Unit) {
        val authCtx = getContext() ?: run {
            jpegFile.delete()
            onWorkFinished()
            return
        }
        val authority = "${authCtx.packageName}.fileprovider"
        val uploadUri = FileProvider.getUriForFile(authCtx, authority, jpegFile)
        accountController.uploadAvatar(uploadUri, authCtx.contentResolver, filenameUri = imageUri) { success, urlOrErr ->
            if (!success || urlOrErr.isBlank()) {
                jpegFile.delete()
                onWorkFinished()
                toastLogoError(if (!success) urlOrErr else null)
                return@uploadAvatar
            }
            clansController.updateClanLogo(clanId, urlOrErr) { ok, msg ->
                jpegFile.delete()
                onWorkFinished()
                if (ok) {
                    finishFragment()
                } else {
                    toastLogoError(msg)
                }
            }
        }
    }

    private fun toastLogoError(detail: String?) {
        val msg = detail?.takeIf { it.isNotBlank() }
            ?: getString(R.string.clan_settings_logo_update_failed)
        MezonToast.show(this, ToastOverlay.ToastType.ERROR, msg)
    }
}
