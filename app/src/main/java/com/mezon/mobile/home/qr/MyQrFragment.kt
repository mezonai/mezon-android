package com.mezon.mobile.home.qr

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.profile.AccountController
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.PopupMenu
import com.mezon.mobile.ui.cells.ToastOverlay
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class MyQrFragment : BaseFragment() {

    private companion object {
        private const val REQUEST_WRITE_STORAGE = 4101
        private const val REQUEST_QR_CENTER_IMAGE = 4102
        private const val DEFAULT_PROFILE_QR_SIZE = 400
        private const val DEFAULT_TRANSFER_QR_SIZE = 220
        private const val MAX_CENTER_IMAGE_SIZE_PX = 512
        private const val CENTER_IMAGE_EDIT_BUTTON_SIZE_DP = 40
        private const val CENTER_IMAGE_EDIT_BUTTON_MARGIN_DP = 10
        private const val CENTER_IMAGE_EDIT_BUTTON_SAFE_GAP_DP = 8
    }

    private enum class Tab { PROFILE, TRANSFER }

    private lateinit var userController: UserController
    private lateinit var accountController: AccountController

    private lateinit var tabProfile: TextView
    private lateinit var tabTransfer: TextView
    private lateinit var headerAvatar: AvatarView
    private lateinit var headerTitle: TextView
    private lateinit var headerUsername: TextView
    private lateinit var snapshotCard: LinearLayout
    private lateinit var qrCard: QrInviteCardCell
    private lateinit var actionRow: LinearLayout

    private var activeTab = Tab.PROFILE
    private var profileQr: Bitmap? = null
    private var transferQr: Bitmap? = null
    private var selectedCenterBitmap: Bitmap? = null
    private var centerImageMenu: PopupMenu? = null
    private var centerImageRequestGeneration = 0
    private var pendingDownload = false
    private var lastQrSizePx = 0

    override fun onInject(entryPoint: FragmentEntryPoint) {
        userController = entryPoint.userController()
        accountController = entryPoint.accountController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        observe(NotificationCenter.accountInfoLoaded) { _, _, _ ->
            profileQr = null
            transferQr = null
            refreshUi()
        }
        return true
    }

    override fun createView(context: Context): View {
        val pageBg = themeColors.background
        invalidatePendingCenterImageRequest()
        releaseCenterImage()

        val root = ScrollView(context).apply {
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            setBackgroundColor(pageBg)
        }

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(12), LayoutHelper.dp(16), LayoutHelper.dp(32))
        }
        root.addView(contentLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        contentLayout.addView(buildTabs(context), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        snapshotCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, LayoutHelper.dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                MyQrSnapshotStyle.gradientColors
            ).apply {
                cornerRadius = LayoutHelper.dp(16f).toFloat()
            }
        }
        snapshotCard.addView(buildUserCard(context), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        qrCard = QrInviteCardCell(context, themeColors)
        qrCard.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val width = right - left
            if (width <= 0 || (right == oldRight && left == oldLeft)) return@addOnLayoutChangeListener
            val newSize = qrCard.getQrSizePx()
            if (newSize > 0 && newSize != lastQrSizePx) {
                lastQrSizePx = newSize
                profileQr = null
                transferQr = null
                refreshUi()
            }
        }
        snapshotCard.addView(qrCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(24) })

        actionRow = buildActionRow(context)
        snapshotCard.addView(actionRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(24) })

        val snapshotWrapper = FrameLayout(context).apply {
            addView(snapshotCard, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(buildCenterImageEditButton(context), FrameLayout.LayoutParams(
                LayoutHelper.dp(CENTER_IMAGE_EDIT_BUTTON_SIZE_DP),
                LayoutHelper.dp(CENTER_IMAGE_EDIT_BUTTON_SIZE_DP),
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = LayoutHelper.dp(CENTER_IMAGE_EDIT_BUTTON_MARGIN_DP)
                rightMargin = LayoutHelper.dp(CENTER_IMAGE_EDIT_BUTTON_MARGIN_DP)
            })
        }

        contentLayout.addView(snapshotWrapper, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = LayoutHelper.dp(4)
            rightMargin = LayoutHelper.dp(4)
            topMargin = LayoutHelper.dp(16)
        })

        refreshUi()

        return wrapWithActionBar(getString(R.string.qr_my_code), root).also {
            it.setBackgroundColor(pageBg)
            actionBar?.setCenterTitle(true)
        }
    }


    private fun buildTabs(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(24f).toFloat()
                setColor(themeColors.surfaceVariant)
            }
            background = bg
            setPadding(LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4))
        }

        tabProfile = buildPillTab(context, getString(R.string.qr_profile)) { switchTab(Tab.PROFILE) }
        tabTransfer = buildPillTab(context, getString(R.string.qr_transfer)) { switchTab(Tab.TRANSFER) }

        container.addView(tabProfile, LinearLayout.LayoutParams(0, LayoutHelper.dp(40), 1f))
        container.addView(tabTransfer, LinearLayout.LayoutParams(0, LayoutHelper.dp(40), 1f))
        return container
    }

    private fun buildPillTab(context: Context, text: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }
    }


    private fun buildUserCard(context: Context): LinearLayout {
        val avatarSize = LayoutHelper.dp(48)
        val avatarTextGap = LayoutHelper.dp(12)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(LayoutHelper.dp(24), LayoutHelper.dp(24), LayoutHelper.dp(24), 0)
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        headerAvatar = AvatarView(context).apply {
            setSizeDp(48)
            setRoundRadius(24f)
        }
        headerRow.addView(headerAvatar, LinearLayout.LayoutParams(avatarSize, avatarSize))

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

        headerTitle = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, MyQrSnapshotStyle.NAME_TEXT_SIZE_SP)
            setTextColor(MyQrSnapshotStyle.primaryText)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this,
                16,
                MyQrSnapshotStyle.NAME_TEXT_SIZE_SP.toInt(),
                1,
                TypedValue.COMPLEX_UNIT_SP
            )
        }
        textColumn.addView(headerTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        headerUsername = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(MyQrSnapshotStyle.qrAccent)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(MyQrSnapshotStyle.badgeBackground)
            }
            setPadding(LayoutHelper.dp(8), LayoutHelper.dp(2), LayoutHelper.dp(8), LayoutHelper.dp(2))
        }
        textColumn.addView(headerUsername, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(4) })

        headerRow.addView(textColumn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            avatarSize
        ).apply { leftMargin = avatarTextGap })

        card.addView(headerRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        card.addOnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
            val headerSideInset = maxOf(
                view.paddingLeft,
                LayoutHelper.dp(
                    CENTER_IMAGE_EDIT_BUTTON_SIZE_DP +
                        CENTER_IMAGE_EDIT_BUTTON_MARGIN_DP +
                        CENTER_IMAGE_EDIT_BUTTON_SAFE_GAP_DP
                )
            )
            val maxTextWidth = (
                right - left - headerSideInset * 2 - avatarSize - avatarTextGap
            ).coerceAtLeast(LayoutHelper.dp(80))
            if (headerTitle.maxWidth != maxTextWidth) {
                headerTitle.maxWidth = maxTextWidth
                headerUsername.maxWidth = maxTextWidth
            }
        }

        return card
    }

    private fun buildCenterImageEditButton(context: Context): ImageView {
        return ImageView(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(MyQrSnapshotStyle.badgeBackground)
            }
            setImageDrawable(MezonIcon.pencilIcon.getDrawable(context, MyQrSnapshotStyle.badgeIcon))
            setPadding(LayoutHelper.dp(11), LayoutHelper.dp(11), LayoutHelper.dp(11), LayoutHelper.dp(11))
            elevation = LayoutHelper.dp(2).toFloat()
            contentDescription = context.getString(R.string.qr_center_image_title)
            isFocusable = true
            setOnClickListener { showCenterImageOptions(it) }
        }
    }

    private fun showCenterImageOptions(anchorView: View) {
        val context = getContext() ?: return
        centerImageMenu?.dismiss()
        centerImageMenu = PopupMenu(context, themeColors).apply {
            addItem(getString(R.string.qr_center_image_device))
            addItem(getString(R.string.qr_center_image_avatar))
            addItem(getString(R.string.qr_center_image_mezon))
            setOnItemClickListener { which ->
                when (which) {
                    0 -> openCenterImagePicker()
                    1 -> selectCenterImage(null)
                    2 -> selectCenterImage(createMezonLogoBitmap(context))
                }
            }
            setOnDismissListener { centerImageMenu = null }
            show(anchorView)
        }
    }

    private fun openCenterImagePicker() {
        invalidatePendingCenterImageRequest()
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        getParentActivity()?.startActivityForResult(
            Intent.createChooser(intent, getString(R.string.qr_center_image_device)),
            REQUEST_QR_CENTER_IMAGE
        )
    }

    private fun createMezonLogoBitmap(context: Context): Bitmap {
        val size = LayoutHelper.dp(96)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        MezonIcon.logoMezon.getDrawable(context).apply {
            setBounds(0, 0, size, size)
            draw(Canvas(bitmap))
        }
        return bitmap
    }

    private fun selectCenterImage(bitmap: Bitmap?) {
        invalidatePendingCenterImageRequest()
        replaceCenterImage(bitmap)
    }

    private fun replaceCenterImage(bitmap: Bitmap?) {
        val previous = selectedCenterBitmap
        if (previous === bitmap) return
        selectedCenterBitmap = bitmap
        refreshUi()
        if (previous != null && !previous.isRecycled) previous.recycle()
    }

    private fun releaseCenterImage() {
        selectedCenterBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        selectedCenterBitmap = null
    }

    private fun invalidatePendingCenterImageRequest() {
        centerImageRequestGeneration++
    }

    private fun buildActionRow(context: Context): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val buttonWidth = LayoutHelper.dp(60)
        val buttonHeight = LayoutHelper.dp(42)
        val downloadBtn = buildIconButton(
            context,
            MezonIcon.downloadIcon,
            R.string.common_download
        ) { downloadQr() }
        val shareBtn = buildIconButton(
            context,
            MezonIcon.shareIcon,
            R.string.common_share
        ) { shareQr() }

        row.addView(downloadBtn, LinearLayout.LayoutParams(buttonWidth, buttonHeight).apply {
            rightMargin = LayoutHelper.dp(16)
        })
        row.addView(shareBtn, LinearLayout.LayoutParams(buttonWidth, buttonHeight))
        return row
    }

    private fun buildIconButton(
        context: Context,
        icon: MezonIcon,
        contentDescriptionRes: Int,
        onClick: () -> Unit
    ): ImageView {
        return ImageView(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(8f).toFloat()
                setColor(MyQrSnapshotStyle.badgeBackground)
            }
            setImageDrawable(icon.getDrawable(context, MyQrSnapshotStyle.badgeIcon))
            contentDescription = context.getString(contentDescriptionRes)
            isFocusable = true
            val pad = LayoutHelper.dp(10)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onClick() }
        }
    }


    private fun switchTab(tab: Tab) {
        if (activeTab == tab) return
        activeTab = tab
        refreshUi()
    }


    private fun refreshUi() {
        val info = accountController.accountInfo.value
        val name = info.displayName.ifEmpty {
            info.username.ifEmpty { userController.displayName.ifEmpty { userController.username } }
        }
        val username = info.username.ifEmpty { userController.username }
        val avatarUrl = info.avatarUrl.ifEmpty { userController.avatarUrl }

        if (!::headerAvatar.isInitialized) return

        headerAvatar.setInfo(info.userId, username)
        headerAvatar.setImageUrl(avatarUrl.takeIf(String::isNotEmpty))

        val isProfile = activeTab == Tab.PROFILE

        updateTabStyle(tabProfile, isProfile)
        updateTabStyle(tabTransfer, !isProfile)

        headerTitle.text = name.ifEmpty { username }
        headerUsername.text = username
            .ifEmpty { name }
            .let { if (it.startsWith("@")) it else "@$it" }

        val qrBitmap = if (isProfile) getProfileQr(username, info.userId, avatarUrl, name, lastQrSizePx)
        else getTransferQr(username, name, info.userId, lastQrSizePx)

        qrCard.bind(QrInviteCardCell.Model(
            qrBitmap = qrBitmap,
            avatarUrl  = avatarUrl,
            avatarName = username,
            appearance = QrInviteCardCell.Appearance.PERSONAL,
            qrTypeLabel = getString(
                if (isProfile) R.string.qr_profile_badge else R.string.qr_transfer_badge
            ),
            centerBitmap = selectedCenterBitmap
        ))
    }

    private fun updateTabStyle(tab: TextView, active: Boolean) {
        if (active) {
            val bg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(20f).toFloat()
                setColor(themeColors.surface)
            }
            tab.background = bg
            tab.setTextColor(themeColors.onSurface)
            tab.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        } else {
            tab.background = null
            tab.setTextColor(themeColors.onSurfaceVariant)
            tab.typeface = Typeface.DEFAULT
        }
    }


    private fun getProfileQr(
        username: String,
        userId: Long,
        avatar: String,
        name: String,
        qrSizePx: Int
    ): Bitmap {
        profileQr?.let { return it }
        val json = JSONObject()
            .put("id", userId)
            .put("avatar", avatar)
            .put("name", name)
            .toString()
        val encoded = android.util.Base64.encodeToString(
            URLEncoder.encode(json, "UTF-8").toByteArray(),
            android.util.Base64.NO_WRAP
        )
        val url = "${BuildConfig.MEZON_REDIRECT_URI}/chat/$username?data=$encoded"
        val size = if (qrSizePx > 0) qrSizePx else DEFAULT_PROFILE_QR_SIZE
        return QrCodeUtils.generateQr(url, size).also { profileQr = it }
    }

    private fun getTransferQr(
        username: String,
        displayName: String,
        userId: Long,
        qrSizePx: Int
    ): Bitmap {
        transferQr?.let { return it }
        val receiverName = username.ifEmpty { displayName }
        val json = JSONObject()
            .put("receiver_name", receiverName)
            .put("receiver_display_name", displayName)
            .put("receiver_id", userId)
            .toString()
        val size = if (qrSizePx > 0) qrSizePx else DEFAULT_TRANSFER_QR_SIZE
        return QrCodeUtils.generateQr(json, size).also { transferQr = it }
    }


    private fun downloadQr() {
        if (!ensureLegacyWritePermission()) {
            pendingDownload = true
            return
        }
        pendingDownload = false
        val bmp = captureQrBitmap() ?: return
        val ctx = requireContext()
        val fname = "mezon_qr_${System.currentTimeMillis()}"
        val uri = try {
            persistQrBitmap(ctx, bmp, fname)
        } catch (_: Exception) {
            null
        } finally {
            bmp.recycle()
        }
        showToast(
            if (uri != null) getString(R.string.qr_download_success) else getString(R.string.qr_download_failed),
            if (uri != null) ToastOverlay.ToastType.SUCCESS else ToastOverlay.ToastType.ERROR
        )
    }

    private fun persistQrBitmap(context: Context, bitmap: Bitmap, fileName: String): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            val resolver = context.contentResolver
            val outputUri = runCatching {
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }.getOrNull() ?: return null
            val written = runCatching {
                resolver.openOutputStream(outputUri)?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                } == true
            }.getOrDefault(false)
            if (!written) {
                runCatching { resolver.delete(outputUri, null, null) }
                return null
            }
            return outputUri
        }

        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        if (!directory.exists() && !directory.mkdirs()) return null
        val file = File(directory, "$fileName.png")
        val written = runCatching {
            FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
        }.getOrDefault(false)
        if (!written) return null
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("image/png"),
            null
        )
        return Uri.fromFile(file)
    }

    private fun shareQr() {
        val bmp = captureQrBitmap() ?: return
        val context = requireContext()
        val uri = runCatching {
            val cacheDir = File(context.cacheDir, "qr")
            if (!cacheDir.exists() && !cacheDir.mkdirs()) return@runCatching null
            val file = File(cacheDir, "qr_share.png")
            val written = FileOutputStream(file).use { stream ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            if (!written) return@runCatching null
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file
            )
        }.getOrNull()
        bmp.recycle()
        if (uri == null) {
            showToast(getString(R.string.common_something_went_wrong), ToastOverlay.ToastType.ERROR)
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_TEXT,
                getString(
                    if (activeTab == Tab.PROFILE) R.string.qr_share_message
                    else R.string.qr_transfer_hint
                )
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        getParentActivity()?.startActivity(Intent.createChooser(intent, getString(R.string.common_share)))
    }

    private fun captureQrBitmap(): Bitmap? {
        if (snapshotCard.width <= 0 || snapshotCard.height <= 0) return null

        return Bitmap.createBitmap(
            snapshotCard.width,
            snapshotCard.height,
            Bitmap.Config.ARGB_8888
        ).also { bitmap ->
            snapshotCard.draw(Canvas(bitmap))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_QR_CENTER_IMAGE || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        decodeCenterImage(uri, centerImageRequestGeneration)
    }

    private fun decodeCenterImage(uri: Uri, requestGeneration: Int) {
        val context = requireContext()
        fragmentScope.launch(Dispatchers.IO) {
            val bitmap = runCatching { decodeCenterImageBitmap(context, uri) }.getOrNull()
            launch(Dispatchers.Main) {
                if (requestGeneration != centerImageRequestGeneration) {
                    if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                    return@launch
                }
                if (bitmap == null) {
                    showToast(
                        getString(R.string.qr_center_image_load_failed),
                        ToastOverlay.ToastType.ERROR
                    )
                } else {
                    replaceCenterImage(bitmap)
                }
            }
        }
    }

    private fun decodeCenterImageBitmap(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val longestSide = maxOf(width, height)
                if (longestSide > MAX_CENTER_IMAGE_SIZE_PX) {
                    val scale = MAX_CENTER_IMAGE_SIZE_PX.toFloat() / longestSide
                    decoder.setTargetSize(
                        (width * scale).toInt().coerceAtLeast(1),
                        (height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_CENTER_IMAGE_SIZE_PX) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        return applyExifOrientation(bitmap, orientation)
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
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
            else -> return bitmap
        }

        val oriented = runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull() ?: return bitmap
        if (oriented !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        return oriented
    }

    private fun ensureLegacyWritePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        val ctx = getContext() ?: return false
        val granted = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return true
        val activity = getParentActivity() ?: return false
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            REQUEST_WRITE_STORAGE
        )
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_WRITE_STORAGE) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted && pendingDownload) {
                pendingDownload = false
                downloadQr()
            } else {
                pendingDownload = false
            }
        }
    }

    override fun onFragmentDestroy() {
        centerImageMenu?.dismiss()
        centerImageMenu = null
        invalidatePendingCenterImageRequest()
        releaseCenterImage()
        super.onFragmentDestroy()
    }

    private fun showToast(msg: String, type: ToastOverlay.ToastType) {
        val parent = getLayoutContainer() ?: (fragmentView as? android.view.ViewGroup) ?: return
        ToastOverlay(requireContext(), themeColors).show(parent, type, msg)
    }
}
