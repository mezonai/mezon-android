package com.mezon.mobile.home.qr

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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

class MyQrFragment : BaseFragment() {

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

    override fun onInject(entryPoint: FragmentEntryPoint) {
        userController = entryPoint.userController()
        accountController = entryPoint.accountController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        observe(NotificationCenter.accountInfoLoaded) { _, _, _ -> refreshUi() }
        return true
    }

    override fun createView(context: Context): View {
        // Page background: light gray (like image)
        val pageBg = 0xFFF2F2F7.toInt()

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

        // 1. Tab pill switcher
        contentLayout.addView(buildTabs(context), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 2. User info card (white rounded card)
        contentLayout.addView(buildUserCard(context), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(16) })

        // 3. QR card
        qrCard = QrInviteCardCell(context, themeColors)
        val qrCardWrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(16f).toFloat()
                setColor(Color.WHITE)
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

        // 4. Action buttons (icon-only, two equal columns)
        actionRow = buildActionRow(context)
        contentLayout.addView(actionRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(16) })

        // 5. Hint text below buttons
        hintText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF888888.toInt())
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
        }
    }

    // ── Pill tab switcher ──────────────────────────────────────────────────────
    // Matching image: gray container pill, active tab = white pill, inactive = transparent

    private fun buildTabs(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(24f).toFloat()
                setColor(0xFFE0E0E0.toInt())   // light gray container
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

    // ── User info card ─────────────────────────────────────────────────────────
    // White rounded card, avatar (square-rounded 8dp) + username bold + subtitle

    private fun buildUserCard(context: Context): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(14f).toFloat()
                setColor(Color.WHITE)
            }
            background = bg
            setPadding(LayoutHelper.dp(14), LayoutHelper.dp(14), LayoutHelper.dp(14), LayoutHelper.dp(14))
        }

        headerAvatar = AvatarView(context).apply {
            setSizeDp(48)
            setRoundRadius(8f)   // slightly rounded square, matching image
        }
        card.addView(headerAvatar, LinearLayout.LayoutParams(LayoutHelper.dp(48), LayoutHelper.dp(48)))

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        headerTitle = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        headerSubtitle = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF666666.toInt())
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

    // ── Action row: two icon-only buttons in light gray boxes ─────────────────
    // Matching image: each button is a gray rounded square with just an icon (no label)

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
            // tint icon to match onSurface so it's always readable
            setColorFilter(themeColors.onSurface)
            val pad = LayoutHelper.dp(16)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onClick() }
        }
    }

    // ── Tab switching ─────────────────────────────────────────────────────────

    private fun switchTab(tab: Tab) {
        if (activeTab == tab) return
        activeTab = tab
        refreshUi()
    }

    // ── UI refresh ────────────────────────────────────────────────────────────

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

        // Tab style: active = white pill with shadow, inactive = transparent
        updateTabStyle(tabProfile, isProfile)
        updateTabStyle(tabTransfer, !isProfile)

        // Header card texts
        headerTitle.text = username.ifEmpty { name }
        headerSubtitle.text = if (isProfile) {
            getString(R.string.qr_share_with_others)   // "Chia sẻ với người khác"
        } else {
            val balance = info.balance.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            val formatted = String.format(Locale.getDefault(), "%,.0f", balance)
            getString(R.string.qr_token_balance, formatted)  // "Số dư: x đồng"
        }

        // QR bitmap
        val qrBitmap = if (isProfile) getProfileQr(username, info.userId, avatarUrl, name)
        else getTransferQr(username, info.userId)

        qrCard.bind(QrInviteCardCell.Model(
            title    = username.ifEmpty { name },
            subtitle = if (isProfile) getString(R.string.qr_profile_hint)
                       else getString(R.string.qr_transfer_hint),
            qrBitmap = qrBitmap,
            avatarUrl  = avatarUrl,
            avatarName = name
        ))

        // Download/Share buttons — only show for profile tab
        actionRow.visibility = if (isProfile) View.VISIBLE else View.GONE

        // Hint text below buttons
        hintText.text = if (isProfile) getString(R.string.qr_profile_hint)
                        else getString(R.string.qr_transfer_hint)
        hintText.visibility = View.VISIBLE
    }

    private fun updateTabStyle(tab: TextView, active: Boolean) {
        if (active) {
            val bg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(20f).toFloat()
                setColor(Color.WHITE)
            }
            tab.background = bg
            tab.setTextColor(Color.BLACK)
            tab.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        } else {
            tab.background = null
            tab.setTextColor(0xFF888888.toInt())
            tab.typeface = Typeface.DEFAULT
        }
    }

    // ── QR generation ─────────────────────────────────────────────────────────

    private fun getProfileQr(username: String, userId: Long, avatar: String, name: String): Bitmap {
        val existing = profileQr
        if (existing != null) return existing
        val json = "{\"id\":$userId,\"avatar\":\"$avatar\",\"name\":\"$name\"}"
        val encoded = android.util.Base64.encodeToString(
            URLEncoder.encode(json, "UTF-8").toByteArray(),
            android.util.Base64.NO_WRAP
        )
        val url = "${BuildConfig.MEZON_REDIRECT_URI}/chat/$username?data=$encoded"
        return QrCodeUtils.generateQr(url, 400).also { profileQr = it }
    }

    private fun getTransferQr(username: String, userId: Long): Bitmap {
        val existing = transferQr
        if (existing != null) return existing
        val json = "{\"receiver_name\":\"$username\",\"receiver_id\":$userId}"
        return QrCodeUtils.generateQr(json, 220).also { transferQr = it }
    }

    // ── Download / Share ──────────────────────────────────────────────────────

    private fun downloadQr() {
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

    private fun showToast(msg: String, type: ToastOverlay.ToastType) {
        val parent = getLayoutContainer() ?: (fragmentView as? android.view.ViewGroup) ?: return
        ToastOverlay(requireContext(), themeColors).show(parent, type, msg)
    }
}
