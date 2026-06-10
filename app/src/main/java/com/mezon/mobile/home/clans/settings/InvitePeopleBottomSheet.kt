package com.mezon.mobile.home.clans.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
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
import androidx.recyclerview.widget.DiffUtil
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

    companion object {
        private const val PAYLOAD_ACTION = "action"
        private const val VID_DIVIDER = 0x2201
    }

    private val theme = ThemeColors.instance
    private val sheetScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    private var copiedResetJob: Job? = null
    private var linkErrorHandled = false

    private lateinit var searchField: EditText
    private lateinit var recycler: RecyclerListView
    private lateinit var adapter: TargetAdapter
    private lateinit var loadingOverlay: FrameLayout

    private lateinit var copyLinkBtn: LinearLayout
    private lateinit var copyLinkLabel: TextView

    init {
        containerHeight = (AndroidUtilities.displaySize.y * 0.88f).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTitle(context.getString(R.string.invite_a_friend))

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.background)
        }

        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val shareBtn = makeActionButton(
            icon = MezonIcon.shareIcon,
            label = context.getString(R.string.invite_action_share),
        ) { shareInviteLink() }

        copyLinkBtn = makeActionButton(
            icon = MezonIcon.copyIcon,
            label = context.getString(R.string.invite_action_copy_link),
        ) { copyInviteLink() }
        copyLinkLabel = copyLinkBtn.getChildAt(1) as TextView

        val qrBtn = makeActionButton(
            icon = MezonIcon.scanQR,
            label = context.getString(R.string.invite_action_qr),
        ) { showQrDialog() }

        actionsRow.addView(shareBtn, actionColParams())
        actionsRow.addView(copyLinkBtn, actionColParams())
        actionsRow.addView(qrBtn, actionColParams())

        root.addView(
            actionsRow,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(8f)
                bottomMargin = LayoutHelper.dp(16f)
            },
        )

        root.addView(
            View(context).apply { setBackgroundColor(theme.borderDim) },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1),
        )

        val searchWrapper = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(8f)
                setStroke(LayoutHelper.dp(1), theme.borderDim)
                setColor(android.graphics.Color.TRANSPARENT)
            }
            setPadding(LayoutHelper.dp(12f), 0, LayoutHelper.dp(12f), 0)
        }

        val searchIcon = ImageView(context).apply {
            val d = MezonIcon.searchIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(theme.textDisabled, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
        }
        searchWrapper.addView(
            searchIcon,
            LinearLayout.LayoutParams(LayoutHelper.dp(18f), LayoutHelper.dp(18f)).apply {
                rightMargin = LayoutHelper.dp(10f)
            },
        )

        searchField = EditText(context).apply {
            hint = context.getString(R.string.invite_search_placeholder)
            setHintTextColor(theme.textDisabled)
            setTextColor(theme.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setSingleLine(true)
            background = null
            setPadding(0, 0, 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun afterTextChanged(s: Editable?) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    controller.onSearch(s?.toString().orEmpty())
                }
            })
        }
        searchWrapper.addView(
            searchField,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        root.addView(
            searchWrapper,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40).apply {
                topMargin = LayoutHelper.dp(14f)
                leftMargin = LayoutHelper.dp(14f)
                rightMargin = LayoutHelper.dp(14f)
                bottomMargin = LayoutHelper.dp(8f)
            },
        )

        adapter = TargetAdapter()
        recycler = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@InvitePeopleBottomSheet.adapter
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val listFrame = FrameLayout(context)
        listFrame.addView(recycler, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        loadingOverlay = FrameLayout(context).apply {
            setBackgroundColor(0x80000000.toInt())
            visibility = View.GONE
            addView(ProgressBar(context), LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
        }
        listFrame.addView(loadingOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        root.addView(listFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        setCustomView(root)
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


    private fun render(state: InvitePeopleUiState) {
        val hasLink = state.inviteUrl.isNotBlank() && !state.isLoadingLink
        loadingOverlay.visibility = if (state.isLoadingLink) View.VISIBLE else View.GONE

        if (state.isCopied) {
            copyLinkLabel.text = context.getString(R.string.invite_copied)
            copyLinkLabel.setTextColor(theme.success)
        } else {
            copyLinkLabel.text = context.getString(R.string.invite_action_copy_link)
            copyLinkLabel.setTextColor(theme.colorText)
        }
        copyLinkBtn.alpha = if (hasLink) 1f else 0.4f
        copyLinkBtn.isEnabled = hasLink

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

    private fun shareInviteLink() {
        val url = controller.state.value.inviteUrl
        if (url.isBlank()) {
            Toast.makeText(context, context.getString(R.string.invite_create_link_error), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.invite_action_share)))
        } catch (_: Exception) {
            copyInviteLink()
        }
    }

    private fun showQrDialog() {
        val url = controller.state.value.inviteUrl
        if (url.isBlank()) {
            Toast.makeText(context, context.getString(R.string.invite_create_link_error), Toast.LENGTH_SHORT).show()
            return
        }
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
                Toast.LENGTH_SHORT,
            ).show()
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.invite_create_link_error), Toast.LENGTH_SHORT).show()
        }
    }


    private fun makeActionButton(
        icon: MezonIcon,
        label: String,
        onClick: () -> Unit,
    ): LinearLayout {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.surfaceVariant)
            }
        }
        val iconView = ImageView(context).apply {
            val d = icon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(theme.onSurface, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        circle.addView(iconView, LayoutHelper.createFrame(24, 24, Gravity.CENTER))
        col.addView(circle, LayoutHelper.createLinear(56, 56, 0f, Gravity.CENTER_HORIZONTAL))

        val tv = TextView(context).apply {
            text = label
            setTextColor(theme.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }
        col.addView(
            tv,
            LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                0f, Gravity.CENTER_HORIZONTAL, 0f, 8f, 0f, 0f,
            ),
        )
        return col
    }

    private fun actionColParams() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        topMargin = LayoutHelper.dp(4f)
        bottomMargin = LayoutHelper.dp(4f)
    }


    private inner class InviteTargetViewHolder(
        itemView: View,
        val avatar: AvatarView,
        val titleTv: TextView,
        val subTv: TextView,
        val actionBtn: TextView,
    ) : RecyclerView.ViewHolder(itemView)

    private inner class TargetAdapter : RecyclerView.Adapter<InviteTargetViewHolder>() {
        private var rows: List<InviteDmTarget> = emptyList()
        private var sentIds: Set<String> = emptySet()
        private var sendingId: String? = null

        init { setHasStableIds(true) }

        fun submit(state: InvitePeopleUiState) {
            val newRows = state.dmTargets
            val newSent = state.sentTargetIds
            val newSending = state.sendingTargetId

            val listUnchanged = rows.size == newRows.size &&
                rows.zip(newRows).all { (old, new) -> old.rowId == new.rowId && old == new }

            if (listUnchanged) {
                val prevSent = sentIds
                val prevSending = sendingId
                sentIds = newSent
                sendingId = newSending
                if (prevSent != newSent || prevSending != newSending) {
                    notifyActionStateChanged(prevSent, newSent, prevSending, newSending)
                }
                return
            }

            val diff = DiffUtil.calculateDiff(TargetDiffCallback(rows, newRows))
            rows = newRows
            sentIds = newSent
            sendingId = newSending
            diff.dispatchUpdatesTo(this)
        }

        private fun notifyActionStateChanged(
            prevSent: Set<String>,
            newSent: Set<String>,
            prevSending: String?,
            newSending: String?,
        ) {
            rows.forEachIndexed { index, target ->
                val id = target.rowId
                val sentChanged = prevSent.contains(id) != newSent.contains(id)
                val sendingChanged = prevSending == id || newSending == id
                if (sentChanged || sendingChanged) notifyItemChanged(index, PAYLOAD_ACTION)
            }
        }

        override fun getItemId(position: Int): Long = rows[position].rowId.hashCode().toLong()
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InviteTargetViewHolder {
            val wrapper = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                )
            }

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padH = LayoutHelper.dp(14f)
                val padV = LayoutHelper.dp(11f)
                setPadding(padH, padV, padH, padV)
            }

            val avatar = AvatarView(context).apply { setSizeDp(44) }
            row.addView(avatar, LinearLayout.LayoutParams(LayoutHelper.dp(44f), LayoutHelper.dp(44f)).apply {
                rightMargin = LayoutHelper.dp(12f)
                gravity = Gravity.CENTER_VERTICAL
            })

            val textCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val titleTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(theme.colorText)
                typeface = Typeface.DEFAULT
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val subTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(theme.textDisabled)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                visibility = View.GONE
            }
            textCol.addView(titleTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            textCol.addView(subTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
                rightMargin = LayoutHelper.dp(8f)
            })

            val actionBtn = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT
                gravity = Gravity.CENTER
                setPadding(LayoutHelper.dp(16f), 0, LayoutHelper.dp(16f), 0)
                background = GradientDrawable().apply {
                    cornerRadius = LayoutHelper.dpf(8f)
                    setColor(theme.surfaceVariant)
                }
            }
            row.addView(actionBtn, LinearLayout.LayoutParams(LayoutHelper.dp(76f), LayoutHelper.dp(34f)).apply {
                gravity = Gravity.CENTER_VERTICAL
            })

            wrapper.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            val divider = View(context).apply {
                id = VID_DIVIDER
                setBackgroundColor(theme.borderDim)
            }
            wrapper.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                leftMargin = LayoutHelper.dp(14f)
            })

            return InviteTargetViewHolder(wrapper, avatar, titleTv, subTv, actionBtn)
        }

        override fun onBindViewHolder(holder: InviteTargetViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_ACTION)) {
                bindActionButton(holder, rows[position])
                return
            }
            onBindViewHolder(holder, position)
        }

        override fun onBindViewHolder(holder: InviteTargetViewHolder, position: Int) {
            val target = rows[position]

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
            } else {
                holder.avatar.setImageUrl(null)
            }

            holder.itemView.findViewById<View>(VID_DIVIDER)?.visibility =
                if (position == itemCount - 1) View.INVISIBLE else View.VISIBLE

            bindActionButton(holder, target)
        }

        private fun bindActionButton(holder: InviteTargetViewHolder, target: InviteDmTarget) {
            val sent = sentIds.contains(target.rowId)
            val sending = sendingId == target.rowId
            holder.actionBtn.setOnClickListener(null)
            when {
                sent -> {
                    holder.actionBtn.text = context.getString(R.string.invite_btn_sent)
                    holder.actionBtn.setTextColor(theme.textDisabled)
                    (holder.actionBtn.background as? GradientDrawable)?.setColor(theme.border)
                    holder.actionBtn.isEnabled = false
                }
                sending -> {
                    holder.actionBtn.text = "…"
                    holder.actionBtn.setTextColor(theme.textDisabled)
                    (holder.actionBtn.background as? GradientDrawable)?.setColor(theme.surfaceVariant)
                    holder.actionBtn.isEnabled = false
                }
                else -> {
                    holder.actionBtn.text = context.getString(R.string.invite_btn_invite)
                    holder.actionBtn.setTextColor(theme.colorText)
                    (holder.actionBtn.background as? GradientDrawable)?.setColor(theme.surfaceVariant)
                    holder.actionBtn.isEnabled = true
                    holder.actionBtn.setOnClickListener {
                        holder.actionBtn.isEnabled = false
                        controller.sendInviteToTarget(target) { ok, err ->
                            AndroidUtilities.runOnUIThread {
                                if (!ok) {
                                    Toast.makeText(
                                        context,
                                        err ?: context.getString(R.string.invite_send_error),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private class TargetDiffCallback(
        private val oldRows: List<InviteDmTarget>,
        private val newRows: List<InviteDmTarget>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldRows.size
        override fun getNewListSize() = newRows.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldRows[oldItemPosition].rowId == newRows[newItemPosition].rowId
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldRows[oldItemPosition] == newRows[newItemPosition]
    }
}
