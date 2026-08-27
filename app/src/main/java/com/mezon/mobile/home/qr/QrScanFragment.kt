package com.mezon.mobile.home.qr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.os.Build
import android.graphics.ImageDecoder
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
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.AlertsCreator
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.deeplink.DeepLinkParser
import com.mezon.mobile.deeplink.DeepLinkRouter
import com.mezon.mobile.deeplink.InviteClanFragment
import com.mezon.mobile.deeplink.InstallClanFragment
import com.mezon.mobile.deeplink.InstallKind
import com.mezon.mobile.MainActivity
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.session.SessionExpiredException
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.home.wallet.SendTokenFragment
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class QrScanFragment : BaseFragment() {

    companion object {
        private const val TAG = "QrScanFragment"
        private const val REQUEST_CAMERA = 7001
        private const val REQUEST_GALLERY = 7002
        private const val SCAN_THROTTLE_MS = 5000L
        private const val GALLERY_QR_MAX_EDGE = 4096

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
            val minRowBytes = (width - 1) * pixelStride + 1
            for (rowIndex in 0 until height) {
                val available = minOf(rowStride, buffer.remaining())
                if (available < minRowBytes) return null
                buffer.get(row, 0, available)
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
    private var permissionDeniedShown = false
    private val resumeScanRunnable = Runnable {
        if (!scanningStopped) {
            scanningEnabled = true
        }
    }

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private lateinit var deepLinkRouter: DeepLinkRouter

    override fun onInject(entryPoint: FragmentEntryPoint) {
        deepLinkRouter = entryPoint.deepLinkRouter()
    }

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
        val activity = getParentActivity() ?: return
        val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            permissionDeniedShown = false
            bindCamera()
        } else if (!permissionDeniedShown) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onPause() {
        super.onPause()
        cameraProvider?.unbindAll()
    }

    private fun bindCamera() {
        val activity = getParentActivity() ?: return
        val lifecycleOwner = activity as? androidx.lifecycle.LifecycleOwner ?: return
        val providerFuture = ProcessCameraProvider.getInstance(activity)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
            val analyzer = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
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
            is QrAction.LuckyMoney -> {
                stopScanning()
                presentFragment(ClaimLuckyMoneyFragment.newInstance(action.id))
            }
            is QrAction.DeepLink -> {
                val route = DeepLinkParser.parse(action.url)
                val activity = getParentActivity() as? MainActivity
                if (route != null && activity != null) {
                    deepLinkRouter.dispatchRoute(activity, route, action.url)
                } else {
                    handleUnsupportedQr(value)
                }
            }
            is QrAction.Invite -> {
                val inviteId = action.code.toLongOrNull() ?: 0L
                if (inviteId == 0L) {
                    handleUnsupportedQr(value)
                    return
                }
                presentFragment(InviteClanFragment.newInstance(inviteId))
            }
            is QrAction.BotInstall -> {
                presentFragment(InstallClanFragment.newInstance(action.appId, InstallKind.BOT, value))
            }
            is QrAction.AppInstall -> {
                presentFragment(InstallClanFragment.newInstance(action.appId, InstallKind.APP, value))
            }
            else -> {
                handleUnsupportedQr(value)
            }
        }
    }

    private fun handleUnsupportedQr(value: String) {
        val link = QrPayloadParser.parseOpenableLink(value)
        if (link == null) {
            showToast(getString(R.string.qr_code_not_valid))
            resumeScanLater()
            return
        }
        showExternalLinkSheet(link)
    }

    private fun showExternalLinkSheet(url: String) {
        val activity = getParentActivity()
        if (activity == null) {
            resumeScanLater()
            return
        }
        QrExternalLinkBottomSheet(
            sheetContext = activity,
            themeColors = themeColors,
            url = url,
            onOpenLink = { openExternalLink(url) },
            onClosed = { resumeScanLater() }
        ).show()
    }

    private fun openExternalLink(url: String): Boolean {
        val activity = getParentActivity() ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return try {
            activity.startActivity(intent)
            true
        } catch (_: Exception) {
            showToast(getString(R.string.qr_external_link_open_failed), ToastOverlay.ToastType.ERROR)
            false
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
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(
            Intent.createChooser(intent, getString(R.string.qr_select_photo_with_qr)),
            REQUEST_GALLERY
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_GALLERY && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            decodeFromGallery(uri)
        }
    }

    private fun decodeFromGallery(uri: Uri) {
        val ctx = requireContext()
        fragmentScope.launch(Dispatchers.IO) {
            val bytes = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            val value = bytes?.takeIf { it.isNotEmpty() }?.let { decodeGalleryQrValue(it) }
            launch(Dispatchers.Main) {
                if (value.isNullOrBlank()) {
                    showToast(getString(R.string.qr_select_photo_with_qr), ToastOverlay.ToastType.ERROR)
                } else {
                    onGalleryQrScanned(value)
                }
            }
        }
    }

    private fun onGalleryQrScanned(value: String) {
        lastScanAt = 0L
        scanningEnabled = true
        onQrScanned(value)
    }

    private fun decodeGalleryQrValue(bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return decodeGalleryQrValueWithImageDecoder(bytes, bounds)
        }

        val orientation = readExifOrientation(bytes)
        var sample = 1
        while (bounds.outWidth / sample > GALLERY_QR_MAX_EDGE || bounds.outHeight / sample > GALLERY_QR_MAX_EDGE) {
            sample *= 2
        }

        while (sample >= 1) {
            val bitmap = decodeGalleryBitmapFromBytes(bytes, bounds, sample) ?: run {
                if (sample == 1) {
                    return decodeGalleryQrValueWithImageDecoder(bytes, bounds)
                }
                sample /= 2
                continue
            }
            val oriented = applyExifOrientation(bitmap, orientation)
            val toDecode = oriented ?: bitmap
            val value = QrCodeUtils.decodeFromBitmap(toDecode)
            if (oriented != null && oriented !== bitmap) bitmap.recycle()
            toDecode.recycle()
            if (!value.isNullOrBlank()) return value
            if (sample == 1) break
            sample /= 2
        }
        return decodeGalleryQrValueWithImageDecoder(bytes, bounds)
    }

    private fun decodeGalleryQrValueWithImageDecoder(bytes: ByteArray, bounds: BitmapFactory.Options): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    val maxEdge = maxOf(bounds.outWidth, bounds.outHeight)
                    if (maxEdge > GALLERY_QR_MAX_EDGE) {
                        val scale = GALLERY_QR_MAX_EDGE.toFloat() / maxEdge
                        decoder.setTargetSize(
                            (bounds.outWidth * scale).toInt().coerceAtLeast(1),
                            (bounds.outHeight * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
            }
        }.getOrNull() ?: return null
        return try {
            QrCodeUtils.decodeFromBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun readExifOrientation(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun decodeGalleryBitmapFromBytes(
        bytes: ByteArray,
        bounds: BitmapFactory.Options,
        sampleSize: Int
    ): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching {
                    val targetW = (bounds.outWidth / sampleSize).coerceAtLeast(1)
                    val targetH = (bounds.outHeight / sampleSize).coerceAtLeast(1)
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = true
                        decoder.setTargetSize(targetW, targetH)
                    }
                }.getOrNull()
            } else {
                null
            }
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap? {
        if (orientation == ExifInterface.ORIENTATION_NORMAL) return null

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return null
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CAMERA) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                permissionDeniedShown = false
                bindCamera()
            } else {
                permissionDeniedShown = true
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
            try {
                val bitmap = image.toBitmap()
                val rotation = image.imageInfo.rotationDegrees
                
                val scale = 960f / maxOf(bitmap.width, bitmap.height)
                
                val finalBitmap = if (rotation != 0 || scale < 1f) {
                    val matrix = android.graphics.Matrix().apply {
                        if (rotation != 0) postRotate(rotation.toFloat())
                        if (scale < 1f) postScale(scale, scale)
                    }
                    val transformed = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    bitmap.recycle() 
                    transformed
                } else {
                    bitmap
                }

                try {
                    val value = QrCodeUtils.decodeLiveCameraBitmap(finalBitmap)
                    if (!value.isNullOrBlank()) onQr(value)
                } finally {
                    finalBitmap.recycle() 
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            } finally {
                image.close() 
            }
        }
    }

}
