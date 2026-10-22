package com.mezon.mobile.home.voice

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

internal enum class MeetingMessageStatus { SENDING, SENT, FAILED }

internal data class MeetingChatMessage(
    val name: String,
    val content: String,
    val timestamp: Long,
    val isMe: Boolean,
    val status: MeetingMessageStatus = MeetingMessageStatus.SENT,
)

/** Renders caller-owned messages; it does not send, receive or acknowledge messages. */
internal class MeetingChatBottomSheet(
    context: Context,
    private val theme: ThemeColors,
) : BottomSheet(context, needFocusable = true) {
    var onSend: ((String) -> Boolean)? = null
    var onCopyLink: (() -> Unit)? = null
    private var messages: List<MeetingChatMessage> = emptyList()
    private val list = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true } }
    private val messagesAdapter = object : RecyclerView.Adapter<MessageHolder>() {
        override fun getItemCount() = messages.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = MessageHolder(TextView(parent.context).apply {
            setTextColor(theme.onSurface)
            textSize = 15f
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(10), LayoutHelper.dp(16), LayoutHelper.dp(10))
        })
        override fun onBindViewHolder(holder: MessageHolder, position: Int) {
            val message = messages[position]
            val status = if (message.isMe) context.getString(when (message.status) {
                MeetingMessageStatus.SENDING -> R.string.external_meeting_sending
                MeetingMessageStatus.SENT -> R.string.external_meeting_sent
                MeetingMessageStatus.FAILED -> R.string.external_meeting_failed
            }) else ""
            val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(message.timestamp))
            holder.text.text = "${message.name} · $time\n${message.content}" + if (status.isEmpty()) "" else "\n$status"
        }
    }

    init {
        containerHeight = (context.resources.displayMetrics.heightPixels * 0.7f).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = context.getString(R.string.external_meeting_chat)
            textSize = 20f
            setTextColor(theme.onSurface)
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(12), LayoutHelper.dp(8), LayoutHelper.dp(12))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(android.widget.ImageView(context).apply {
            setImageDrawable(com.mezon.mobile.ui.cells.MezonIcon.linkIcon.getDrawable(context).apply {
                setTint(theme.onSurface)
            })
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12))
            contentDescription = context.getString(R.string.external_meeting_copy_link)
            setOnClickListener {
                onCopyLink?.invoke()
            }
        }, LinearLayout.LayoutParams(LayoutHelper.dp(48), LayoutHelper.dp(48)))
        root.addView(header)
        list.adapter = messagesAdapter
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val input = EditText(context).apply {
            hint = context.getString(R.string.external_meeting_message)
            setTextColor(theme.onSurface)
            setHintTextColor(theme.onSurfaceVariant)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
        }
        fun submit() {
            if (onSend?.invoke(input.text.toString()) == true) input.text.clear()
        }
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                submit()
                true
            } else false
        }
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(12), 0, LayoutHelper.dp(12), LayoutHelper.dp(12))
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(context).apply {
            text = context.getString(R.string.external_meeting_send)
            setTextColor(theme.primary)
            gravity = Gravity.CENTER
            setOnClickListener { submit() }
        }, LinearLayout.LayoutParams(LayoutHelper.dp(64), LayoutHelper.dp(48)))
        root.addView(row)
        setCustomView(root)
    }

    fun submitMessages(messages: List<MeetingChatMessage>) {
        val atBottom = !list.canScrollVertically(1)
        this.messages = messages.toList()
        messagesAdapter.notifyDataSetChanged()
        if (atBottom && messages.isNotEmpty()) list.scrollToPosition(messages.lastIndex)
    }

    private class MessageHolder(val text: TextView) : RecyclerView.ViewHolder(text)
}
