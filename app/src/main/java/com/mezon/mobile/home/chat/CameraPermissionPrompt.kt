package com.mezon.mobile.home.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertsCreator

object CameraPermissionPrompt {
    fun show(context: Context) {
        AlertsCreator.createConfirmDialog(
            context,
            context.getString(R.string.camera_permission_denied_title),
            context.getString(R.string.camera_permission_denied_message),
            confirmText = context.getString(R.string.common_open_settings),
            cancelText = context.getString(R.string.common_cancel)
        ) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            runCatching { context.startActivity(intent) }
        }.show()
    }
}
