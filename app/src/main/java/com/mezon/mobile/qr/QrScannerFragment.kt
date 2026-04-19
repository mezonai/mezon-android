package com.mezon.mobile.qr

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class QrScannerFragment : Fragment() {
    private val viewModel: QrViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val composeView = androidx.compose.ui.platform.ComposeView(requireContext())
        composeView.setContent {
            QrScannerScreen(
                viewModel = viewModel,
                onNavigateProfile = { username, data ->
                    parentFragmentManager.beginTransaction()
                        .replace((container?.id ?: android.R.id.content), ProfileDetailFragment.newInstance(username, data))
                        .addToBackStack(null)
                        .commit()
                },
                onNavigateInvite = { inviteId ->
                    val url = com.mezon.mobile.BuildConfig.MEZON_REDIRECT_URI + "/invite/" + inviteId
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
                onNavigateLuckyMoney = { luckyMoneyId ->
                    val url = com.mezon.mobile.BuildConfig.MEZON_REDIRECT_URI + "/lucky-money/" + luckyMoneyId
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
                onNavigateTransfer = {
                    Toast.makeText(requireContext(), getString(com.mezon.mobile.R.string.qr_transfer_not_supported), Toast.LENGTH_SHORT).show()
                },
                onNavigateDeepLink = { value ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
                },
                onClose = { parentFragmentManager.popBackStack() }
            )
        }
        return composeView
    }
}
