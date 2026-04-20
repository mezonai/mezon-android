package com.mezon.mobile.qr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.profile.AccountController
import com.mezon.mobile.home.profile.UserController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QrScannerFragment : BaseFragment() {

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 1001
        private const val GALLERY_REQUEST = 1002
        private const val GALLERY_PERMISSION_REQUEST = 1003
        private const val SCAN_THROTTLE_MS = 5000L
        private const val FRAME_INTERVAL_MS = 300L
    }

    private lateinit var userController: UserController
    private lateinit var accountController: AccountController

    private var previewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null

    private val barcodeScanner: BarcodeScanner = BarcodeScanning.getClient()
    @Volatile
    private var isProcessingFrame = false

    private var scanningEnabled = true
    private var isNavigating = false
    private var lastScanMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pendingLoginId: String? = null
    private var loginConfirmOverlay: View? = null
    private var loginStatusView: TextView? = null
    private var loginConfirmButton: TextView? = null
    private var loginStartButton: TextView? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        userController = entryPoint.userController()
        accountController = entryPoint.accountController()
    }

    // ─────────────────────────────────────────────
    // View
    // ─────────────────────────────────────────────

    override fun createView(context: Context): View {
        cameraExecutor = Executors.newSingleThreadExecutor()

        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }

        // Camera preview
        previewView = PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Dark overlays
        root.addView(View(context).apply { setBackgroundColor(0x80000000.toInt()) },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(120)))
        root.addView(View(context).apply { setBackgroundColor(0x80000000.toInt()) },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(220))
                .apply { gravity = Gravity.BOTTOM })

        // Scan frame
        val frameSize = LayoutHelper.dp(240)
        root.addView(ScanFrameView(context),
            FrameLayout.LayoutParams(frameSize, frameSize, Gravity.CENTER))

        // Hint text below frame
        root.addView(TextView(context).apply {
            text = context.getString(R.string.qr_scan_hint)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            topMargin = frameSize / 2 + LayoutHelper.dp(24)
        })

        // Header (close + My QR)
        root.addView(buildHeader(context), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(48) })

        // Gallery button
        root.addView(buildGalleryButton(context), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = LayoutHelper.dp(24)
            bottomMargin = LayoutHelper.dp(60)
        })

        if (hasCameraPermission(context)) startCamera(context)
        else {
            val activity = getParentActivity()
            if (activity is androidx.fragment.app.FragmentActivity) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_REQUEST
                )
            }
        }

        return root
    }

    private fun buildHeader(context: Context): LinearLayout {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(16), 0, LayoutHelper.dp(16), 0)
        }

        // Close
        val closeBtn = FrameLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x66000000)
            }
            isClickable = true; isFocusable = true
            setOnClickListener { finishFragment() }
        }
        closeBtn.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_close_icon_share)
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, FrameLayout.LayoutParams(LayoutHelper.dp(24), LayoutHelper.dp(24), Gravity.CENTER))
        header.addView(closeBtn, LinearLayout.LayoutParams(LayoutHelper.dp(48), LayoutHelper.dp(48)))

        // Spacer
        header.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))

        // My QR pill
        val myQrBtn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(50f).toFloat()
                setColor(0x80000000.toInt())
            }
            setPadding(LayoutHelper.dp(14), LayoutHelper.dp(10), LayoutHelper.dp(14), LayoutHelper.dp(10))
            isClickable = true; isFocusable = true
            setOnClickListener { presentFragment(MyQrFragment()) }
        }
        myQrBtn.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_qr_scan_setting_icon)
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(LayoutHelper.dp(20), LayoutHelper.dp(20)))
        myQrBtn.addView(TextView(context).apply {
            text = context.getString(R.string.qr_my_qr_code)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(LayoutHelper.dp(6), 0, 0, 0)
        })
        header.addView(myQrBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return header
    }

    private fun buildGalleryButton(context: Context): FrameLayout {
        val btn = FrameLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(0x66000000)
            }
            setPadding(LayoutHelper.dp(14), LayoutHelper.dp(14), LayoutHelper.dp(14), LayoutHelper.dp(14))
            isClickable = true; isFocusable = true
            setOnClickListener { openGallery() }
        }
        btn.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_file_icon)
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, FrameLayout.LayoutParams(LayoutHelper.dp(28), LayoutHelper.dp(28), Gravity.CENTER))
        return btn
    }

    // ─────────────────────────────────────────────
    // Camera — CameraX + zxing-cpp BarcodeReader
    // ─────────────────────────────────────────────

    private fun hasCameraPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasGalleryPermission(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera(context: Context) {
        ProcessCameraProvider.getInstance(context).also { future ->
            future.addListener({
                try {
                    cameraProvider = future.get()
                    bindCamera(context)
                } catch (e: Exception) { e.printStackTrace() }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    private fun bindCamera(context: Context) {
        val provider = cameraProvider ?: return
        val preview = previewView ?: return
        provider.unbindAll()

        val previewUseCase = Preview.Builder().build()
            .also { it.setSurfaceProvider(preview.surfaceProvider) }

        val analyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analyzer.setAnalyzer(cameraExecutor!!) { imageProxy ->
            // Throttle
            val now = System.currentTimeMillis()
            if (!scanningEnabled || isNavigating || now - lastScanMs < FRAME_INTERVAL_MS || isProcessingFrame) {
                imageProxy.close()
                return@setAnalyzer
            }
            lastScanMs = now

            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return@setAnalyzer
            }
            isProcessingFrame = true
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    val text = barcodes.firstOrNull { it.rawValue?.isNotEmpty() == true }?.rawValue
                    if (!text.isNullOrEmpty()) {
                        scanningEnabled = false
                        mainHandler.post { handleQrValue(text) }
                        mainHandler.postDelayed({ scanningEnabled = true }, SCAN_THROTTLE_MS)
                    }
                }
                .addOnCompleteListener {
                    isProcessingFrame = false
                    imageProxy.close()
                }
        }

        val lifecycleOwner = getParentActivity() as? LifecycleOwner ?: return
        try {
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                previewUseCase,
                analyzer
            )
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ─────────────────────────────────────────────
    // QR value routing
    // ─────────────────────────────────────────────

    private fun handleQrValue(value: String) {
        when {
            value.contains("channel-app") -> {
                try {
                    getParentActivity()?.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(value))
                    )
                } catch (_: Exception) { showInvalidToast() }
                isNavigating = true
            }
            value.contains("/invite/") -> {
                val code = value.substringAfter("/invite/").split("?")[0]
                showToast(requireContext().getString(R.string.qr_invite_code, code))
                mainHandler.postDelayed({ isNavigating = false; scanningEnabled = true }, SCAN_THROTTLE_MS)
            }
            value.contains("/chat/") -> {
                val username = value.substringAfter("/chat/").substringBefore("?")
                val data = Uri.parse(value).getQueryParameter("data")
                isNavigating = true
                presentFragment(QrProfileDetailFragment.newInstance(username, data))
            }
            else -> {
                val json = tryParseJson(value)
                when {
                    json != null && json.has("lucky_money_id") ->
                        showToast("Lucky money: ${json.getString("lucky_money_id")}")
                    json != null && (json.has("receiver_id") || json.has("wallet_address")) ->
                        showToast(requireContext().getString(R.string.qr_transfer_detected))
                    isSnowflakeId(value) -> showLoginConfirm(value)
                    else -> showInvalidToast()
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // Gallery — zxing-cpp BarcodeReader.read(Bitmap)
    // ─────────────────────────────────────────────

    private fun openGallery() {
        val ctx = requireContext()
        if (!hasGalleryPermission(ctx)) {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            ActivityCompat.requestPermissions(
                getParentActivity() ?: return,
                arrayOf(permission),
                GALLERY_PERMISSION_REQUEST
            )
            return
        }
        val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
        startActivityForResult(intent, GALLERY_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GALLERY_REQUEST && resultCode == Activity.RESULT_OK) {
            scanningEnabled = false
            data?.data?.let { decodeQrFromUri(it) }
        }
    }

    private fun decodeQrFromUri(uri: Uri) {
        val ctx = requireContext()
        fragmentScope.launch(Dispatchers.IO) {
            try {
                val bitmap = ctx.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                } ?: return@launch

                val text = decodeQrFromBitmap(bitmap)

                withContext(Dispatchers.Main) {
                    if (!text.isNullOrEmpty()) {
                        handleQrValue(text)
                    } else {
                        showToast(ctx.getString(R.string.qr_no_qr_in_image))
                        scanningEnabled = true
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    showToast(ctx.getString(R.string.qr_no_qr_in_image))
                    scanningEnabled = true
                }
            }
        }
    }

    private suspend fun decodeQrFromBitmap(bitmap: Bitmap): String? {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val barcodes = barcodeScanner.process(image).await()
            barcodes.firstOrNull { it.rawValue?.isNotEmpty() == true }?.rawValue
        } catch (_: Exception) {
            null
        }
    }

    // ─────────────────────────────────────────────
    // Login confirm overlay
    // ─────────────────────────────────────────────

    private fun showLoginConfirm(loginId: String) {
        pendingLoginId = loginId
        val context = requireContext()
        val root = fragmentView as? ViewGroup ?: return
        scanningEnabled = false
        isNavigating = true
        previewView?.visibility = View.INVISIBLE
        cameraProvider?.unbindAll()

        val overlay = FrameLayout(context).apply { setBackgroundColor(0xCC000000.toInt()) }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(16f).toFloat()
                setColor(0xFF1F1F3A.toInt())
            }
            setPadding(LayoutHelper.dp(24), LayoutHelper.dp(28), LayoutHelper.dp(24), LayoutHelper.dp(24))
        }

        card.addView(TextView(context).apply {
            text = context.getString(R.string.qr_login_title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        card.addView(TextView(context).apply {
            text = context.getString(R.string.qr_login_description)
            setTextColor(0xFFAAAAAA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, LayoutHelper.dp(8), 0, LayoutHelper.dp(20))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        loginStatusView = TextView(context).apply {
            setTextColor(0xFFAAAAAA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        card.addView(loginStatusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = LayoutHelper.dp(12) })

        loginConfirmButton = TextView(context).apply {
             text = context.getString(R.string.qr_login_confirm)
             setTextColor(Color.WHITE)
             setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
             gravity = Gravity.CENTER
             background = android.graphics.drawable.GradientDrawable().apply {
                 cornerRadius = LayoutHelper.dp(50f).toFloat()
                 setColor(0xFF5865F2.toInt())
             }
             setPadding(0, LayoutHelper.dp(14), 0, LayoutHelper.dp(14))
             isClickable = true; isFocusable = true
             setOnClickListener { confirmLogin() }
        }
        card.addView(loginConfirmButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(48))
             .apply { topMargin = LayoutHelper.dp(4) })

        loginStartButton = TextView(context).apply {
            text = context.getString(R.string.qr_login_start_talking)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(50f).toFloat()
                setColor(0xFF2CB67D.toInt())
            }
            setPadding(0, LayoutHelper.dp(14), 0, LayoutHelper.dp(14))
            isClickable = true; isFocusable = true
            visibility = View.GONE
            setOnClickListener { dismissLoginConfirm() }
        }
        card.addView(loginStartButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(48)
        ).apply { topMargin = LayoutHelper.dp(8) })

        card.addView(TextView(context).apply {
            text = context.getString(R.string.common_cancel)
            setTextColor(0xFFAAAAAA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            isClickable = true; isFocusable = true
            setOnClickListener { dismissLoginConfirm() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = LayoutHelper.dp(12) })

        overlay.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            leftMargin = LayoutHelper.dp(24); rightMargin = LayoutHelper.dp(24)
            bottomMargin = LayoutHelper.dp(60)
        })

        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        loginConfirmOverlay = overlay
    }

    private fun dismissLoginConfirm() {
        (fragmentView as? ViewGroup)?.let { root ->
            loginConfirmOverlay?.let { root.removeView(it) }
        }
        loginConfirmOverlay = null
        pendingLoginId = null
        loginStatusView = null
        loginConfirmButton = null
        loginStartButton = null
        previewView?.visibility = View.VISIBLE
        if (hasCameraPermission(requireContext())) bindCamera(requireContext())
        scanningEnabled = true
        isNavigating = false
    }

    private fun confirmLogin() {
        if (pendingLoginId == null) return
        loginConfirmButton?.isEnabled = false
        loginConfirmButton?.alpha = 0.6f
        loginStatusView?.apply {
            text = getString(R.string.qr_login_confirming)
            visibility = View.VISIBLE
        }

        // TODO: hook confirmLoginRequest(loginId) when proto/API is ready.
        mainHandler.postDelayed({
            loginStatusView?.text = getString(R.string.qr_login_success)
            loginStartButton?.visibility = View.VISIBLE
            loginConfirmButton?.visibility = View.GONE
        }, 800)
    }

    // ─────────────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────────────

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startCamera(requireContext())
            } else {
                showPermissionDenied()
            }
            return
        }
        if (requestCode == GALLERY_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                openGallery()
            } else {
                showToast(getString(R.string.qr_gallery_permission_denied))
            }
        }
    }

    private fun showPermissionDenied() {
        val ctx = requireContext()
        val root = fragmentView as? FrameLayout ?: return
        root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.qr_camera_permission_denied)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(LayoutHelper.dp(24), 0, LayoutHelper.dp(24), LayoutHelper.dp(48))
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.qr_open_settings)
            setTextColor(0xFF5865F2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            isClickable = true; isFocusable = true
            setOnClickListener {
                getParentActivity()?.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", ctx.packageName, null))
                )
            }
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ).apply { topMargin = LayoutHelper.dp(80) })
    }

    // ─────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (loginConfirmOverlay == null) {
            scanningEnabled = true
            isNavigating = false
            if (hasCameraPermission(requireContext())) bindCamera(requireContext())
        }
    }

    override fun onPause() {
        super.onPause()
        scanningEnabled = false
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        cameraProvider?.unbindAll()
        cameraExecutor?.shutdown()
        cameraExecutor = null
        barcodeScanner.close()
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private fun showToast(msg: String) =
        mainHandler.post { Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show() }

    private fun showInvalidToast() = showToast(requireContext().getString(R.string.qr_code_not_valid))

    private fun tryParseJson(value: String): org.json.JSONObject? =
        try { org.json.JSONObject(value) } catch (_: Exception) { null }

    private fun isSnowflakeId(value: String): Boolean =
        value.length in 15..20 && value.all { it.isDigit() }

    // ─────────────────────────────────────────────
    // Scan frame overlay view
    // ─────────────────────────────────────────────

    private class ScanFrameView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = LayoutHelper.dp(3).toFloat()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val len = LayoutHelper.dp(28).toFloat()
        private val r   = LayoutHelper.dp(8).toFloat()

        override fun onDraw(canvas: android.graphics.Canvas) {
            val rect = RectF(1f, 1f, width - 1f, height - 1f)
            with(rect) {
                // top-left
                canvas.drawLine(left, top + len, left, top + r, paint)
                canvas.drawArc(left, top, left + r * 2, top + r * 2, 180f, 90f, false, paint)
                canvas.drawLine(left + r, top, left + len, top, paint)
                // top-right
                canvas.drawLine(right - len, top, right - r, top, paint)
                canvas.drawArc(right - r * 2, top, right, top + r * 2, 270f, 90f, false, paint)
                canvas.drawLine(right, top + r, right, top + len, paint)
                // bottom-right
                canvas.drawLine(right, bottom - len, right, bottom - r, paint)
                canvas.drawArc(right - r * 2, bottom - r * 2, right, bottom, 0f, 90f, false, paint)
                canvas.drawLine(right - r, bottom, right - len, bottom, paint)
                // bottom-left
                canvas.drawLine(left + len, bottom, left + r, bottom, paint)
                canvas.drawArc(left, bottom - r * 2, left + r * 2, bottom, 90f, 90f, false, paint)
                canvas.drawLine(left, bottom - r, left, bottom - len, paint)
            }
        }
    }
}
