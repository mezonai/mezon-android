package com.mezon.mobile.home.chat.channelinfo

import android.app.Activity
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
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.MemberResolver
import com.mezon.mobile.home.PinMessageController
import com.mezon.mobile.home.PinMessageData
import com.mezon.mobile.home.chat.MessageEntity
import com.mezon.mobile.home.chat.PinMessageAdapter
import com.mezon.mobile.home.chat.PinMessageCell
import com.mezon.mobile.ui.cells.MezonIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PinsTabHelper(
    private val channelId: Long,
    private val clanId: Long,
    private val channelType: Int,
    private val themeColors: ThemeColors,
    private val pinMessageController: PinMessageController,
    private val chatController: ChatController,
    private val scope: CoroutineScope,
    private val memberResolver: MemberResolver,
    private val notificationCenter: NotificationCenter,
    private val hostActivity: Activity,
    private val onJumpToMessage: (Long) -> Unit,
    private val getString: (Int) -> String
) : TabHelper {

    private var adapter: PinMessageAdapter? = null
    private var recyclerView: RecyclerListView? = null
    private var emptyView: View? = null
    private var loadingView: View? = null
    private val cachedMessagesById = HashMap<Long, MessageEntity>()

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
                val act = hostActivity
                if (act.isFinishing || act.isDestroyed) return
                AlertDialog.Builder(act)
                    .setTitle(getString(R.string.unpin_message_confirm_title))
                    .setMessage(getString(R.string.unpin_message_confirm_description))
                    .setPositiveButton(getString(R.string.common_yes)) { _, _ ->
                        pinMessageController.unpinMessage(channelId, clanId, data.messageId)
                    }
                    .setNegativeButton(getString(R.string.common_cancel), null)
                    .create()
                    .show()
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

        val cachedPins = pinMessageController.getPinMessages(channelId)
        if (cachedPins.isNotEmpty()) {
            applyPinList(cachedPins)
            refreshFromServer(showLoading = false)
        } else {
            showLoading()
            refreshFromServer(showLoading = true)
        }
        return container
    }

    fun refreshFromServer(showLoading: Boolean = false) {
        if (showLoading) showLoading()
        pinMessageController.loadPinMessages(channelId, clanId, noCache = false)
    }

    fun onPinRemoved(messageId: Long) {
        val removed = adapter?.removeByMessageId(messageId) == true
        cachedMessagesById.remove(messageId)
        updateEmptyState()
        if (!removed) reload()
    }

    override fun reload() {
        applyPinList(pinMessageController.getPinMessages(channelId))
    }

    private fun showLoading() {
        loadingView?.visibility = View.VISIBLE
        recyclerView?.visibility = View.GONE
        emptyView?.visibility = View.GONE
    }

    private fun updateEmptyState() {
        val hasItems = (adapter?.pinCount ?: 0) > 0
        loadingView?.visibility = View.GONE
        if (hasItems) {
            emptyView?.visibility = View.GONE
            recyclerView?.visibility = View.VISIBLE
        } else {
            emptyView?.visibility = View.VISIBLE
            recyclerView?.visibility = View.GONE
        }
    }

    private fun applyPinList(items: List<PinMessageData>) {
        if (items.isEmpty()) {
            cachedMessagesById.clear()
            adapter?.setData(emptyList())
            updateEmptyState()
            return
        }
        val messageIds = items.map { it.messageId }.filter { it != 0L }.distinct()
        val missingIds = messageIds.filter { !cachedMessagesById.containsKey(it) }
        if (missingIds.isEmpty()) {
            cachedMessagesById.keys.retainAll(messageIds.toSet())
            adapter?.setData(items, HashMap(cachedMessagesById), mergeMessages = true)
            updateEmptyState()
            return
        }
        scope.launch(Dispatchers.Main.immediate) {
            val fetched = chatController.getMessagesByIds(channelId, missingIds)
            cachedMessagesById.putAll(fetched)
            cachedMessagesById.keys.retainAll(messageIds.toSet())
            adapter?.setData(items, HashMap(cachedMessagesById), mergeMessages = true)
            updateEmptyState()
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
