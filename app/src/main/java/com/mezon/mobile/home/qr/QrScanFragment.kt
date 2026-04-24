package com.mezon.mobile.home.qr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.AlertsCreator
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.session.SessionExpiredException
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.home.wallet.SendTokenFragment
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class QrScanFragment : BaseFragment() {

    companion object {
        private const val TAG = "QrScanFragment"
        private const val REQUEST_CAMERA = 7001
        private const val REQUEST_GALLERY = 7002
        private const val SCAN_THROTTLE_MS = 5000L

        private fun extractLuminance(image: ImageProxy): ByteArray? {
            val plane = image.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            if (rowStride < width * pixelStride) return null

            val data = ByteArray(width * height)
            buffer.rewind()

            if (pixelStride == 1 && rowStride == width) {
                if (buffer.remaining() < data.size) return null
                buffer.get(data, 0, data.size)
                return data
            }

            val row = ByteArray(rowStride)
            var offset = 0
            for (rowIndex in 0 until height) {
                if (buffer.remaining() < rowStride) return null
                buffer.get(row, 0, rowStride)
                var col = 0
                var idx = 0
                while (col < width) {
                    data[offset++] = row[idx]
                    idx += pixelStride
                    col++
                }
            }

            return data
        }
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: QrScanOverlayView
    private lateinit var root: FrameLayout
    private var cameraProvider: ProcessCameraProvider? = null
    private var scanningEnabled = true
    private var scanningStopped = false
    private var lastScanAt = 0L
    private val resumeScanRunnable = Runnable {
        if (!scanningStopped) {
            scanningEnabled = true
        }
    }

    private val analyzerExecutor = Executors.newSingleThreadExecutor()

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        return true
    }

    override fun createView(context: android.content.Context): View {
        root = FrameLayout(context)
        root.setBackgroundColor(themeColors.background)

        previewView = PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        overlayView = QrScanOverlayView(context, themeColors)

        root.addView(previewView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(overlayView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        root.addView(buildTopBar(context), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
            topMargin = LayoutHelper.dp(48)
        })

        root.addView(buildGalleryButton(context), FrameLayout.LayoutParams(
            LayoutHelper.dp(52),
            LayoutHelper.dp(52)
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = LayoutHelper.dp(16)
            bottomMargin = LayoutHelper.dp(32)
        })

        return root
    }


    private fun buildTopBar(context: android.content.Context): View {
        val bar = FrameLayout(context).apply {
            setPadding(LayoutHelper.dp(16), 0, LayoutHelper.dp(16), 0)
        }

        val closeBtn = ImageView(context).apply {
            val bg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(10f).toFloat()
                setColor(themeColors.qrPillBackground)
            }
            background = bg
            setImageDrawable(MezonIcon.closeIcon.getDrawable(context, themeColors))
            setColorFilter(themeColors.qrPillContent)
            val pad = LayoutHelper.dp(10)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { finishFragment() }
        }
        bar.addView(closeBtn, FrameLayout.LayoutParams(LayoutHelper.dp(44), LayoutHelper.dp(44)).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        })

        val myQrPill = buildMyQrPill(context)
        bar.addView(myQrPill, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            LayoutHelper.dp(44)
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        })

        return bar
    }

    private fun buildMyQrPill(context: android.content.Context): LinearLayout {
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(22f).toFloat()
                setColor(themeColors.qrPillBackground)
            }
            background = bg
            val padH = LayoutHelper.dp(14)
            val padV = LayoutHelper.dp(0)
            setPadding(padH, padV, padH, padV)
            setOnClickListener { presentFragment(MyQrFragment()) }
            isClickable = true
        }
        val icon = ImageView(context).apply {
            setImageDrawable(MezonIcon.myQRcodeIcon.getDrawable(context, themeColors))
            setColorFilter(themeColors.qrPillContent)
        }
        pill.addView(icon, LinearLayout.LayoutParams(LayoutHelper.dp(18), LayoutHelper.dp(18)))

        val label = TextView(context).apply {
            text = getString(R.string.qr_my_code)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.qrPillContent)
            setPadding(LayoutHelper.dp(7), 0, 0, 0)
        }
        pill.addView(label)
        return pill
    }


    private fun buildGalleryButton(context: android.content.Context): View {
        return ImageView(context).apply {
            val bg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(themeColors.qrPillBackground)
            }
            background = bg
            setImageDrawable(MezonIcon.imageIcon.getDrawable(context, themeColors))
            setColorFilter(themeColors.qrPillContent)
            val pad = LayoutHelper.dp(14)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { openGallery() }
        }
    }


    override fun onResume() {
        super.onResume()
        scanningStopped = false
        scanningEnabled = true
        lastScanAt = 0L
        startCameraIfPermitted()
    }

    override fun onPause() {
        super.onPause()
        cameraProvider?.unbindAll()
    }

    private fun startCameraIfPermitted() {
        val activity = getParentActivity() ?: return
        val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        bindCamera()
    }

    private fun bindCamera() {
        val activity = getParentActivity() ?: return
        val lifecycleOwner = activity as? androidx.lifecycle.LifecycleOwner ?: return
        val providerFuture = ProcessCameraProvider.getInstance(activity)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analyzer.setAnalyzer(analyzerExecutor, QrAnalyzer { value ->
                AndroidUtilities.runOnUIThread { onQrScanned(value) }
            })

            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(lifecycleOwner, selector, preview, analyzer)
        }, ContextCompat.getMainExecutor(activity))
    }


    private fun onQrScanned(value: String) {
        val now = System.currentTimeMillis()
        if (scanningStopped || !scanningEnabled || now - lastScanAt < SCAN_THROTTLE_MS) return
        lastScanAt = now
        scanningEnabled = false

        val action = QrPayloadParser.parse(value)
        when (action) {
            is QrAction.Profile -> {
                val payload = QrPayloadParser.decodeProfilePayload(action.data)
                if (payload == null && action.username.isBlank()) {
                    showToast(getString(R.string.qr_code_not_valid))
                    resumeScanLater()
                    return
                }
                presentFragment(
                    QrProfileFragment.newInstance(
                        username = action.username,
                        displayName = payload?.name.orEmpty(),
                        avatarUrl = payload?.avatar,
                        userId = payload?.id ?: 0L
                    )
                )
            }
            is QrAction.Login -> {
                showConfirmLogin(action.loginId)
            }
            is QrAction.Transfer -> {
                stopScanning()
                presentFragment(SendTokenFragment.newInstance(action.rawJson))
            }
            else -> {
                showToast(getString(R.string.qr_code_not_valid))
                resumeScanLater()
            }
        }
    }

    private fun resumeScanLater() {
        if (scanningStopped) return
        root.removeCallbacks(resumeScanRunnable)
        root.postDelayed(resumeScanRunnable, SCAN_THROTTLE_MS)
    }

    private fun stopScanning() {
        scanningStopped = true
        scanningEnabled = false
        root.removeCallbacks(resumeScanRunnable)
        cameraProvider?.unbindAll()
    }

    private fun showConfirmLogin(loginId: Long) {
        val ctx = requireContext()
        var isConfirming = false
        val dialog = AlertsCreator.createConfirmDialog(
            ctx,
            getString(R.string.qr_login_title),
            getString(R.string.qr_login_message),
            confirmText = getString(R.string.qr_login_confirm),
            cancelText = getString(R.string.common_cancel)
        ) {
            isConfirming = true
            fragmentScope.launch(Dispatchers.Main) {
                val result = entryPoint().authRepository().confirmLoginByQr(loginId)
                if (result.isSuccess) {
                    stopScanning()
                    showToast(getString(R.string.qr_login_success), ToastOverlay.ToastType.SUCCESS)
                    root.post { finishFragment() }
                } else {
                    val exception = result.exceptionOrNull()
                    Log.e(
                        TAG,
                        "QR confirm login failed loginId=$loginId kind=${
                            if (exception is SessionExpiredException) "session_expired" else "other"
                        }",
                        exception
                    )
                    val messageRes = if (exception is SessionExpiredException) {
                        R.string.qr_login_require_mobile_session
                    } else {
                        R.string.qr_login_failed
                    }
                    showToast(getString(messageRes), ToastOverlay.ToastType.ERROR)
                    resumeScanLater()
                }
            }
        }
        dialog.setOnDismissListener {
            if (!isConfirming) {
                resumeScanLater()
            }
        }
        dialog.show()
    }

    private fun showToast(msg: String, type: ToastOverlay.ToastType = ToastOverlay.ToastType.INFO) {
        val parent = getLayoutContainer() ?: (fragmentView as? android.view.ViewGroup) ?: return
        ToastOverlay(requireContext(), themeColors).show(parent, type, msg)
    }


    private fun openGallery() {
        val activity = getParentActivity() ?: return
        val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
        activity.startActivityForResult(intent, REQUEST_GALLERY)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_GALLERY && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            decodeFromGallery(uri)
        }
    }

    private fun decodeFromGallery(uri: Uri) {
        val ctx = requireContext()
        fragmentScope.launch(Dispatchers.IO) {
            val bitmap = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            }.getOrNull()
            val value = bitmap?.let { QrCodeUtils.decodeFromBitmap(it) }
            launch(Dispatchers.Main) {
                if (value == null) {
                    showToast(getString(R.string.qr_select_photo_with_qr), ToastOverlay.ToastType.ERROR)
                } else {
                    onQrScanned(value)
                }
            }
        }
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CAMERA) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                bindCamera()
            } else {
                val ctx = requireContext()
                AlertsCreator.createConfirmDialog(
                    ctx,
                    getString(R.string.qr_camera_denied_title),
                    getString(R.string.qr_camera_denied_message),
                    confirmText = getString(R.string.common_open_settings),
                    cancelText = getString(R.string.common_cancel)
                ) {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", ctx.packageName, null)
                    }
                    ctx.startActivity(intent)
                }.show()
            }
        }
    }

    override fun onFragmentDestroy() {
        stopScanning()
        analyzerExecutor.shutdownNow()
        super.onFragmentDestroy()
    }


    private class QrAnalyzer(
        private val onQr: (String) -> Unit
    ) : ImageAnalysis.Analyzer {
        override fun analyze(image: ImageProxy) {
            val value = extractLuminance(image)?.let {
                QrCodeUtils.decodeFromYPlane(it, image.width, image.height)
            }
            if (!value.isNullOrBlank()) onQr(value)
            image.close()
        }
    }

}
