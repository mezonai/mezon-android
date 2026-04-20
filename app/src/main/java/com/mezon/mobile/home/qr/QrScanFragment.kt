package com.mezon.mobile.home.qr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
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
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertsCreator
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class QrScanFragment : BaseFragment() {

    companion object {
        private const val REQUEST_CAMERA = 7001
        private const val REQUEST_GALLERY = 7002
        private const val SCAN_THROTTLE_MS = 5000L
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: QrScanOverlayView
    private lateinit var root: FrameLayout
    private var cameraProvider: ProcessCameraProvider? = null
    private var scanningEnabled = true
    private var lastScanAt = 0L

    private val analyzerExecutor = Executors.newSingleThreadExecutor()

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        return true
    }

    override fun createView(context: android.content.Context): View {
        root = FrameLayout(context)
        root.setBackgroundColor(Color.BLACK)

        // Camera preview fills entire screen
        previewView = PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        overlayView = QrScanOverlayView(context)

        root.addView(previewView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(overlayView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Top-left: [X]  [My QR Code pill]
        root.addView(buildTopBar(context), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = LayoutHelper.dp(48)
            leftMargin = LayoutHelper.dp(16)
        })

        // Bottom-left: gallery icon button
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

    // ── Top bar ───────────────────────────────────────────────────────────────

    private fun buildTopBar(context: android.content.Context): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // X close button — dark rounded square
        val closeBtn = ImageView(context).apply {
            val bg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(10f).toFloat()
                setColor(0xCC1A1A1A.toInt())
            }
            background = bg
            setImageDrawable(MezonIcon.closeIcon.getDrawable(context, themeColors))
            setColorFilter(Color.WHITE)   // white icon
            val pad = LayoutHelper.dp(10)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { finishFragment() }
        }
        bar.addView(closeBtn, LinearLayout.LayoutParams(LayoutHelper.dp(44), LayoutHelper.dp(44)))

        // "My QR Code" pill — dark rounded pill with icon + text
        val myQrPill = buildMyQrPill(context)
        bar.addView(myQrPill, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LayoutHelper.dp(44)
        ).apply { leftMargin = LayoutHelper.dp(10) })

        return bar
    }

    private fun buildMyQrPill(context: android.content.Context): LinearLayout {
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(22f).toFloat()
                setColor(0xCC1A1A1A.toInt())
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
            setColorFilter(Color.WHITE)
        }
        pill.addView(icon, LinearLayout.LayoutParams(LayoutHelper.dp(18), LayoutHelper.dp(18)))

        val label = TextView(context).apply {
            text = getString(R.string.qr_my_code)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.WHITE)
            setPadding(LayoutHelper.dp(7), 0, 0, 0)
        }
        pill.addView(label)
        return pill
    }

    // ── Gallery button ────────────────────────────────────────────────────────

    private fun buildGalleryButton(context: android.content.Context): View {
        return ImageView(context).apply {
            val bg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(0xCC1A1A1A.toInt())
            }
            background = bg
            setImageDrawable(MezonIcon.imageIcon.getDrawable(context, themeColors))
            setColorFilter(Color.WHITE)   // white icon
            val pad = LayoutHelper.dp(14)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { openGallery() }
        }
    }

    // ── Camera lifecycle ──────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        scanningEnabled = true
        lastScanAt = 0L
        startCameraIfPermitted()
    }

    override fun onPause() {
        super.onPause()
        cameraProvider?.unbindAll()
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        analyzerExecutor.shutdown()
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
            analyzer.setAnalyzer(analyzerExecutor, QrAnalyzer { onQrScanned(it) })

            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(lifecycleOwner, selector, preview, analyzer)
        }, ContextCompat.getMainExecutor(activity))
    }

    // ── QR result handling ─────────────────────────────────────────────────────

    private fun onQrScanned(value: String) {
        val now = System.currentTimeMillis()
        if (!scanningEnabled || now - lastScanAt < SCAN_THROTTLE_MS) return
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
                        avatarUrl = payload?.avatar
                    )
                )
            }
            is QrAction.Login -> {
                showConfirmLogin(action.loginId)
            }
            else -> {
                showToast(getString(R.string.qr_code_not_valid))
                resumeScanLater()
            }
        }
    }

    private fun resumeScanLater() {
        root.postDelayed({ scanningEnabled = true }, SCAN_THROTTLE_MS)
    }

    private fun showConfirmLogin(loginId: Long) {
        val ctx = requireContext()
        val dialog = AlertsCreator.createConfirmDialog(
            ctx,
            getString(R.string.qr_login_title),
            getString(R.string.qr_login_message),
            confirmText = getString(R.string.qr_login_confirm),
            cancelText = getString(R.string.common_cancel)
        ) {
            fragmentScope.launch(Dispatchers.Main) {
                val result = entryPoint().authRepository().confirmLoginByQr(loginId)
                if (result.isSuccess) {
                    showToast(getString(R.string.qr_login_success), ToastOverlay.ToastType.SUCCESS)
                } else {
                    showToast(getString(R.string.qr_login_failed), ToastOverlay.ToastType.ERROR)
                }
                resumeScanLater()
            }
        }
        dialog.show()
    }

    private fun showToast(msg: String, type: ToastOverlay.ToastType = ToastOverlay.ToastType.INFO) {
        val parent = getLayoutContainer() ?: (fragmentView as? android.view.ViewGroup) ?: return
        ToastOverlay(requireContext(), themeColors).show(parent, type, msg)
    }

    // ── Gallery ────────────────────────────────────────────────────────────────

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

    // ── Permission ─────────────────────────────────────────────────────────────

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

    // ── Inner Analyzer ─────────────────────────────────────────────────────────

    private class QrAnalyzer(
        private val onQr: (String) -> Unit
    ) : ImageAnalysis.Analyzer {
        override fun analyze(image: ImageProxy) {
            val buffer = image.planes.firstOrNull()?.buffer
            if (buffer != null) {
                val value = QrCodeUtils.decodeFromYPlane(buffer, image.width, image.height)
                if (!value.isNullOrBlank()) {
                    onQr(value)
                }
            }
            image.close()
        }
    }
}
