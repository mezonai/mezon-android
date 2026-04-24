package com.mezon.mobile.home.qr

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import com.mezon.mobile.ui.cells.ToastOverlay
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONObject
import android.content.pm.PackageManager

class MyQrFragment : BaseFragment() {

    private companion object {
        private const val REQUEST_WRITE_STORAGE = 4101
        private const val DEFAULT_PROFILE_QR_SIZE = 400
        private const val DEFAULT_TRANSFER_QR_SIZE = 220
    }

    private enum class Tab { PROFILE, TRANSFER }

    private lateinit var userController: UserController
    private lateinit var accountController: AccountController

    private lateinit var root: ScrollView
    private lateinit var contentLayout: LinearLayout
    private lateinit var tabProfile: TextView
    private lateinit var tabTransfer: TextView
    private lateinit var headerAvatar: AvatarView
    private lateinit var headerTitle: TextView
    private lateinit var headerSubtitle: TextView
    private lateinit var qrCard: QrInviteCardCell
    private lateinit var actionRow: LinearLayout
    private lateinit var hintText: TextView

    private var activeTab = Tab.PROFILE
    private var profileQr: Bitmap? = null
    private var transferQr: Bitmap? = null
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

        root = ScrollView(context).apply {
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            setBackgroundColor(pageBg)
        }

        contentLayout = LinearLayout(context).apply {
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

        contentLayout.addView(buildUserCard(context), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(16) })

        qrCard = QrInviteCardCell(context, themeColors)
        qrCard.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            if (width <= 0 || (right == oldRight && left == oldLeft)) return@addOnLayoutChangeListener
            val newSize = width - LayoutHelper.dp(48)
            if (newSize > 0 && newSize != lastQrSizePx) {
                lastQrSizePx = newSize
                profileQr = null
                transferQr = null
                refreshUi()
            }
        }
        val qrCardWrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(16f).toFloat()
                setColor(themeColors.surface)
            }
            background = bg
            setPadding(0, 0, 0, LayoutHelper.dp(20))
        }
        qrCardWrapper.addView(qrCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        contentLayout.addView(qrCardWrapper, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(16) })

        actionRow = buildActionRow(context)
        contentLayout.addView(actionRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(16) })

        hintText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(themeColors.onSurfaceVariant)
            gravity = Gravity.CENTER
            setPadding(LayoutHelper.dp(16), 0, LayoutHelper.dp(16), 0)
        }
        contentLayout.addView(hintText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(10) })

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
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(14f).toFloat()
                setColor(themeColors.surface)
            }
            background = bg
            setPadding(LayoutHelper.dp(14), LayoutHelper.dp(14), LayoutHelper.dp(14), LayoutHelper.dp(14))
        }

        headerAvatar = AvatarView(context).apply {
            setSizeDp(48)
            setRoundRadius(8f)  
        }
        card.addView(headerAvatar, LinearLayout.LayoutParams(LayoutHelper.dp(48), LayoutHelper.dp(48)))

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        headerTitle = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(themeColors.onSurface)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        headerSubtitle = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(themeColors.onSurfaceVariant)
        }
        textCol.addView(headerTitle)
        textCol.addView(headerSubtitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(2) })

        card.addView(textCol, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { leftMargin = LayoutHelper.dp(12) })

        return card
    }

    private fun buildActionRow(context: Context): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val btnSize = LayoutHelper.dp(56)
        val downloadBtn = buildIconButton(context, MezonIcon.downloadIcon) { downloadQr() }
        val shareBtn    = buildIconButton(context, MezonIcon.shareIcon)    { shareQr() }

        row.addView(downloadBtn, LinearLayout.LayoutParams(btnSize, btnSize).apply {
            rightMargin = LayoutHelper.dp(16)
        })
        row.addView(shareBtn, LinearLayout.LayoutParams(btnSize, btnSize))
        return row
    }

    private fun buildIconButton(context: Context, icon: MezonIcon, onClick: () -> Unit): ImageView {
        return ImageView(context).apply {
            val bg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(themeColors.secondaryLight)
            }
            background = bg
            setImageDrawable(icon.getDrawable(context, themeColors))
            setColorFilter(themeColors.onSurface)
            val pad = LayoutHelper.dp(16)
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

        headerAvatar.setInfo(info.userId, name)
        if (avatarUrl.isNotEmpty()) headerAvatar.setImageUrl(avatarUrl)

        val isProfile = activeTab == Tab.PROFILE

        updateTabStyle(tabProfile, isProfile)
        updateTabStyle(tabTransfer, !isProfile)

        headerTitle.text = username.ifEmpty { name }
        if (isProfile) {
            headerSubtitle.text = getString(R.string.qr_share_with_others)
            headerSubtitle.visibility = View.VISIBLE
        } else {
            headerSubtitle.text = ""
            headerSubtitle.visibility = View.GONE
        }

        val qrBitmap = if (isProfile) getProfileQr(username, info.userId, avatarUrl, name, lastQrSizePx)
        else getTransferQr(username, name, info.userId, lastQrSizePx)

        qrCard.bind(QrInviteCardCell.Model(
            title    = username.ifEmpty { name },
            subtitle = if (isProfile) getString(R.string.qr_profile_hint)
                       else getString(R.string.qr_transfer_hint),
            qrBitmap = qrBitmap,
            avatarUrl  = avatarUrl,
            avatarName = name
        ))

        actionRow.visibility = if (isProfile) View.VISIBLE else View.GONE

        hintText.text = if (isProfile) getString(R.string.qr_profile_hint)
                        else getString(R.string.qr_transfer_hint)
        hintText.visibility = View.VISIBLE
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


    private fun getProfileQr(username: String, userId: Long, avatar: String, name: String, qrSizePx: Int): Bitmap {
        val existing = profileQr
        if (existing != null && qrSizePx <= 0) return existing
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

    private fun getTransferQr(username: String, displayName: String, userId: Long, qrSizePx: Int): Bitmap {
        val existing = transferQr
        if (existing != null && qrSizePx <= 0) return existing
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
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$fname.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            val outUri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            outUri?.let { u ->
                ctx.contentResolver.openOutputStream(u)?.use { s -> bmp.compress(Bitmap.CompressFormat.PNG, 100, s) }
            }
            outUri
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val file = File(dir, "$fname.png")
            FileOutputStream(file).use { s -> bmp.compress(Bitmap.CompressFormat.PNG, 100, s) }
            Uri.fromFile(file)
        }
        showToast(
            if (uri != null) getString(R.string.qr_download_success) else getString(R.string.qr_download_failed),
            if (uri != null) ToastOverlay.ToastType.SUCCESS else ToastOverlay.ToastType.ERROR
        )
    }

    private fun shareQr() {
        val bmp = captureQrBitmap() ?: return
        val cacheDir = File(requireContext().cacheDir, "qr")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val file = File(cacheDir, "qr_share.png")
        FileOutputStream(file).use { s -> bmp.compress(Bitmap.CompressFormat.PNG, 100, s) }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            requireContext(),
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, getString(R.string.qr_share_message))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        getParentActivity()?.startActivity(Intent.createChooser(intent, getString(R.string.common_share)))
    }

    private fun captureQrBitmap(): Bitmap? {
        if (qrCard.width == 0 || qrCard.height == 0) return null
        val bmp = Bitmap.createBitmap(qrCard.width, qrCard.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        qrCard.draw(canvas)
        return bmp
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

    private fun showToast(msg: String, type: ToastOverlay.ToastType) {
        val parent = getLayoutContainer() ?: (fragmentView as? android.view.ViewGroup) ?: return
        ToastOverlay(requireContext(), themeColors).show(parent, type, msg)
    }
}
