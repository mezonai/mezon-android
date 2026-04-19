package com.mezon.mobile.qr

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.font.FontWeight

@Composable
fun QrScannerScreen(
    viewModel: QrViewModel,
    onNavigateProfile: (String, String?) -> Unit,
    onNavigateInvite: (String) -> Unit,
    onNavigateLuckyMoney: (String) -> Unit,
    onNavigateTransfer: (String) -> Unit,
    onNavigateDeepLink: (String) -> Unit,
    onClose: () -> Unit
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value as QrUiState.Content
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showMyQr by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onIntent(QrIntent.PermissionResult(granted))
        showPermissionDialog = !granted
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val stream = context.contentResolver.openInputStream(uri)
        val bitmap = stream?.use { BitmapFactory.decodeStream(it) }
        if (bitmap == null) {
            Toast.makeText(context, context.getString(com.mezon.mobile.R.string.qr_select_photo_with_qr), Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val options = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        val scanner = BarcodeScanning.getClient(options)
        val image = InputImage.fromBitmap(bitmap, 0)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstOrNull()?.rawValue
                if (value.isNullOrBlank()) {
                    Toast.makeText(context, context.getString(com.mezon.mobile.R.string.qr_select_photo_with_qr), Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.onIntent(QrIntent.ScanResult(value))
                }
            }
            .addOnFailureListener {
                Toast.makeText(context, context.getString(com.mezon.mobile.R.string.qr_error), Toast.LENGTH_SHORT).show()
            }
    }

    val readPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) galleryLauncher.launch("image/*") else Toast.makeText(
            context,
            context.getString(com.mezon.mobile.R.string.qr_camera_permission_denied),
            Toast.LENGTH_SHORT
        ).show()
    }

    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, context.getString(com.mezon.mobile.R.string.qr_camera_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.onIntent(QrIntent.StartScan)
        viewModel.event.collect { event ->
            when (event) {
                is QrEvent.ShowError -> Toast.makeText(context, context.getString(event.messageResId), Toast.LENGTH_SHORT).show()
                is QrEvent.ShowSuccess -> Toast.makeText(context, context.getString(event.messageResId), Toast.LENGTH_SHORT).show()
                QrEvent.RequestCameraPermission -> permissionLauncher.launch(Manifest.permission.CAMERA)
                QrEvent.OpenGallery -> {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        readPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                    } else {
                        readPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }
                QrEvent.NavigateBack -> onClose()
                QrEvent.OpenSettings -> showPermissionDialog = true
                QrEvent.ShowMyQr -> showMyQr = true
                is QrEvent.NavigateDeepLink -> onNavigateDeepLink(event.value)
                is QrEvent.NavigateInvite -> onNavigateInvite(event.inviteId)
                is QrEvent.NavigateProfile -> onNavigateProfile(event.username, event.data)
                is QrEvent.NavigateLuckyMoney -> onNavigateLuckyMoney(event.luckyMoneyId)
                is QrEvent.NavigateTransfer -> onNavigateTransfer(event.rawJson)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onIntent(QrIntent.OnResume)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
        if (state.hasPermission && state.valueCode == null && !showMyQr) {
            val previewView = remember { PreviewView(context) }
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            val options = remember { BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build() }
            val scanner = remember { BarcodeScanning.getClient(options) }
            DisposableEffect(state.cameraRestartKey, state.hasPermission) {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                val executor = ContextCompat.getMainExecutor(context)
                val runnable = Runnable {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage == null) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        scanner.process(inputImage)
                            .addOnSuccessListener { barcodes ->
                                val value = barcodes.firstOrNull()?.rawValue
                                if (!value.isNullOrBlank()) {
                                    viewModel.onIntent(QrIntent.ScanResult(value))
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
                cameraProviderFuture.addListener(runnable, executor)
                onDispose {
                    runCatching { cameraProviderFuture.get().unbindAll() }
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x33000000))
        )

        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 52.dp, start = 16.dp, end = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // ── Close button (X icon trong circle) ────────────────────
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color(0x80222222), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                // ── My QR Code button (icon + text) ───────────────────────
                Button(
                    onClick = { viewModel.onIntent(QrIntent.ShowMyQr) },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x80222222)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = context.getString(com.mezon.mobile.R.string.qr_my_code),
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
            ) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .border(2.dp, Color.White, RoundedCornerShape(8.dp))
                )
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp)
        ) {
            // ── Gallery picker button (photo icon) ────────────────────────
            IconButton(
                onClick = { viewModel.onIntent(QrIntent.PickFromGallery) },
                modifier = Modifier
                    .size(54.dp)
                    .background(Color(0x80222222), RoundedCornerShape(14.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Photo,
                    contentDescription = "Pick from gallery",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        if (state.valueCode != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xAA000000))
            ) {
                ConfirmLoginScreen(
                    isSuccess = state.isSuccess,
                    title = context.getString(com.mezon.mobile.R.string.qr_log_in_on_new_device),
                    warning = context.getString(com.mezon.mobile.R.string.qr_never_scan_login_from_another_user),
                    successMessage = context.getString(com.mezon.mobile.R.string.qr_you_are_logged_in),
                    confirmLabel = context.getString(com.mezon.mobile.R.string.qr_log_in),
                    cancelLabel = context.getString(com.mezon.mobile.R.string.qr_cancel),
                    startLabel = context.getString(com.mezon.mobile.R.string.qr_start_talking),
                    onConfirm = { viewModel.onIntent(QrIntent.ConfirmLogin) },
                    onCancel = { viewModel.onIntent(QrIntent.CancelLogin) },
                    onStartTalking = {
                        viewModel.onIntent(QrIntent.CancelLogin)
                        onClose()
                    }
                )
            }
        }

        if (showMyQr) {
            val myQrViewModel: MyQrViewModel = hiltViewModel()
            val myQrState = myQrViewModel.state.collectAsStateWithLifecycle().value
            LaunchedEffect(Unit) {
                myQrViewModel.event.collect { event ->
                    when (event) {
                        MyQrEvent.Download -> {
                            try {
                                val freshState = myQrViewModel.state.value
                                val bitmap = freshState.qrProfileBitmap
                                if (bitmap == null) {
                                    Toast.makeText(context, "QR is still generating...", Toast.LENGTH_SHORT).show()
                                    return@collect
                                }
                                if (Build.VERSION.SDK_INT < 29) {
                                    writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                }
                                val name = "mezon_qr_${System.currentTimeMillis()}"
                                val uri = saveBitmapToGallery(context, bitmap, name)
                                if (uri == null && Build.VERSION.SDK_INT < 29) {
                                    saveBitmapLegacy(context, bitmap, name)
                                }
                                Toast.makeText(context, context.getString(com.mezon.mobile.R.string.qr_downloaded), Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        MyQrEvent.Share -> {
                            try {
                                val freshState = myQrViewModel.state.value
                                val bitmap = freshState.qrProfileBitmap
                                if (bitmap != null) {
                                    shareBitmap(
                                        context,
                                        bitmap,
                                        "mezon_qr_${System.currentTimeMillis()}",
                                        "Scan QR code to chat with me on Mezon"
                                    )
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        MyQrEvent.Back -> showMyQr = false
                    }
                }
            }
            // ✅ Bỏ background MaterialTheme.colorScheme.primary - MyQrScreen tự quản lý background
            Box(modifier = Modifier.fillMaxSize()) {
                MyQrScreen(
                    state = myQrState,
                    onTabChanged = { myQrViewModel.onIntent(MyQrIntent.TabChanged(it)) },
                    onDownload = { myQrViewModel.onIntent(MyQrIntent.Download) },
                    onShare = { myQrViewModel.onIntent(MyQrIntent.Share) },
                    onBack = { myQrViewModel.onIntent(MyQrIntent.Back) }
                )
            }
        }

        if (showPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showPermissionDialog = false },
                title = { Text(text = context.getString(com.mezon.mobile.R.string.qr_camera_permission_denied)) },
                confirmButton = {
                    Button(onClick = {
                        showPermissionDialog = false
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    }) {
                        Text(text = context.getString(com.mezon.mobile.R.string.qr_open_settings))
                    }
                },
                dismissButton = {
                    Button(onClick = { showPermissionDialog = false }) {
                        Text(text = context.getString(com.mezon.mobile.R.string.qr_cancel))
                    }
                }
            )
        }
    }
}
