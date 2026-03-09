package com.mezon.mobile.home.chat

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.LongSparseArray
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.ui.cells.ActionBarView
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ChatFragment : BaseFragment() {

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CHANNEL_NAME = "channelName"
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_CHANNEL_TYPE = "channelType"

        fun newInstance(
            channelId: Long,
            channelName: String,
            clanId: Long = 0L,
            channelType: Int = 0
        ): ChatFragment = ChatFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_CHANNEL_ID, channelId)
                putString(ARG_CHANNEL_NAME, channelName)
                putLong(ARG_CLAN_ID, clanId)
                putInt(ARG_CHANNEL_TYPE, channelType)
            }
        }
    }

    @Inject lateinit var chatController: ChatController
    @Inject lateinit var dialogsController: DialogsController

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingView: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var adapter: ChatAdapter
    private lateinit var rootView: LinearLayout
    private lateinit var inputBar: LinearLayout

    private var channelId = 0L
    private var channelName = ""
    private var clanId = 0L
    private var channelType = 0
    private var isLoading = false
    private var isLoadingMore = false
    private var hasMoreTop = false
    private var hasMoreBottom = false

    private val messages = ArrayList<MessageEntity>()
    private val messagesDict = LongSparseArray<MessageEntity>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        channelName = arguments?.getString(ARG_CHANNEL_NAME) ?: ""
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        channelType = arguments?.getInt(ARG_CHANNEL_TYPE) ?: 0
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        rootView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        val chatActionBar = ActionBarView(requireContext(), themeColors).apply {
            setTitle(channelName)
            setBackClickListener { navigateBack() }
        }
        actionBar = chatActionBar
        rootView.addView(chatActionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56))

        val contentFrame = FrameLayout(requireContext())
        rootView.addView(contentFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        recyclerView = RecyclerView(requireContext()).apply {
            val lm = LinearLayoutManager(context)
            lm.reverseLayout = true
            lm.stackFromEnd = false
            layoutManager = lm
            visibility = View.GONE
        }
        contentFrame.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        loadingView = ProgressBar(requireContext()).apply { visibility = View.GONE }
        contentFrame.addView(loadingView, LayoutHelper.createFrame(48, 48, Gravity.CENTER))

        errorView = TextView(requireContext()).apply {
            setTextColor(themeColors.error)
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        contentFrame.addView(errorView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        inputBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(themeColors.surface)
            val pad = LayoutHelper.dp(8)
            setPadding(pad, pad, pad, pad)
        }
        rootView.addView(inputBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        inputField = EditText(requireContext()).apply {
            hint = getString(R.string.message_input_placeholder)
            setHintTextColor(themeColors.onSurfaceVariant)
            setTextColor(themeColors.onSurface)
            textSize = 15f
            maxLines = 6
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        inputBar.addView(inputField, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        sendButton = ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setColorFilter(themeColors.primary)
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            isEnabled = false
            alpha = 0.5f
            setOnClickListener { sendMessage() }
        }
        inputBar.addView(sendButton, LayoutHelper.createLinear(48, 48))

        inputField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val hasText = s?.isNotBlank() == true
                sendButton.isEnabled = hasText
                sendButton.alpha = if (hasText) 1f else 0.5f
            }
        })

        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }

        adapter = ChatAdapter(themeColors)
        recyclerView.adapter = adapter

        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialogsController.setCurrentChannel(channelId)
        isLoading = true
        showLoading()
        chatController.loadMessages(channelId, clanId)

        observe(NotificationCenter.messagesDidLoad) { _, args ->
            if (args.size < 5 || args[0] != channelId) return@observe
            @Suppress("UNCHECKED_CAST")
            val loadedMessages = args[1] as? ArrayList<MessageEntity> ?: return@observe
            val moreTop = args[2] as? Boolean ?: false
            val moreBottom = args[3] as? Boolean ?: false
            val isCache = args[4] as? Boolean ?: false

            if (isCache && messages.isEmpty()) {
                for (m in loadedMessages.reversed()) messages.add(m)
                for (m in loadedMessages) messagesDict.put(m.id, m)
                hasMoreTop = moreTop
                hasMoreBottom = moreBottom
            } else if (!isCache) {
                if (messages.isEmpty()) {
                    for (m in loadedMessages.reversed()) messages.add(m)
                    for (m in loadedMessages) messagesDict.put(m.id, m)
                } else {
                    for (m in loadedMessages) {
                        if (messagesDict.get(m.id) == null) {
                            if (m.timestampSeconds >= (messages.firstOrNull()?.timestampSeconds ?: 0L)) {
                                messages.add(0, m)
                            } else {
                                messages.add(m)
                            }
                            messagesDict.put(m.id, m)
                        }
                    }
                }
                if (moreTop) hasMoreTop = true
                if (moreBottom) hasMoreBottom = true
            }

            isLoading = false
            isLoadingMore = false
            refreshUI()
            if (!moreTop && !moreBottom) scrollToBottom()
        }

        observe(NotificationCenter.didReceiveNewMessages) { _, args ->
            if (args.size < 2 || args[0] != channelId) return@observe
            val entity = args[1] as? MessageEntity ?: return@observe
            if (messagesDict.get(entity.id) != null) return@observe
            messages.add(0, entity)
            messagesDict.put(entity.id, entity)
            refreshUI()
            scrollToBottom()
        }

        observe(NotificationCenter.messageDidUpdate) { _, args ->
            if (args.size < 2 || args[0] != channelId) return@observe
            val entity = args[1] as? MessageEntity ?: return@observe
            val idx = messages.indexOfFirst { it.id == entity.id }
            if (idx >= 0) {
                messages[idx] = entity
                messagesDict.put(entity.id, entity)
                updateVisibleRows()
            }
        }

        observe(NotificationCenter.messageDidDelete) { _, args ->
            if (args.size < 2 || args[0] != channelId) return@observe
            val messageId = args[1] as? Long ?: return@observe
            val idx = messages.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                messages.removeAt(idx)
                messagesDict.delete(messageId)
                refreshUI()
            }
        }

        observe(NotificationCenter.messagesLoadError) { _, args ->
            if (args.isNotEmpty() && args[0] == channelId) {
                isLoading = false
                isLoadingMore = false
                if (messages.isEmpty()) {
                    showError(args.getOrNull(1) as? String ?: "Failed to load")
                }
            }
        }

        observe(NotificationCenter.themeChanged) { _, _ ->
            rootView.setBackgroundColor(themeColors.background)
            inputBar.setBackgroundColor(themeColors.surface)
            inputField.setTextColor(themeColors.onSurface)
            inputField.setHintTextColor(themeColors.onSurfaceVariant)
            sendButton.setColorFilter(themeColors.primary)
            actionBar?.applyTheme()
            adapter.notifyDataSetChanged()
        }

        setupLoadMore()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dialogsController.clearCurrentChannel()
        messages.clear()
        messagesDict.clear()
    }

    private fun setupLoadMore() {
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (isLoadingMore || !hasMoreTop) return
                val lm = rv.layoutManager as LinearLayoutManager
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible >= adapter.messagesEndRow - 3 && adapter.itemCount > 0) {
                    val oldest = messages.lastOrNull()?.id ?: return
                    isLoadingMore = true
                    chatController.loadMoreTop(channelId, clanId, oldest)
                }
            }
        })
    }

    private fun refreshUI() {
        if (messages.isNotEmpty()) {
            showMessages()
        } else if (isLoading) {
            showLoading()
        }
    }

    private fun showLoading() {
        loadingView.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        errorView.visibility = View.GONE
    }

    private fun showError(message: String) {
        loadingView.visibility = View.GONE
        recyclerView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        errorView.text = message
    }

    private fun showMessages() {
        loadingView.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        adapter.showLoadingUp = hasMoreTop
        adapter.showLoadingDown = hasMoreBottom
        adapter.setData(messages)
    }

    private fun scrollToBottom() {
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
        if (lm.findFirstVisibleItemPosition() <= 3) {
            recyclerView.post { recyclerView.scrollToPosition(0) }
        }
    }

    private fun updateVisibleRows() {
        val count = recyclerView.childCount
        for (i in 0 until count) {
            val child = recyclerView.getChildAt(i)
            if (child is ChatMessageCell) {
                val msg = child.messageEntity ?: continue
                val updated = messagesDict.get(msg.id) ?: continue
                if (updated !== msg) {
                    child.setMessage(updated)
                }
            }
        }
    }

    private fun sendMessage() {
        val text = inputField.text?.toString()?.trim() ?: return
        if (text.isBlank()) return
        chatController.sendMessage(channelId, clanId, channelType, text)
        inputField.text?.clear()
    }
}
