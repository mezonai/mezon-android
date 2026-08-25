package com.mezon.mobile.home.qr

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay
import java.io.File
import java.io.FileOutputStream

class InviteQrBottomSheet(
    context: Context,
    private val theme: ThemeColors,
    private val inviteUrl: String,
    private val clanName: String,
    private val clanLogo: String,
) : BottomSheet(context) {

    private lateinit var qrCard: QrInviteCardCell
    private var qrBitmap: Bitmap? = null
    private var lastQrSizePx = 0

    init {
        setCanDismissWithSwipe(true)
        setCanDismissWithTouchOutside(true)
        setCustomView(buildContent(context))
    }

    private fun buildContent(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.background)
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), LayoutHelper.dp(20f))
        }

        val cardWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(16f)
                setColor(theme.surface)
            }
            setPadding(0, 0, 0, LayoutHelper.dp(20f))
        }

        qrCard = QrInviteCardCell(context, theme).apply {
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                val width = right - left
                if (width <= 0 || (right == oldRight && left == oldLeft)) return@addOnLayoutChangeListener
                val newSize = width - LayoutHelper.dp(48f)
                if (newSize > 0 && newSize != lastQrSizePx) {
                    lastQrSizePx = newSize
                    qrBitmap = null
                    bindQrCard()
                }
            }
        }
        cardWrap.addView(
            qrCard,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        root.addView(cardWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        root.addView(
            buildActionRow(context),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(16f)
            },
        )

        root.addView(
            TextView(context).apply {
                text = context.getString(R.string.invite_qr_hint)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(theme.textDisabled)
                gravity = Gravity.CENTER
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(10f)
            },
        )

        qrCard.post { bindQrCard() }
        return root
    }

    private fun bindQrCard() {
        val size = if (lastQrSizePx > 0) lastQrSizePx else {
            val fallback = AndroidUtilities.displaySize.x - LayoutHelper.dp(64f)
            fallback.coerceAtLeast(LayoutHelper.dp(200f))
        }
        val bitmap = qrBitmap ?: QrCodeUtils.generateQr(inviteUrl, size).also { qrBitmap = it }
        qrCard.bind(
            QrInviteCardCell.Model(
                qrBitmap = bitmap,
                avatarUrl = clanLogo,
                avatarName = clanName,
                appearance = QrInviteCardCell.Appearance.INVITE,
            ),
        )
    }

    private fun buildActionRow(context: Context): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val btnSize = LayoutHelper.dp(56f)
        row.addView(
            buildIconButton(context, MezonIcon.downloadIcon) { downloadQr() },
            LinearLayout.LayoutParams(btnSize, btnSize).apply { rightMargin = LayoutHelper.dp(16f) },
        )
        row.addView(
            buildIconButton(context, MezonIcon.shareIcon) { shareQr() },
            LinearLayout.LayoutParams(btnSize, btnSize),
        )
        return row
    }

    private fun buildIconButton(context: Context, icon: MezonIcon, onClick: () -> Unit): ImageView {
        return ImageView(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(12f)
                setColor(theme.surfaceVariant)
            }
            setImageDrawable(icon.getDrawable(context, theme.colorText))
            val pad = LayoutHelper.dp(16f)
            setPadding(pad, pad, pad, pad)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun captureQrBitmap(): Bitmap? {
        if (qrCard.width == 0 || qrCard.height == 0) return null
        val bmp = Bitmap.createBitmap(qrCard.width, qrCard.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        qrCard.draw(canvas)
        return bmp
    }

    private fun shareQr() {
        val bmp = captureQrBitmap() ?: run {
            Toast.makeText(context, context.getString(R.string.invite_create_link_error), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val cacheDir = File(context.cacheDir, "invite_qr")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val file = File(cacheDir, "invite_qr_share.png")
            FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, inviteUrl)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.invite_share_qr)))
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.invite_create_link_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadQr() {
        val bmp = captureQrBitmap() ?: run {
            Toast.makeText(context, context.getString(R.string.invite_create_link_error), Toast.LENGTH_SHORT).show()
            return
        }
        val filename = "mezon_invite_qr_${System.currentTimeMillis()}.png"
        val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                true
            } else {
                false
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            dir.mkdirs()
            val file = File(dir, filename)
            file.outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            true
        }
        onQrDownloaded(
            if (saved) ToastOverlay.ToastType.SUCCESS else ToastOverlay.ToastType.ERROR,
            context.getString(if (saved) R.string.invite_qr_saved else R.string.qr_download_failed),
        )
    }

    private fun onQrDownloaded(type: ToastOverlay.ToastType, message: String) {
        val activity = AndroidUtilities.findActivity(context) as? MainActivity
        dismiss()
        AndroidUtilities.runOnUIThread({
            if (activity != null) {
                ToastOverlay(activity, theme).show(activity.drawerLayoutContainer, type, message)
            } else {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            }
        }, 300)
    }
}
