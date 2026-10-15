package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.ui.cells.MezonIcon

class ReactionDetailBottomSheet(
    context: Context,
    message: MessageEntity,
    selectedEmojiId: Long,
    private val currentUserId: Long,
    private val themeColors: ThemeColors,
    private val membersResolver: (senderIds: Set<Long>) -> Map<Long, ClanMember>,
    private val notificationCenter: NotificationCenter,
    private val clanId: Long,
    private val channelId: Long,
    private val parentChannelId: Long,
    private val onRemoveReaction: (emojiId: Long, emoji: String, count: Int) -> Unit
) : BottomSheet(context), NotificationCenter.NotificationCenterDelegate {

    private lateinit var senderList: RecyclerView
    private val listScreenLocation = IntArray(2)
    private var swipeDismissAllowed = false

    init {
        setAllowNestedScroll(false)
    }

    override fun canDismissWithSwipe(): Boolean = swipeDismissAllowed

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            senderList.getLocationOnScreen(listScreenLocation)
            val fromHeader = ev.rawY < listScreenLocation[1]
            swipeDismissAllowed = fromHeader || (
                !senderList.canScrollVertically(-1) && senderList.scrollState != RecyclerView.SCROLL_STATE_SETTLING
            )
        }
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            swipeDismissAllowed = false
        }
        return handled
    }

    private val memberEvents = intArrayOf(
        NotificationCenter.clanMembersDidLoad,
        NotificationCenter.channelMembersDidLoad,
        NotificationCenter.userClansDidLoad,
        NotificationCenter.dialogsNeedReload
    )

    override fun onStart() {
        super.onStart()
        memberEvents.forEach { notificationCenter.addObserver(this, it) }
    }

    override fun onStop() {
        memberEvents.forEach { notificationCenter.removeObserver(this, it) }
        super.onStop()
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        val target = args.firstOrNull() as? Long
        when (id) {
            NotificationCenter.clanMembersDidLoad -> {
                if (clanId == 0L || target != clanId) return
            }
            NotificationCenter.channelMembersDidLoad -> {
                if (target != channelId && (parentChannelId == 0L || target != parentChannelId)) return
            }
            NotificationCenter.userClansDidLoad -> Unit
            NotificationCenter.dialogsNeedReload -> {
                if (clanId != 0L) return
            }
            else -> return
        }
        senderAdapter.refreshProfiles()
    }

    private val groups = message.combineReactions()
    private var selectedTabIndex = groups.indexOfFirst { it.emojiId == selectedEmojiId }.coerceAtLeast(0)
    private lateinit var senderAdapter: SenderAdapter
    private lateinit var titleView: TextView
    private lateinit var deleteButton: FrameLayout
    private lateinit var tabContainer: LinearLayout

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        val sheetHeight = (AndroidUtilities.displaySize.y * 0.6f).toInt()
        containerHeight = sheetHeight
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        val tabScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(themeColors.background)
        }
        tabContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(LayoutHelper.dp(10), LayoutHelper.dp(10), LayoutHelper.dp(10), LayoutHelper.dp(10))
        }
        buildTabs()
        tabScroll.addView(tabContainer, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, LayoutHelper.dp(60)
        ))

        val tabDivider = View(context).apply {
            setBackgroundColor(themeColors.outlineVariant)
        }
        rootLayout.addView(tabScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(60)
        ))
        rootLayout.addView(tabDivider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(2)
        ))

        val headerRow = FrameLayout(context).apply {
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12), 0)
        }
        titleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            setTextColor(themeColors.onSurface)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        headerRow.addView(titleView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.CENTER_VERTICAL
        ))

        deleteButton = FrameLayout(context).apply {
            val bg = GradientDrawable().apply {
                setColor(0xFFD30E0E.toInt())
                cornerRadius = LayoutHelper.dp(8).toFloat()
            }
            background = bg
            setPadding(LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8))
            visibility = View.GONE
        }
        val trashIcon = ImageView(context).apply {
            val d = MezonIcon.trashIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
        }
        deleteButton.addView(trashIcon, FrameLayout.LayoutParams(LayoutHelper.dp(20), LayoutHelper.dp(20), Gravity.CENTER))
        deleteButton.setOnClickListener { deleteMyReaction() }
        headerRow.addView(deleteButton, FrameLayout.LayoutParams(
            LayoutHelper.dp(36), LayoutHelper.dp(36),
            Gravity.END or Gravity.CENTER_VERTICAL
        ))

        rootLayout.addView(headerRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        senderList = RecyclerView(
            context, null, com.mezon.mobile.R.attr.mezonRecyclerListViewStyle
        ).apply {
            layoutManager = LinearLayoutManager(context)
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(10), LayoutHelper.dp(12), LayoutHelper.dp(12))
            clipToPadding = false
        }
        senderAdapter = SenderAdapter()
        senderList.adapter = senderAdapter
        rootLayout.addView(senderList, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        updateContent()

        setCustomView(rootLayout)
        super.onCreate(savedInstanceState)
    }

    private fun buildTabs() {
        tabContainer.removeAllViews()
        val loader = MezonImageLoader.getInstance(context)
        for (i in groups.indices) {
            val group = groups[i]
            val tab = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4))
                val bg = GradientDrawable().apply {
                    cornerRadius = LayoutHelper.dp(8).toFloat()
                    setColor(if (i == selectedTabIndex) themeColors.tertiary else 0x00000000)
                }
                background = bg
            }

            val emojiIv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val url = com.mezon.mobile.util.getEmojiUrl(group.emojiId.toString(), context)
            val emojiSize = LayoutHelper.dp(24)
            if (url != null) {
                fun loadTabEmoji(loadUrl: String, isRetry: Boolean) {
                    loader.loadDrawable(loadUrl, emojiSize, emojiSize,
                        onSuccess = { drawable ->
                            emojiIv.setImageDrawable(drawable)
                            if (drawable is android.graphics.drawable.AnimatedImageDrawable) {
                                drawable.start()
                            }
                        },
                        onError = {
                            if (!isRetry) {
                                val direct = com.mezon.mobile.util.getEmojiDirectUrl(group.emojiId.toString(), context)
                                if (direct != null && direct != loadUrl) {
                                    loadTabEmoji(direct, true)
                                }
                            }
                        })
                }
                loadTabEmoji(url, false)
            }
            tab.addView(emojiIv, LinearLayout.LayoutParams(LayoutHelper.dp(24), LayoutHelper.dp(24)))

            val countTv = TextView(context).apply {
                text = group.totalCount.toString()
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                setTextColor(themeColors.onSurface)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(LayoutHelper.dp(6), 0, 0, 0)
            }
            tab.addView(countTv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            tab.setOnClickListener {
                selectedTabIndex = i
                updateContent()
                updateTabHighlights()
            }

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { rightMargin = LayoutHelper.dp(7) }
            tabContainer.addView(tab, lp)
        }
    }

    private fun updateTabHighlights() {
        for (i in 0 until tabContainer.childCount) {
            val tab = tabContainer.getChildAt(i) as? LinearLayout ?: continue
            val bg = tab.background as? GradientDrawable ?: continue
            bg.setColor(if (i == selectedTabIndex) themeColors.tertiary else 0x00000000)
        }
    }

    private fun updateContent() {
        if (groups.isEmpty()) return
        val group = groups.getOrNull(selectedTabIndex) ?: return
        titleView.text = group.emoji

        val hasMyReaction = group.senders.any { it.senderId == currentUserId && it.count > 0 }
        deleteButton.visibility = if (hasMyReaction) View.VISIBLE else View.GONE

        senderAdapter.setSenders(group.senders)
    }

    private fun deleteMyReaction() {
        val group = groups.getOrNull(selectedTabIndex) ?: return
        val myCount = group.senders.find { it.senderId == currentUserId }?.count ?: return
        onRemoveReaction(group.emojiId, group.emoji, myCount)
        dismiss()
    }

    private inner class SenderAdapter : RecyclerView.Adapter<SenderViewHolder>() {
        private var senders: List<ReactionSender> = emptyList()
        private val profiles = ReactionSenderProfiles(membersResolver)

        fun setSenders(newSenders: List<ReactionSender>) {
            senders = newSenders.filter { it.count > 0 }
            profiles.setSenders(senders)
            notifyDataSetChanged()
        }

        fun refreshProfiles() {
            profiles.refresh().forEach { notifyItemChanged(it, "profile") }
        }

        override fun getItemCount() = senders.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SenderViewHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, LayoutHelper.dp(56)
                )
                setBackgroundColor(themeColors.background)
            }
            return SenderViewHolder(row)
        }

        override fun onBindViewHolder(holder: SenderViewHolder, position: Int) {
            holder.bind(senders[position], profiles[position])
        }
    }

    private inner class SenderViewHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        private val avatarView = com.mezon.mobile.ui.cells.BackupImageView(row.context).apply {
            setAspectFill(true)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }
        private val nameView = TextView(row.context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            setTextColor(themeColors.onSurface)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        private val countView = TextView(row.context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setTextColor(themeColors.onSurfaceVariant)
            setSingleLine(true)
        }

        init {
            row.addView(avatarView, LinearLayout.LayoutParams(LayoutHelper.dp(40), LayoutHelper.dp(40)))
            val textColumn = LinearLayout(row.context).apply {
                orientation = LinearLayout.VERTICAL
                addView(nameView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(20)
                ))
                addView(countView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(18)
                ))
            }
            row.addView(textColumn, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { leftMargin = LayoutHelper.dp(8) })
        }

        fun bind(sender: ReactionSender, profile: ReactionSenderProfile) {
            nameView.text = profile.name
            countView.text = "×${sender.count}"

            val proxyUrl = if (profile.avatarUrl.isNotBlank()) {
                com.mezon.mobile.util.avatarImgproxyUrl(profile.avatarUrl, LayoutHelper.dp(40))
            } else null
            avatarView.setImage(proxyUrl, sender.senderId, profile.username)
        }
    }
}
