package com.mezon.mobile.home.clans.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.qr.QrCodeUtils
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class InvitePeopleBottomSheet(
    context: Context,
    private val controller: InvitePeopleController,
    private val clanId: Long,
    private val clanName: String,
    private val clanLogo: String,
) : BottomSheet(context) {

    private val theme = ThemeColors.instance
    private val sheetScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    private var copiedResetJob: Job? = null
    private var linkErrorHandled = false

    private lateinit var searchField: EditText
    private lateinit var recycler: RecyclerListView
    private lateinit var adapter: TargetAdapter
    private lateinit var linkField: TextView
    private lateinit var copyBtn: TextView
    private lateinit var copyQrBtn: TextView
    private lateinit var qrRowView: LinearLayout
    private lateinit var loadingOverlay: FrameLayout

    init {
        containerHeight = (AndroidUtilities.displaySize.y * 0.88f).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTitle(context.getString(R.string.invite_modal_title, clanName))

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.background)
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(4f), LayoutHelper.dp(16f), LayoutHelper.dp(16f))
        }

        searchField = EditText(context).apply {
            hint = context.getString(R.string.invite_search_placeholder)
            setHintTextColor(theme.textDisabled)
            setTextColor(theme.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setSingleLine(true)
            background = roundedFieldBg()
            setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(10f), LayoutHelper.dp(14f), LayoutHelper.dp(10f))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    controller.onSearch(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(searchField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(10f)
        })

        val listFrame = FrameLayout(context)
        adapter = TargetAdapter()
        recycler = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@InvitePeopleBottomSheet.adapter
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        listFrame.addView(recycler, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        root.addView(listFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.dp(220f)))

        root.addView(View(context).apply { setBackgroundColor(theme.borderDim) },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1).apply {
                topMargin = LayoutHelper.dp(10f)
                bottomMargin = LayoutHelper.dp(12f)
            })

        val labelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelRow.addView(TextView(context).apply {
            text = context.getString(R.string.invite_send_link_label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(theme.textDisabled)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        qrRowView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { showQrDialog() }
            visibility = View.GONE
        }
        qrRowView.addView(ImageView(context).apply {
            setImageDrawable(MezonIcon.myQRcodeIcon.getDrawable(context, theme.textLink))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(LayoutHelper.dp(16f), LayoutHelper.dp(16f)).apply {
            rightMargin = LayoutHelper.dp(5f)
        })
        copyQrBtn = TextView(context).apply {
            text = context.getString(R.string.invite_share_qr)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(theme.textLink)
            typeface = Typeface.DEFAULT_BOLD
        }
        qrRowView.addView(copyQrBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        labelRow.addView(qrRowView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(labelRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(8f)
        })

        val linkCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(10f)
                setColor(theme.border)
            }
        }

        linkField = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(theme.textDisabled)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(8f), LayoutHelper.dp(12f))
        }
        linkCard.addView(linkField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        copyBtn = TextView(context).apply {
            text = context.getString(R.string.invite_copy)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.onPrimary)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(10f)
                setColor(theme.primary)
            }
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(12f), LayoutHelper.dp(16f), LayoutHelper.dp(12f))
            isClickable = true
            setOnClickListener { copyInviteLink() }
        }
        linkCard.addView(copyBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(linkCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val contentWrap = FrameLayout(context)
        contentWrap.addView(root, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        loadingOverlay = FrameLayout(context).apply {
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
            addView(ProgressBar(context), LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
        }
        contentWrap.addView(loadingOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        setCustomView(contentWrap)
        super.onCreate(savedInstanceState)

        controller.open(clanId, clanName, clanLogo)
        collectJob = sheetScope.launch {
            controller.state.collect { render(it) }
        }
    }

    override fun dismiss() {
        collectJob?.cancel()
        copiedResetJob?.cancel()
        sheetScope.cancel()
        controller.reset()
        super.dismiss()
    }

    private fun roundedFieldBg() = GradientDrawable().apply {
        cornerRadius = LayoutHelper.dpf(10f)
        setColor(theme.border)
    }

    private fun render(state: InvitePeopleUiState) {
        val hasLink = state.inviteUrl.isNotBlank() && !state.isLoadingLink
        loadingOverlay.visibility = if (state.isLoadingLink) View.VISIBLE else View.GONE
        linkField.text = if (state.inviteUrl.isNotBlank()) state.inviteUrl else "…"
        copyBtn.isEnabled = hasLink
        copyBtn.alpha = if (hasLink) 1f else 0.5f
        qrRowView.visibility = if (hasLink) View.VISIBLE else View.GONE

        if (state.isCopied) {
            copyBtn.text = context.getString(R.string.invite_copied)
            copyBtn.background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(10f)
                setColor(theme.success)
            }
        } else {
            copyBtn.text = context.getString(R.string.invite_copy)
            copyBtn.background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(10f)
                setColor(theme.primary)
            }
        }

        if (state.linkError != null && !state.isLoadingLink && !linkErrorHandled) {
            linkErrorHandled = true
            val msg = when (state.linkError) {
                InvitePeopleController.ERROR_NO_WELCOME_CHANNEL ->
                    context.getString(R.string.clan_invite_need_channel)
                else -> context.getString(R.string.invite_create_link_error)
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            dismiss()
            return
        }

        adapter.submit(state)
    }

    private fun copyInviteLink() {
        val url = controller.state.value.inviteUrl
        if (url.isBlank()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("invite", url))
        controller.markCopied(true)
        copiedResetJob?.cancel()
        copiedResetJob = sheetScope.launch {
            delay(1500)
            controller.markCopied(false)
        }
    }

    private fun showQrDialog() {
        val url = controller.state.value.inviteUrl
        if (url.isBlank()) return
        val size = LayoutHelper.dp(220f)
        val qr = QrCodeUtils.generateQr(url, size)

        val img = ImageView(context).apply {
            setImageBitmap(qr)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), LayoutHelper.dp(8f))
        }

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.invite_share_qr))
            .setView(img)
            .setPositiveButton(context.getString(R.string.invite_share_qr)) { d, _ ->
                d.dismiss()
                shareQrImage(url, qr)
            }
            .setNeutralButton(context.getString(R.string.invite_save_qr)) { d, _ ->
                d.dismiss()
                saveQrImage(qr)
            }
            .setNegativeButton(context.getString(R.string.common_close)) { d, _ -> d.dismiss() }
            .show()
    }

    private fun shareQrImage(url: String, qr: android.graphics.Bitmap) {
        try {
            val cache = java.io.File(context.cacheDir, "invite_qr.png")
            cache.outputStream().use { out ->
                qr.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cache,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, url)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.invite_share_qr)))
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.invite_create_link_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveQrImage(qr: android.graphics.Bitmap) {
        try {
            val filename = "mezon_invite_qr_${System.currentTimeMillis()}.png"
            val saved = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        qr.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                    true
                } else false
            } else {
                val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
                dir.mkdirs()
                val file = java.io.File(dir, filename)
                file.outputStream().use { out ->
                    qr.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                true
            }
            Toast.makeText(
                context,
                if (saved) context.getString(R.string.invite_qr_saved)
                else context.getString(R.string.invite_create_link_error),
                Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.invite_create_link_error), Toast.LENGTH_SHORT).show()
        }
    }

    private inner class TargetAdapter : RecyclerView.Adapter<TargetAdapter.VH>() {
        private var rows: List<InviteDmTarget> = emptyList()
        private var sentIds: Set<String> = emptySet()
        private var sendingId: String? = null

        fun submit(state: InvitePeopleUiState) {
            rows = state.dmTargets
            sentIds = state.sentTargetIds
            sendingId = state.sendingTargetId
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, LayoutHelper.dp(6f), 0, LayoutHelper.dp(6f))
            }

            val avatar = AvatarView(context).apply { setSizeDp(40) }
            row.addView(avatar, LinearLayout.LayoutParams(LayoutHelper.dp(40f), LayoutHelper.dp(40f)).apply {
                rightMargin = LayoutHelper.dp(12f)
                gravity = Gravity.CENTER_VERTICAL
            })

            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val titleTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(theme.colorText)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val subTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(theme.textDisabled)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            texts.addView(titleTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            texts.addView(subTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = LayoutHelper.dp(8f)
                gravity = Gravity.CENTER_VERTICAL
            })

            val actionBtn = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            row.addView(actionBtn, LinearLayout.LayoutParams(LayoutHelper.dp(80f), LayoutHelper.dp(34f)).apply {
                gravity = Gravity.CENTER_VERTICAL
            })

            return VH(row, avatar, titleTv, subTv, actionBtn)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val target = rows[position]
            val sent = sentIds.contains(target.rowId)
            val sending = sendingId == target.rowId

            holder.titleTv.text = target.title
            if (!target.subtitle.isNullOrBlank()) {
                holder.subTv.visibility = View.VISIBLE
                holder.subTv.text = target.subtitle
            } else {
                holder.subTv.visibility = View.GONE
            }

            val avatarKey = target.userId ?: target.channelId ?: 0L
            holder.avatar.setInfo(avatarKey, target.title)
            if (!target.avatarUrl.isNullOrBlank()) {
                holder.avatar.setImageUrl(target.avatarUrl)
            }

            when {
                sent -> {
                    holder.actionBtn.text = context.getString(R.string.invite_btn_sent)
                    holder.actionBtn.setTextColor(theme.textDisabled)
                    holder.actionBtn.background = GradientDrawable().apply {
                        cornerRadius = LayoutHelper.dpf(8f)
                        setColor(theme.surfaceVariant)
                        setStroke(LayoutHelper.dp(1f), theme.borderDim)
                    }
                    holder.actionBtn.isEnabled = false
                }
                sending -> {
                    holder.actionBtn.text = "…"
                    holder.actionBtn.setTextColor(theme.textDisabled)
                    holder.actionBtn.background = GradientDrawable().apply {
                        cornerRadius = LayoutHelper.dpf(8f)
                        setColor(theme.surfaceVariant)
                    }
                    holder.actionBtn.isEnabled = false
                }
                else -> {
                    holder.actionBtn.text = context.getString(R.string.invite_btn_invite)
                    holder.actionBtn.setTextColor(theme.onPrimary)
                    holder.actionBtn.background = GradientDrawable().apply {
                        cornerRadius = LayoutHelper.dpf(8f)
                        setColor(theme.primary)
                    }
                    holder.actionBtn.isEnabled = true
                    holder.actionBtn.setOnClickListener {
                        holder.actionBtn.isEnabled = false
                        controller.sendInviteToTarget(target) { ok, err ->
                            AndroidUtilities.runOnUIThread {
                                if (!ok) {
                                    Toast.makeText(
                                        context,
                                        err ?: context.getString(R.string.invite_send_error),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                }
            }
        }

        inner class VH(
            v: View,
            val avatar: AvatarView,
            val titleTv: TextView,
            val subTv: TextView,
            val actionBtn: TextView,
        ) : RecyclerView.ViewHolder(v)
    }
}
