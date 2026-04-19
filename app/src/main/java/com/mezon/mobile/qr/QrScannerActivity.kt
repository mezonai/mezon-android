package com.mezon.mobile.qr

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class QrScannerActivity : ComponentActivity() {
    private val viewModel: QrViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                QrScannerScreen(
                    viewModel = viewModel,
                    onNavigateProfile = { username, data ->
                        startActivity(ProfileDetailActivity.newIntent(this, username, data))
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
                        Toast.makeText(this, getString(com.mezon.mobile.R.string.qr_transfer_not_supported), Toast.LENGTH_SHORT).show()
                    },
                    onNavigateDeepLink = { value ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
                    },
                    onClose = { finish() }
                )
            }
        }
    }
}


