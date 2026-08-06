package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
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
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.ui.cells.MezonIcon

class ReactionDetailBottomSheet(
    context: Context,
    private val message: MessageEntity,
    private val selectedEmojiId: Long,
    private val currentUserId: Long,
    private val themeColors: ThemeColors,
    private val memberResolver: (senderId: Long) -> ClanMember?,
    private val onRemoveReaction: (emojiId: Long, emoji: String, count: Int) -> Unit
) : BottomSheet(context) {

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

        val recyclerView = RecyclerView(
            context, null, com.mezon.mobile.R.attr.mezonRecyclerListViewStyle
        ).apply {
            layoutManager = LinearLayoutManager(context)
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(10), LayoutHelper.dp(12), LayoutHelper.dp(12))
            clipToPadding = false
        }
        senderAdapter = SenderAdapter()
        recyclerView.adapter = senderAdapter
        rootLayout.addView(recyclerView, LinearLayout.LayoutParams(
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
            val url = com.mezon.mobile.util.getEmojiUrl(group.emojiId.toString())
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
                                val direct = com.mezon.mobile.util.getEmojiDirectUrl(group.emojiId.toString())
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

        senderAdapter.setSenders(group.senders, group.emoji)
    }

    private fun deleteMyReaction() {
        val group = groups.getOrNull(selectedTabIndex) ?: return
        val myCount = group.senders.find { it.senderId == currentUserId }?.count ?: return
        onRemoveReaction(group.emojiId, group.emoji, myCount)
        dismiss()
    }

    private inner class SenderAdapter : RecyclerView.Adapter<SenderViewHolder>() {
        private var senders: List<ReactionSender> = emptyList()
        private var emoji: String = ""

        fun setSenders(newSenders: List<ReactionSender>, newEmoji: String) {
            senders = newSenders.filter { it.count > 0 }
            emoji = newEmoji
            notifyDataSetChanged()
        }

        override fun getItemCount() = senders.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SenderViewHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, LayoutHelper.dp(10))
                setBackgroundColor(themeColors.background)
            }
            return SenderViewHolder(row)
        }

        override fun onBindViewHolder(holder: SenderViewHolder, position: Int) {
            holder.bind(senders[position])
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
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(themeColors.onSurface)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        private val countView = TextView(row.context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            setTextColor(themeColors.onSurfaceVariant)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        init {
            row.addView(avatarView, LinearLayout.LayoutParams(LayoutHelper.dp(36), LayoutHelper.dp(36)))
            row.addView(nameView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = LayoutHelper.dp(12)
            })
            row.addView(countView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = LayoutHelper.dp(12) })
        }

        fun bind(sender: ReactionSender) {
            val member = memberResolver(sender.senderId)
            val name = if (member != null) {
                member.clanNick.ifBlank { member.displayName.ifBlank { member.username } }
            } else {
                "User ${sender.senderId}"
            }
            nameView.text = name
            countView.text = "x${sender.count}"

            val avatarUrl = if (member != null) {
                member.clanAvatar.ifBlank { member.avatarUrl }
            } else ""

            val proxyUrl = if (avatarUrl.isNotBlank()) com.mezon.mobile.util.avatarImgproxyUrl(avatarUrl, LayoutHelper.dp(36)) else null
            avatarView.setImage(proxyUrl, sender.senderId, member?.username ?: "")
        }
    }
}
