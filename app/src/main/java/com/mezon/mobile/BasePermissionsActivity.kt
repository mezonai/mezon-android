package com.mezon.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.fragment.app.FragmentActivity
import com.mezon.mobile.core.AlertDialog

open class BasePermissionsActivity : FragmentActivity() {

    companion object {
        const val REQUEST_CODE_GEOLOCATION = 2
        const val REQUEST_CODE_EXTERNAL_STORAGE = 4
        const val REQUEST_CODE_ATTACH_CONTACT = 5
        const val REQUEST_CODE_CALLS = 7
        const val REQUEST_CODE_OPEN_CAMERA = 20
        const val REQUEST_CODE_VIDEO_MESSAGE = 150
        const val REQUEST_CODE_EXTERNAL_STORAGE_FOR_AVATAR = 151
        const val REQUEST_CODE_NOTIFICATION = 1001
    }

    protected var currentAccount = 0

    protected open fun checkPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        if (grantResults.isEmpty()) return true
        val granted = grantResults[0] == PackageManager.PERMISSION_GRANTED

        when (requestCode) {
            REQUEST_CODE_EXTERNAL_STORAGE, REQUEST_CODE_EXTERNAL_STORAGE_FOR_AVATAR -> {
                if (!granted) {
                    showPermissionErrorAlert(getString(R.string.permission_no_storage))
                }
            }
            REQUEST_CODE_OPEN_CAMERA -> {
                if (!granted) {
                    showPermissionErrorAlert(getString(R.string.permission_no_camera))
                }
            }
            3, REQUEST_CODE_VIDEO_MESSAGE -> {
                var audioGranted = true
                var cameraGranted = true
                for (i in permissions.indices) {
                    if (i >= grantResults.size) break
                    if (Manifest.permission.RECORD_AUDIO == permissions[i]) {
                        audioGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED
                    } else if (Manifest.permission.CAMERA == permissions[i]) {
                        cameraGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED
                    }
                }
                if (requestCode == REQUEST_CODE_VIDEO_MESSAGE && (!audioGranted || !cameraGranted)) {
                    showPermissionErrorAlert(getString(R.string.permission_no_camera_mic_video))
                } else if (!audioGranted) {
                    showPermissionErrorAlert(getString(R.string.permission_no_audio))
                } else if (!cameraGranted) {
                    showPermissionErrorAlert(getString(R.string.permission_no_camera))
                } else {
                    return false
                }
            }
            REQUEST_CODE_GEOLOCATION -> {
                if (!granted) {
                    showPermissionErrorAlert(getString(R.string.permission_no_location))
                }
            }
        }
        return true
    }

    protected fun createPermissionErrorAlert(message: String): AlertDialog {
        return AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(getString(R.string.permission_open_settings)) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (_: Exception) {}
            }
            .setNegativeButton(getString(R.string.permission_not_now), null)
            .create()
    }

    private fun showPermissionErrorAlert(message: String) {
        createPermissionErrorAlert(message).show()
    }
}
