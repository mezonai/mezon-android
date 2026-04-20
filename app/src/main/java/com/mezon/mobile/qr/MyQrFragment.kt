package com.mezon.mobile.qr

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.profile.AccountController
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.util.createImgproxyUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.Base64

class MyQrFragment : BaseFragment() {

    private enum class Tab { PROFILE, TRANSFER }

    private lateinit var userController: UserController
    private lateinit var accountController: AccountController

    private var activeTab = Tab.PROFILE
    private var profileQrBitmap: Bitmap? = null
    private var transferQrBitmap: Bitmap? = null

    private lateinit var profileTabBtn: TextView
    private lateinit var transferTabBtn: TextView
    private lateinit var qrImageView: ImageView
    private lateinit var subtitleText: TextView
    private lateinit var downloadBtn: View
    private lateinit var shareBtn: View
    private lateinit var userNameText: TextView
    private lateinit var userUsernameText: TextView
    private lateinit var avatarView: AvatarView
    private var avatarCancellable: MezonImageLoader.Cancellable? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onInject(entryPoint: FragmentEntryPoint) {
        userController = entryPoint.userController()
        accountController = entryPoint.accountController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        observe(NotificationCenter.accountInfoLoaded) { _, _, _ ->
            if (fragmentView != null) updateUserHeader()
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView != null) applyThemeColors()
        }
        return true
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        accountController.loadAccount(noCache = false)
        generateQrCodes()
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        // Scroll content
        val scroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(20), LayoutHelper.dp(8), LayoutHelper.dp(20), LayoutHelper.dp(32))
        }
        scroll.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        // Tab pill
        val tabContainer = buildTabContainer(context)
        content.addView(tabContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(44)
        ).apply { topMargin = LayoutHelper.dp(12) })

        // QR Card
        val card = buildQrCard(context)
        content.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(20) })

        return wrapWithActionBar(context.getString(R.string.qr_my_qr_code), root)
    }

    private fun buildTabContainer(context: Context): LinearLayout {
        val bg = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dp(22f).toFloat()
            setColor(themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_sheetItemBackground))
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg
            setPadding(LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4))
        }

        profileTabBtn = buildTabButton(context, context.getString(R.string.qr_tab_profile))
        container.addView(profileTabBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        transferTabBtn = buildTabButton(context, context.getString(R.string.qr_tab_transfer))
        container.addView(transferTabBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        profileTabBtn.setOnClickListener { selectTab(Tab.PROFILE) }
        transferTabBtn.setOnClickListener { selectTab(Tab.TRANSFER) }

        selectTab(Tab.PROFILE)
        return container
    }

    private fun buildTabButton(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }

    private fun selectTab(tab: Tab) {
        activeTab = tab
        val activeColor = themeColors.primary
        val inactiveColor = themeColors.onSurfaceVariant

        val activeBg = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dp(18f).toFloat()
            setColor(activeColor)
        }

        if (tab == Tab.PROFILE) {
            profileTabBtn.background = activeBg
            profileTabBtn.setTextColor(Color.WHITE)
            transferTabBtn.background = null
            transferTabBtn.setTextColor(inactiveColor)
        } else {
            transferTabBtn.background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(18f).toFloat()
                setColor(activeColor)
            }
            transferTabBtn.setTextColor(Color.WHITE)
            profileTabBtn.background = null
            profileTabBtn.setTextColor(inactiveColor)
        }

        updateQrDisplay()
    }

    private fun buildQrCard(context: Context): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(20), LayoutHelper.dp(16), LayoutHelper.dp(20))
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(16f).toFloat()
                setColor(themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_sheetItemBackground))
            }
        }

        // User header row
        val userRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        card.addView(userRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        avatarView = AvatarView(context).apply {
            setSizeDp(48)
            setRoundRadius(10f)
        }
        userRow.addView(avatarView, LinearLayout.LayoutParams(LayoutHelper.dp(48), LayoutHelper.dp(48)))

        val nameCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        userRow.addView(nameCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = LayoutHelper.dp(12)
        })

        userNameText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(themeColors.onSurface)
        }
        nameCol.addView(userNameText)

        userUsernameText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(themeColors.onSurfaceVariant)
        }
        nameCol.addView(userUsernameText)

        // White QR area
        val qrContainer = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(16), LayoutHelper.dp(16), LayoutHelper.dp(16))
        }
        card.addView(qrContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(20) })

        qrImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.ic_qr_scan_setting_icon)
        }
        val qrSize = LayoutHelper.dp(220)
        qrContainer.addView(qrImageView, FrameLayout.LayoutParams(qrSize, qrSize, Gravity.CENTER))

        // Subtitle
        subtitleText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(themeColors.onSurfaceVariant)
            gravity = Gravity.CENTER
        }
        card.addView(subtitleText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(12) })

        // Action buttons (download + share — profile only)
        val actionRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        card.addView(actionRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(16) })

        downloadBtn = buildActionButton(context, R.drawable.ic_history_icon, context.getString(R.string.qr_download)) {
            downloadQr(context)
        }
        actionRow.addView(downloadBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        shareBtn = buildActionButton(context, R.drawable.ic_share_contact_icon, context.getString(R.string.qr_share)) {
            shareQr(context)
        }
        actionRow.addView(shareBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = LayoutHelper.dp(16) })

        updateUserHeader()
        return card
    }

    private fun buildActionButton(context: Context, iconRes: Int, label: String, onClick: () -> Unit): LinearLayout {
        val btn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        val iconContainer = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_sheetItemBackground))
            }
        }
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        val iconSize = LayoutHelper.dp(44)
        iconContainer.addView(icon, FrameLayout.LayoutParams(LayoutHelper.dp(22), LayoutHelper.dp(22), Gravity.CENTER))
        btn.addView(iconContainer, LinearLayout.LayoutParams(iconSize, iconSize))

        val txt = TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(themeColors.onSurfaceVariant)
            gravity = Gravity.CENTER
            setPadding(0, LayoutHelper.dp(4), 0, 0)
        }
        btn.addView(txt)
        return btn
    }

    private fun updateUserHeader() {
        if (!::avatarView.isInitialized) return
        val info = accountController.accountInfo.value
        val name = info.displayName.ifEmpty { info.username.ifEmpty { userController.displayName } }
        val username = info.username.ifEmpty { userController.username }

        userNameText.text = name
        userUsernameText.text = "@$username"
        userNameText.setTextColor(themeColors.onSurface)
        userUsernameText.setTextColor(themeColors.onSurfaceVariant)

        avatarView.setInfo(userController.userId, name)
        val avatarUrl = info.avatarUrl.ifEmpty { userController.avatarUrl }
        if (avatarUrl.isNotEmpty()) {
            avatarView.setImageUrl(avatarUrl)
        }
    }

    private fun generateQrCodes() {
        val info = accountController.accountInfo.value
        val username = info.username.ifEmpty { userController.username }
        val id = if (info.userId != 0L) info.userId.toString() else userController.userIdStr
        val avatar = info.avatarUrl.ifEmpty { userController.avatarUrl }
        val name = info.displayName.ifEmpty { info.username.ifEmpty { userController.displayName } }
        val balance = info.balance

        fragmentScope.launch(Dispatchers.IO) {
            // Profile QR
            val profilePayload = buildProfilePayload(id, avatar, name)
            val profileUrl = "${BuildConfig.MEZON_REDIRECT_URI}/chat/$username?data=$profilePayload"
            val profileBitmap = generateQrBitmap(profileUrl, 400)

            // Transfer QR
            val transferJson = JSONObject().apply {
                put("receiver_name", username)
                put("receiver_id", id)
            }.toString()
            val transferBitmap = generateQrBitmap(transferJson, 220)

            withContext(Dispatchers.Main) {
                profileQrBitmap = profileBitmap
                transferQrBitmap = transferBitmap
                subtitleText.text = buildSubtitle(balance)
                updateQrDisplay()
            }
        }
    }

    private fun buildProfilePayload(id: String, avatar: String, name: String): String {
        val json = JSONObject().apply {
            put("id", id)
            put("avatar", avatar)
            put("name", name)
        }
        val jsonStr = json.toString()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Base64.getUrlEncoder().withoutPadding().encodeToString(jsonStr.toByteArray(Charsets.UTF_8))
        } else {
            android.util.Base64.encodeToString(jsonStr.toByteArray(Charsets.UTF_8), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        }
    }

    private fun generateQrBitmap(content: String, sizePx: Int): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
                EncodeHintType.MARGIN to 1
            )
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = bitMatrix.width
            val h = bitMatrix.height
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            for (x in 0 until w) {
                for (y in 0 until h) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun buildSubtitle(balance: String): String {
        val info = accountController.accountInfo.value
        return if (activeTab == Tab.PROFILE) {
            requireContext().getString(R.string.qr_share_with_others)
        } else {
            val bal = balance.ifEmpty { info.balance }
            requireContext().getString(R.string.qr_transfer_subtitle, bal)
        }
    }

    private fun updateQrDisplay() {
        if (!::qrImageView.isInitialized) return
        val bitmap = if (activeTab == Tab.PROFILE) profileQrBitmap else transferQrBitmap
        if (bitmap != null) {
            qrImageView.setImageBitmap(bitmap)
        } else {
            qrImageView.setImageResource(R.drawable.ic_qr_scan_setting_icon)
        }

        val isProfile = activeTab == Tab.PROFILE
        downloadBtn.visibility = if (isProfile) View.VISIBLE else View.GONE
        shareBtn.visibility = if (isProfile) View.VISIBLE else View.GONE

        val info = accountController.accountInfo.value
        subtitleText.text = buildSubtitle(info.balance)
    }

    private fun downloadQr(context: Context) {
        val bitmap = profileQrBitmap ?: run {
            Toast.makeText(context, context.getString(R.string.qr_generating), Toast.LENGTH_SHORT).show()
            return
        }
        fragmentScope.launch(Dispatchers.IO) {
            try {
                val name = "mezon_qr_${System.currentTimeMillis()}.png"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Mezon")
                    }
                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    uri?.let { u ->
                        context.contentResolver.openOutputStream(u)?.use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Mezon")
                    dir.mkdirs()
                    val file = File(dir, name)
                    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.qr_download_success), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.qr_download_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareQr(context: Context) {
        val bitmap = profileQrBitmap ?: run {
            Toast.makeText(context, context.getString(R.string.qr_generating), Toast.LENGTH_SHORT).show()
            return
        }
        fragmentScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = File(context.cacheDir, "qr_share")
                cacheDir.mkdirs()
                val file = File(cacheDir, "mezon_qr.png")
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file
                )
                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, context.getString(R.string.qr_share_message))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    getParentActivity()?.startActivity(Intent.createChooser(intent, context.getString(R.string.qr_share)))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.qr_share_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun applyThemeColors() {
        fragmentView?.setBackgroundColor(themeColors.background)
        updateUserHeader()
        updateQrDisplay()
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        avatarCancellable?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
