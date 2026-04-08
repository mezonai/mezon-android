package com.mezon.mobile.home.chat.channelinfo

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.MemberResolver
import com.mezon.mobile.home.PinMessageController
import com.mezon.mobile.home.PinMessageData
import com.mezon.mobile.home.chat.PinMessageAdapter
import com.mezon.mobile.home.chat.PinMessageCell
import com.mezon.mobile.ui.cells.MezonIcon

class PinsTabHelper(
    private val channelId: Long,
    private val clanId: Long,
    private val channelType: Int,
    private val themeColors: ThemeColors,
    private val pinMessageController: PinMessageController,
    private val memberResolver: MemberResolver,
    private val notificationCenter: NotificationCenter,
    private val onJumpToMessage: (Long) -> Unit,
    private val getString: (Int) -> String
) : TabHelper {

    private var adapter: PinMessageAdapter? = null
    private var recyclerView: RecyclerListView? = null
    private var emptyView: View? = null
    private var loadingView: View? = null

    override fun buildView(context: Context): View {
        val container = FrameLayout(context)

        val delegate = object : PinMessageCell.PinMessageCellDelegate {
            override fun onJumpToMessage(data: PinMessageData) {
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.jumpToMessage, channelId, data.messageId
                )
                onJumpToMessage(data.messageId)
            }
            override fun onUnpin(data: PinMessageData) {
                pinMessageController.unpinMessage(channelId, clanId, data.messageId)
            }
        }

        adapter = PinMessageAdapter(
            themeColors,
            delegate,
            nameResolver = { resolveName(it) },
            avatarResolver = { resolveAvatar(it) }
        )

        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@PinsTabHelper.adapter
            val padH = LayoutHelper.dp(12)
            setPadding(padH, LayoutHelper.dp(8), padH, 0)
            clipToPadding = false
        }
        container.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        emptyView = buildEmptyView(context)
        container.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        val progress = android.widget.ProgressBar(context).apply { visibility = View.VISIBLE }
        loadingView = progress
        container.addView(progress, LayoutHelper.createFrame(48, 48, Gravity.CENTER))

        pinMessageController.loadPinMessages(channelId, clanId)
        return container
    }

    override fun reload() {
        val items = pinMessageController.getPinMessages(channelId)
        adapter?.setData(items)
        loadingView?.visibility = View.GONE
        if (items.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            recyclerView?.visibility = View.GONE
        } else {
            emptyView?.visibility = View.GONE
            recyclerView?.visibility = View.VISIBLE
        }
    }

    private fun resolveName(data: PinMessageData): String? {
        val member = memberResolver.resolveMember(data.senderId, clanId, channelId, channelType)
        return member?.let {
            if (clanId == 0L) it.displayName.ifBlank { it.username }
            else it.clanNick.ifBlank { it.displayName.ifBlank { it.username } }
        }
    }

    private fun resolveAvatar(data: PinMessageData): String? {
        val member = memberResolver.resolveMember(data.senderId, clanId, channelId, channelType)
        return member?.let {
            if (clanId == 0L) it.avatarUrl
            else it.clanAvatar.ifBlank { it.avatarUrl }
        }
    }

    private fun buildEmptyView(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(0, LayoutHelper.dp(50), 0, 0)
        }
        val icon = ImageView(context).apply {
            setImageDrawable(MezonIcon.emptyPinIcon.getDrawable(context))
        }
        container.addView(icon, LayoutHelper.createLinear(120, 120, 0f, Gravity.CENTER_HORIZONTAL))

        val text = TextView(context).apply {
            this.text = getString(R.string.pin_message_empty)
            setTextColor(themeColors.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f)
            gravity = Gravity.CENTER
            maxWidth = LayoutHelper.dp(300)
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(30), LayoutHelper.dp(16), 0)
        }
        container.addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL))
        return container
    }
}
