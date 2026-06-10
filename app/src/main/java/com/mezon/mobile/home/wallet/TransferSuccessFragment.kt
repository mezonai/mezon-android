package com.mezon.mobile.home.wallet

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
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay
import java.io.File
import java.io.FileOutputStream

class TransferSuccessFragment : BaseFragment() {

    companion object {
        private const val ARG_AMOUNT    = "amount"
        private const val ARG_SYMBOL    = "symbol"
        private const val ARG_RECIPIENT = "recipient"
        private const val ARG_NOTE      = "note"
        private const val ARG_TIME      = "time"

        fun newInstance(
            amount: String,
            symbol: String,
            recipient: String,
            note: String,
            time: String
        ): TransferSuccessFragment {
            return TransferSuccessFragment().apply {
                arguments = android.os.Bundle().apply {
                    putString(ARG_AMOUNT,    amount)
                    putString(ARG_SYMBOL,    symbol)
                    putString(ARG_RECIPIENT, recipient)
                    putString(ARG_NOTE,      note)
                    putString(ARG_TIME,      time)
                }
            }
        }
    }

    var onDone: (() -> Unit)? = null
    var onNewTransfer: (() -> Unit)? = null
    var onNewTransferAfterClose: (() -> Unit)? = null

    private var pendingNewTransferAfterClose = false

    private lateinit var amount: String
    private lateinit var symbol: String
    private lateinit var recipient: String
    private lateinit var note: String
    private lateinit var time: String

    private var cardView: View? = null

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        val a = arguments
        amount    = a?.getString(ARG_AMOUNT).orEmpty()
        symbol    = a?.getString(ARG_SYMBOL).orEmpty()
        recipient = a?.getString(ARG_RECIPIENT).orEmpty()
        note      = a?.getString(ARG_NOTE).orEmpty()
        time      = a?.getString(ARG_TIME).orEmpty()
        return true
    }

    override fun createView(context: Context): View {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(24), LayoutHelper.dp(32), LayoutHelper.dp(24), LayoutHelper.dp(24))
        }
        cardView = body

        val scroll = ScrollView(context).apply {
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            isFillViewport = true
        }
        scroll.addView(body, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val circleSize = LayoutHelper.dp(72)
        val circleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(themeColors.onlineGreen)
        }
        val circleWrap = FrameLayout(context).apply {
            background = circleBg
        }
        val checkImg = ImageView(context).apply {
            setImageDrawable(MezonIcon.checkmarkLargeIcon.getDrawable(context, 0xFFFFFFFF.toInt()))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        circleWrap.addView(checkImg, FrameLayout.LayoutParams(
            LayoutHelper.dp(36), LayoutHelper.dp(36), Gravity.CENTER
        ))
        body.addView(circleWrap, LinearLayout.LayoutParams(circleSize, circleSize))

        body.addView(TextView(context).apply {
            text = getString(R.string.transfer_success_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(themeColors.onSurface)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(20) })

        body.addView(TextView(context).apply {
            text = "$amount $symbol"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(themeColors.onSurface)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(4) })

        body.addView(View(context), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(32)
        ))

        addDetailRow(context, body, getString(R.string.transfer_success_recipient_label), recipient, isFirst = true)
        addDetailRow(context, body, getString(R.string.transfer_success_note_label), note.ifEmpty { getString(R.string.transfer_success_dash) })
        addDetailRow(context, body, getString(R.string.transfer_success_time_label), time)

        outer.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val divider = View(context).apply {
            setBackgroundColor(themeColors.outlineVariant)
        }
        outer.addView(divider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(1)
        ))

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, LayoutHelper.dp(12), 0, LayoutHelper.dp(4))
        }
        outer.addView(actionRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        actionRow.addView(
            buildActionButton(context, MezonIcon.shareIcon, getString(R.string.transfer_success_share)) { onShareClick() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        actionRow.addView(
            buildActionButton(context, MezonIcon.downloadIcon, getString(R.string.transfer_success_save_image)) { onSaveImageClick() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        actionRow.addView(
            buildActionButton(context, MezonIcon.arrowLeftRightIcon, getString(R.string.transfer_success_new_transfer)) { onNewTransferClick() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val doneBtn = TextView(context).apply {
            text = getString(R.string.transfer_success_done)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(themeColors.onPrimaryContainer)
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(32f).toFloat()
                setColor(themeColors.primaryContainer)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onDone?.invoke()
                finishFragment()
            }
        }
        outer.addView(doneBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(52)
        ).apply {
            leftMargin  = LayoutHelper.dp(16)
            rightMargin = LayoutHelper.dp(16)
            topMargin   = LayoutHelper.dp(8)
            bottomMargin = LayoutHelper.dp(24)
        })

        return wrapWithActionBar("", outer)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun addDetailRow(
        context: Context,
        parent: LinearLayout,
        label: String,
        value: String,
        isFirst: Boolean = false
    ) {
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        block.addView(TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(themeColors.onSurfaceVariant)
        })
        block.addView(TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(themeColors.onSurface)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(2) })

        parent.addView(block, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = if (isFirst) 0 else LayoutHelper.dp(16) })
    }

    private fun buildActionButton(
        context: Context,
        icon: MezonIcon,
        label: String,
        onClick: () -> Unit
    ): LinearLayout {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            foreground = androidx.core.content.ContextCompat.getDrawable(context, outValue.resourceId)
            setPadding(LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8))
            setOnClickListener { onClick() }
        }
        val img = ImageView(context).apply {
            setImageDrawable(icon.getDrawable(context, themeColors.onSurface))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        col.addView(img, LinearLayout.LayoutParams(LayoutHelper.dp(28), LayoutHelper.dp(28)))
        col.addView(TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(themeColors.onSurface)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(4) })
        return col
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private fun onShareClick() {
        val bmp = captureCardBitmap() ?: return
        val ctx = requireContext()
        val file = File(ctx.cacheDir, "transfer_${System.currentTimeMillis()}.png")
        runCatching {
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(intent, getString(R.string.transfer_success_share)))
        }.onFailure {
            showToast(ctx, it.message.orEmpty())
        }
    }

    private fun onSaveImageClick() {
        val bmp = captureCardBitmap() ?: return
        val ctx = requireContext()
        runCatching {
            val filename = "transfer_${System.currentTimeMillis()}.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
                val uri: Uri? = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let { ctx.contentResolver.openOutputStream(it)?.use { s -> bmp.compress(Bitmap.CompressFormat.PNG, 95, s) } }
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                dir.mkdirs()
                FileOutputStream(File(dir, filename)).use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
            }
            showToast(ctx, getString(R.string.transfer_success_save_image))
        }.onFailure {
            showToast(ctx, it.message.orEmpty())
        }
    }

    private fun onNewTransferClick() {
        onNewTransfer?.invoke()
        pendingNewTransferAfterClose = true
        finishFragment()
    }

    override fun onTransitionAnimationEnd(isOpen: Boolean, backward: Boolean) {
        super.onTransitionAnimationEnd(isOpen, backward)
        if (!isOpen && backward && pendingNewTransferAfterClose) {
            pendingNewTransferAfterClose = false
            onNewTransferAfterClose?.invoke()
        }
    }

    private fun captureCardBitmap(): Bitmap? {
        val v = cardView ?: return null
        if (v.width == 0 || v.height == 0) return null
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    private fun showToast(context: Context, msg: String) {
        val parent = getLayoutContainer() ?: (fragmentView as? ViewGroup) ?: return
        ToastOverlay(context, themeColors).show(parent, ToastOverlay.ToastType.INFO, msg)
    }
}
