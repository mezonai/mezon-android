package com.mezon.mobile.home.clans.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
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
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.qr.InviteQrBottomSheet
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class InvitePeopleBottomSheet(
    context: Context,
    private val controller: InvitePeopleController,
    private val clanId: Long,
    private val clanName: String,
    private val clanLogo: String,
) : BottomSheet(context, needFocusable = true) {

    companion object {
        private const val PAYLOAD_ACTION = "action"
        private const val GROUP_AVATAR_COLOR = 0xFFF58C29.toInt()
        private const val SEARCH_HEIGHT_DP = 40f
        private const val SEARCH_ICON_SIZE_DP = 18f
        private const val SEARCH_ICON_INSET_DP = 8f
        private const val SEARCH_TEXT_GAP_DP = 8f
        private const val SEARCH_CLEAR_SIZE_DP = 20f
        private const val SEARCH_CLEAR_INSET_DP = 8f
    }

    private val theme = ThemeColors.instance
    private val sheetScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    private var linkErrorHandled = false

    private lateinit var searchField: EditText
    private lateinit var searchClearBtn: ImageView
    private lateinit var recycler: RecyclerListView
    private lateinit var adapter: TargetAdapter
    private lateinit var listContainer: FrameLayout
    private lateinit var emptyStateView: LinearLayout
    private lateinit var targetsLoadingView: FrameLayout
    private lateinit var shareActionBtn: View
    private lateinit var copyActionBtn: View
    private lateinit var qrActionBtn: View

    init {
        containerHeight = (AndroidUtilities.displaySize.y * 0.88f).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.background)
            setPadding(LayoutHelper.dp(16f), 0, LayoutHelper.dp(16f), LayoutHelper.dp(16f))
        }

        root.addView(TextView(context).apply {
            text = context.getString(R.string.invite_sheet_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.colorText)
            gravity = Gravity.CENTER
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(12f)
            bottomMargin = LayoutHelper.dp(16f)
        })

        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(4f), 0, LayoutHelper.dp(4f), 0)
        }
        shareActionBtn = addActionButton(
            actionsRow,
            MezonIcon.shareIcon,
            context.getString(R.string.invite_share),
        ) { shareInviteLink() }
        copyActionBtn = addActionButton(
            actionsRow,
            MezonIcon.linkIcon,
            context.getString(R.string.invite_copy_link),
        ) { copyInviteLink() }
        qrActionBtn = addActionButton(
            actionsRow,
            MezonIcon.myQRcodeIcon,
            context.getString(R.string.invite_qr_code),
        ) { showInviteQrSheet() }
        root.addView(actionsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(12f)
        })

        val iconSize = LayoutHelper.dp(SEARCH_ICON_SIZE_DP)
        val iconInset = LayoutHelper.dp(SEARCH_ICON_INSET_DP)
        val textGap = LayoutHelper.dp(SEARCH_TEXT_GAP_DP)
        val clearSize = LayoutHelper.dp(SEARCH_CLEAR_SIZE_DP)
        val clearInset = LayoutHelper.dp(SEARCH_CLEAR_INSET_DP)
        val textStartPad = iconInset + iconSize + textGap
        val textEndPad = clearInset + clearSize + textGap

        val searchWrap = FrameLayout(context).apply {
            background = roundedFieldBg(theme.surfaceVariant)
            clipChildren = true
        }
        searchField = EditText(context).apply {
            hint = context.getString(R.string.invite_search_placeholder)
            setHintTextColor(theme.textDisabled)
            setTextColor(theme.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setSingleLine(true)
            maxLines = 1
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            background = null
            minHeight = 0
            minimumHeight = 0
            setPadding(textStartPad, 0, textEndPad, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val text = s?.toString().orEmpty()
                    searchClearBtn.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                    controller.onSearch(text)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        val searchIcon = ImageView(context).apply {
            val d = MezonIcon.magnifyingIcon.getDrawable(context).mutate()
            d.colorFilter = PorterDuffColorFilter(theme.textDisabled, PorterDuff.Mode.SRC_IN)
            d.setBounds(0, 0, iconSize, iconSize)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        searchClearBtn = ImageView(context).apply {
            val d = MezonIcon.circleXIcon.getDrawable(context).mutate()
            d.colorFilter = PorterDuffColorFilter(theme.colorText, PorterDuff.Mode.SRC_IN)
            d.setBounds(0, 0, clearSize, clearSize)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                searchField.setText("")
                controller.applySearchImmediately("")
            }
        }
        searchWrap.addView(
            searchField,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        searchWrap.addView(
            searchIcon,
            FrameLayout.LayoutParams(iconSize, iconSize, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                leftMargin = iconInset
            },
        )
        searchWrap.addView(
            searchClearBtn,
            FrameLayout.LayoutParams(clearSize, clearSize, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = clearInset
            },
        )
        root.addView(searchWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, SEARCH_HEIGHT_DP.toInt()).apply {
            bottomMargin = LayoutHelper.dp(10f)
        })

        listContainer = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(10f)
                setColor(theme.surfaceVariant)
            }
            clipToOutline = true
        }
        adapter = TargetAdapter()
        recycler = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@InvitePeopleBottomSheet.adapter
            overScrollMode = View.OVER_SCROLL_NEVER
            background = null
            addItemDecoration(InviteRowDividerDecoration(theme.textDisabled))
        }
        listContainer.addView(recycler, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        emptyStateView = buildEmptyState()
        listContainer.addView(emptyStateView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        targetsLoadingView = FrameLayout(context).apply {
            visibility = View.GONE
            addView(ProgressBar(context), LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
        }
        listContainer.addView(targetsLoadingView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        root.addView(listContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        setCustomView(root)
        super.onCreate(savedInstanceState)

        controller.open(clanId, clanName, clanLogo)
        collectJob = sheetScope.launch {
            controller.state.collect { render(it) }
        }
    }

    override fun dismiss() {
        collectJob?.cancel()
        sheetScope.cancel()
        controller.reset()
        super.dismiss()
    }

    private fun roundedFieldBg(color: Int) = GradientDrawable().apply {
        cornerRadius = LayoutHelper.dpf(8f)
        setColor(color)
    }

    private fun addActionButton(
        parent: LinearLayout,
        icon: MezonIcon,
        label: String,
        onClick: () -> Unit,
    ): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val iconSize = LayoutHelper.dp(40)
        val innerIconSize = LayoutHelper.dp(24)
        val iconWrap = FrameLayout(context).apply {
            clipChildren = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.surfaceVariant)
            }
        }
        val iconView = ImageView(context).apply {
            setImageDrawable(icon.getDrawable(context, theme.colorText))
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        iconWrap.addView(
            iconView,
            FrameLayout.LayoutParams(innerIconSize, innerIconSize, Gravity.CENTER),
        )
        column.addView(iconWrap, LinearLayout.LayoutParams(iconSize, iconSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = LayoutHelper.dp(6f)
        })
        column.addView(TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(theme.colorText)
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        parent.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return column
    }

    private fun buildEmptyState(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(LayoutHelper.dp(24f), LayoutHelper.dp(16f), LayoutHelper.dp(24f), LayoutHelper.dp(16f))

            addView(ImageView(context).apply {
                setImageDrawable(MezonIcon.searchFriendIcon.getDrawable(context, theme.textDisabled))
                scaleType = ImageView.ScaleType.FIT_CENTER
                alpha = 0.85f
            }, LinearLayout.LayoutParams(LayoutHelper.dp(96f), LayoutHelper.dp(96f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = LayoutHelper.dp(10f)
            })

            addView(TextView(context).apply {
                text = context.getString(R.string.invite_empty_title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(theme.colorText)
                gravity = Gravity.CENTER_HORIZONTAL
            }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                bottomMargin = LayoutHelper.dp(10f)
            })

            addView(TextView(context).apply {
                text = context.getString(R.string.invite_empty_description)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(theme.textDisabled)
                gravity = Gravity.CENTER_HORIZONTAL
            }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                bottomMargin = LayoutHelper.dp(14f)
            })

            addView(TextView(context).apply {
                text = context.getString(R.string.invite_empty_action)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(theme.textLink)
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setOnClickListener {
                    showToast(ToastOverlay.ToastType.INFO, context.getString(R.string.invite_empty_action))
                }
            }, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL))
        }
    }

    private fun render(state: InvitePeopleUiState) {
        val hasLink = state.inviteUrl.isNotBlank() && !state.isLoadingLink
        val actionsEnabled = hasLink
        listOf(shareActionBtn, copyActionBtn, qrActionBtn).forEach { btn ->
            btn.isEnabled = actionsEnabled
            btn.alpha = if (actionsEnabled) 1f else 0.45f
        }

        targetsLoadingView.visibility = if (state.isLoadingTargets) View.VISIBLE else View.GONE
        val showEmpty = !state.isLoadingTargets && state.dmTargets.isEmpty()
        emptyStateView.visibility = if (showEmpty) View.VISIBLE else View.GONE
        recycler.visibility = if (showEmpty) View.GONE else View.VISIBLE

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
        showToast(ToastOverlay.ToastType.SUCCESS, context.getString(R.string.invite_link_copied))
    }

    private fun shareInviteLink() {
        val url = controller.state.value.inviteUrl
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.invite_share)))
    }

    private fun showInviteQrSheet() {
        val url = controller.state.value.inviteUrl
        if (url.isBlank()) return
        InviteQrBottomSheet(
            context,
            theme,
            url,
            clanName,
            clanLogo,
        ).apply {
            setDrawNavigationBar(true)
            show()
        }
    }

    private fun showToast(type: ToastOverlay.ToastType, message: String) {
        val act = context as? MainActivity ?: return
        ToastOverlay(context, theme).show(act.drawerLayoutContainer, type, message)
    }

    private inner class InviteTargetViewHolder(
        v: View,
        val avatarWrap: FrameLayout,
        val avatar: AvatarView?,
        val groupIcon: ImageView?,
        val titleTv: TextView,
        val actionBtn: FrameLayout,
        val actionLabel: TextView,
        val actionSpinner: ProgressBar,
    ) : RecyclerView.ViewHolder(v)

    private inner class TargetAdapter : RecyclerView.Adapter<InviteTargetViewHolder>() {
        private var rows: List<InviteDmTarget> = emptyList()
        private var sentIds: Set<String> = emptySet()
        private var sendingId: String? = null

        init {
            setHasStableIds(true)
        }

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
                if (sentChanged || sendingChanged) {
                    notifyItemChanged(index, PAYLOAD_ACTION)
                }
            }
        }

        override fun getItemId(position: Int): Long = rows[position].rowId.hashCode().toLong()

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InviteTargetViewHolder {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    LayoutHelper.dp(60f),
                )
                setPadding(LayoutHelper.dp(20f), 0, LayoutHelper.dp(20f), 0)
            }

            val avatarWrap = FrameLayout(context)
            val avatar = AvatarView(context).apply { setSizeDp(40) }
            avatarWrap.addView(avatar, FrameLayout.LayoutParams(LayoutHelper.dp(40f), LayoutHelper.dp(40f), Gravity.CENTER))
            val groupIcon = ImageView(context).apply {
                visibility = View.GONE
                setImageDrawable(MezonIcon.groupIcon.getDrawable(context, 0xFFFFFFFF.toInt()))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            avatarWrap.addView(groupIcon, FrameLayout.LayoutParams(LayoutHelper.dp(20f), LayoutHelper.dp(20f), Gravity.CENTER))
            row.addView(avatarWrap, LinearLayout.LayoutParams(LayoutHelper.dp(40f), LayoutHelper.dp(40f)).apply {
                rightMargin = LayoutHelper.dp(10f)
            })

            val titleTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(theme.colorText)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            row.addView(titleTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
                rightMargin = LayoutHelper.dp(8f)
            })

            val actionBtn = FrameLayout(context)
            val actionLabel = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(theme.colorText)
            }
            val actionSpinner = ProgressBar(context).apply {
                visibility = View.GONE
            }
            actionBtn.addView(actionLabel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            actionBtn.addView(actionSpinner, FrameLayout.LayoutParams(
                LayoutHelper.dp(20f),
                LayoutHelper.dp(20f),
                Gravity.CENTER,
            ))
            row.addView(actionBtn, LinearLayout.LayoutParams(LayoutHelper.dp(80f), LayoutHelper.dp(32f)))

            return InviteTargetViewHolder(row, avatarWrap, avatar, groupIcon, titleTv, actionBtn, actionLabel, actionSpinner)
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

            val isGroup = target.channelType == CHANNEL_TYPE_GROUP
            val hasAvatar = !target.avatarUrl.isNullOrBlank()
            holder.avatarWrap.background = null

            if (isGroup && !hasAvatar) {
                holder.avatar?.visibility = View.GONE
                holder.groupIcon?.visibility = View.VISIBLE
                holder.avatarWrap.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(GROUP_AVATAR_COLOR)
                }
            } else {
                holder.avatar?.visibility = View.VISIBLE
                holder.groupIcon?.visibility = View.GONE
                val avatarKey = target.userId ?: target.channelId ?: 0L
                holder.avatar?.setInfo(avatarKey, target.title)
                holder.avatar?.setImageUrl(target.avatarUrl)
            }

            bindActionButton(holder, target)
        }

        private fun bindActionButton(holder: InviteTargetViewHolder, target: InviteDmTarget) {
            val sent = sentIds.contains(target.rowId)
            val sending = sendingId == target.rowId
            holder.itemView.alpha = if (sent) 0.6f else 1f
            holder.actionBtn.setOnClickListener(null)

            holder.actionBtn.background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(16f)
                setColor(theme.tertiary)
                setStroke(LayoutHelper.dp(1f), theme.borderDim)
            }

            when {
                sent -> {
                    holder.actionLabel.text = context.getString(R.string.invite_btn_invited)
                    holder.actionLabel.visibility = View.VISIBLE
                    holder.actionSpinner.visibility = View.GONE
                    holder.actionBtn.isEnabled = false
                }
                sending -> {
                    holder.actionLabel.visibility = View.INVISIBLE
                    holder.actionSpinner.visibility = View.VISIBLE
                    holder.actionBtn.isEnabled = false
                }
                else -> {
                    holder.actionLabel.text = context.getString(R.string.invite_btn_invite)
                    holder.actionLabel.visibility = View.VISIBLE
                    holder.actionSpinner.visibility = View.GONE
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

    private class InviteRowDividerDecoration(
        private val dividerColor: Int,
    ) : RecyclerView.ItemDecoration() {
        private val paint = android.graphics.Paint().apply {
            color = dividerColor
            alpha = (255 * 0.45f).toInt()
        }

        override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val left = parent.paddingLeft.toFloat()
            val right = (parent.width - parent.paddingRight).toFloat()
            val childCount = parent.childCount
            for (i in 0 until childCount - 1) {
                val child = parent.getChildAt(i)
                val bottom = child.bottom.toFloat()
                c.drawRect(left, bottom, right, bottom + 1f, paint)
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
