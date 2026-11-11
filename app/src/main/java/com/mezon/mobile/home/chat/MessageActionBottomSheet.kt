package com.mezon.mobile.home.chat

import com.mezon.mobile.ui.cells.setIconColor
import com.mezon.mobile.ui.cells.setMezonIconResource
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.messageHasExplicitTextBody
import com.mezon.mobile.util.parseContentText


class MessageActionBottomSheet(
    context: Context,
    private val message: MessageEntity,
    private val isMyMessage: Boolean,
    private val isDM: Boolean = false,
    private val isPinned: Boolean = false,
    private val canDeleteMessage: Boolean = false,
    private val canManageThread: Boolean = false,
    private val hasMedia: Boolean = false,
    private val hasImage: Boolean = false,
    private val showForwardSingle: Boolean = true,
    private val showForwardAllNearby: Boolean = false,
    private val showEditMessage: Boolean = false,
    private val showTopicDiscussion: Boolean = false,
    private val showPinActions: Boolean = true,
    private val showResend: Boolean = false,
    private val showQuickMenu: Boolean = false,
    private val listener: MessageActionListener
) : BottomSheet(context) {

    enum class ActionType {
        Reply,
        ResendMessage,
        ForwardMessage,
        ForwardAllNearby,
        EditMessage,
        CopyText,
        ShareText,
        TopicDiscussion,
        PinMessage,
        UnPinMessage,
        DeleteMessage,
        CreateThread,
        MarkUnRead,
        AddToInbox,
        SaveMedia,
        CopyMediaLink,
        CopyImage,
        ShareImage,
        Report,
        GiveACoffee,
        QuickMenu
    }

    interface MessageActionListener {
        fun onActionSelected(action: ActionType, message: MessageEntity)
        fun onReactionSelected(emojiId: Long, emoji: String, message: MessageEntity) {}
        fun onOpenEmojiPicker(message: MessageEntity) {}
    }

    private val theme: ThemeColors = ThemeColors.instance
    private var selectedAction: ActionType? = null

    init {
        setOnDismissListener {
            val action = selectedAction ?: return@setOnDismissListener
            selectedAction = null
            // The chat window must regain focus before an action can open its keyboard.
            listener.onActionSelected(action, message)
        }
    }

    private val menuBackgroundColor: Int get() = theme.background
    private val warningIconBackgroundColor: Int get() = theme.surface

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        setSnapPoints(0.5f, 1f, initialIndex = 0)
        setFitToContents(true)
        val scrollView = NestedScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                0, LayoutHelper.dp(20), 0,
                LayoutHelper.dp(20) + AndroidUtilities.navigationBarHeight
            )
        }

        rootLayout.addView(
            buildQuickReactionStrip(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = LayoutHelper.dp(12)
                rightMargin = LayoutHelper.dp(12)
                bottomMargin = LayoutHelper.dp(6)
            }
        )

        val frequentActions = buildFrequentActions()
        val normalActions = buildNormalActions()
        val mediaActions = buildMediaActions()
        val warningActions = buildWarningActions()

        if (frequentActions.isNotEmpty()) {
            rootLayout.addView(
                buildActionGroup(frequentActions),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = LayoutHelper.dp(10)
                    rightMargin = LayoutHelper.dp(10)
                    bottomMargin = LayoutHelper.dp(10)
                }
            )
        }

        if (normalActions.isNotEmpty()) {
            rootLayout.addView(
                buildActionGroup(normalActions),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = LayoutHelper.dp(10)
                    rightMargin = LayoutHelper.dp(10)
                    bottomMargin = LayoutHelper.dp(10)
                }
            )
        }

        if (mediaActions.isNotEmpty()) {
            rootLayout.addView(
                buildActionGroup(mediaActions),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = LayoutHelper.dp(10)
                    rightMargin = LayoutHelper.dp(10)
                    bottomMargin = LayoutHelper.dp(10)
                }
            )
        }

        if (warningActions.isNotEmpty()) {
            rootLayout.addView(
                buildActionGroup(warningActions, isWarning = true),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = LayoutHelper.dp(10)
                    rightMargin = LayoutHelper.dp(10)
                }
            )
        }

        scrollView.addView(rootLayout, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setCustomView(scrollView)

        super.onCreate(savedInstanceState)
        fixNavigationBar()
    }


    private data class ActionItem(
        val type: ActionType,
        val title: String,
        val iconRes: Int,
        val isWarning: Boolean = false,
        val applyIconTint: Boolean = true
    )

  
    private fun buildQuickReactionStrip(): LinearLayout {
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        data class QuickEmoji(val id: Long, val shortname: String)
        val fallbacks = listOf(
            QuickEmoji(7227274405304181951L, ":100:"),
            QuickEmoji(7227274405302432668L, ":joy:"),
            QuickEmoji(LIKE_EMOJI_ID, LIKE_EMOJI_SHORTNAME),
            QuickEmoji(7227274405305046042L, ":laughing:"),
            QuickEmoji(7227274405301971870L, ":innocent:")
        )

        val emojiSize = LayoutHelper.dp(22)
        val btnPad = LayoutHelper.dp(10)
        val loader = MezonImageLoader.getInstance(context)

        for (qe in fallbacks) {
            val btn = FrameLayout(context).apply {
                val bg = GradientDrawable().apply {
                    setColor(menuBackgroundColor)
                    cornerRadius = LayoutHelper.dp(50).toFloat()
                }
                background = bg
                setPadding(btnPad, btnPad, btnPad, btnPad)
            }
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            btn.addView(iv, FrameLayout.LayoutParams(emojiSize, emojiSize, Gravity.CENTER))

            val url = com.mezon.mobile.util.getEmojiUrl(qe.id.toString(), context)
            if (url != null) {
                fun loadIntoIv(loadUrl: String, isRetry: Boolean) {
                    loader.load(loadUrl, emojiSize, emojiSize, onSuccess = { bmp ->
                        iv.setImageBitmap(bmp)
                    }, onError = {
                        if (!isRetry) {
                            val direct = com.mezon.mobile.util.getEmojiDirectUrl(qe.id.toString(), context)
                            if (direct != null && direct != loadUrl) {
                                loadIntoIv(direct, true)
                            }
                        }
                    })
                }
                loadIntoIv(url, false)
            }

            btn.setOnClickListener {
                listener.onReactionSelected(qe.id, qe.shortname, message)
                dismiss()
            }

            strip.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = if (qe == fallbacks.first()) 0 else LayoutHelper.dp(4)
                rightMargin = if (qe == fallbacks.last()) 0 else LayoutHelper.dp(4)
            })
        }

        val plusBtn = FrameLayout(context).apply {
            val bg = GradientDrawable().apply {
                setColor(menuBackgroundColor)
                cornerRadius = LayoutHelper.dp(50).toFloat()
            }
            background = bg
            setPadding(btnPad, btnPad, btnPad, btnPad)
        }
        val plusIv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val d = MezonIcon.loadDrawable(context, R.drawable.mz_emoji_20, ThemeColors.defaultIconTint)
            d.colorFilter = android.graphics.PorterDuffColorFilter(theme.textStrong, android.graphics.PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
        }
        plusBtn.addView(plusIv, FrameLayout.LayoutParams(emojiSize, emojiSize, Gravity.CENTER))
        plusBtn.setOnClickListener {
            listener.onOpenEmojiPicker(message)
            dismiss()
        }
        strip.addView(plusBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = LayoutHelper.dp(4)
        })

        return strip
    }

    private fun buildFrequentActions(): List<ActionItem> {
        val actions = mutableListOf<ActionItem>()

        if (showResend) {
            actions.add(ActionItem(
                ActionType.ResendMessage,
                context.getString(R.string.action_resend_message),
                R.drawable.mz_chat_20
            ))
        }

        if (!isMyMessage) {
            actions.add(ActionItem(
                ActionType.GiveACoffee,
                context.getString(R.string.action_give_coffee),
                R.drawable.mz_send_money_24,
                applyIconTint = false
            ))
        }

        actions.add(ActionItem(
            ActionType.Reply,
            context.getString(R.string.action_reply),
            R.drawable.mz_reply_20
        ))

        if (showForwardSingle) {
            actions.add(ActionItem(
                ActionType.ForwardMessage,
                context.getString(R.string.action_forward),
                R.drawable.mz_forward_20
            ))
        }

        if (showForwardAllNearby) {
            actions.add(ActionItem(
                ActionType.ForwardAllNearby,
                context.getString(R.string.action_forward_all_nearby),
                R.drawable.mz_forward_all_16
            ))
        }

        if (showEditMessage) {
            actions.add(ActionItem(
                ActionType.EditMessage,
                context.getString(R.string.action_edit),
                R.drawable.mz_edit_20
            ))
        }

        if (!isDM && canManageThread) {
            actions.add(ActionItem(
                ActionType.CreateThread,
                context.getString(R.string.action_create_thread),
                R.drawable.mz_thread_add_24,
                applyIconTint = false
            ))
        }

        if (showTopicDiscussion) {
            actions.add(ActionItem(
                ActionType.TopicDiscussion,
                context.getString(R.string.topic_discussion),
                R.drawable.mz_topic_24,
                applyIconTint = false
            ))
        }

        return actions
    }

 
    private fun buildNormalActions(): List<ActionItem> {
        val actions = mutableListOf<ActionItem>()

        if (message.code != MessageEntity.CODE_LOCATION) {
            actions.add(ActionItem(
                ActionType.CopyText,
                context.getString(R.string.action_copy_text),
                R.drawable.mz_copy_20
            ))

            if (messageHasExplicitTextBody(message.content) && parseContentText(message.content).isNotBlank()) {
                actions.add(ActionItem(
                    ActionType.ShareText,
                    context.getString(R.string.action_share_text),
                    R.drawable.mz_share_20
                ))
            }
        }

        if (showPinActions) {
            if (isPinned) {
                actions.add(ActionItem(
                    ActionType.UnPinMessage,
                    context.getString(R.string.action_unpin),
                    R.drawable.mz_pin_off_20,
                    applyIconTint = false
                ))
            } else {
                actions.add(ActionItem(
                    ActionType.PinMessage,
                    context.getString(R.string.action_pin),
                    R.drawable.mz_pin_20,
                    applyIconTint = false
                ))
            }
        }

        if (message.id > 0L && message.sendState == MessageEntity.SEND_STATE_SENT) {
            actions.add(ActionItem(
                ActionType.AddToInbox,
                context.getString(R.string.action_add_to_inbox),
                R.drawable.mz_inbox_24
            ))
        }

        if (showQuickMenu) {
            actions.add(ActionItem(
                ActionType.QuickMenu,
                context.getString(R.string.action_quick_menu),
                R.drawable.mz_bolt_20
            ))
        }

        return actions
    }

 
    private fun buildMediaActions(): List<ActionItem> {
        if (!hasMedia && !hasImage) return emptyList()
        val actions = mutableListOf<ActionItem>()

        if (hasMedia) {
            val isVideo = isVideoAttachmentType(
                message.attachmentFiletype, message.attachmentFilename, message.attachmentUrl,
            )
            val saveMediaTitleRes = if (isVideo) {
                R.string.action_save_video
            } else {
                R.string.action_save_image
            }
            val copyLinkTitleRes = if (isVideo) {
                R.string.action_copy_video_link
            } else {
                R.string.action_copy_image_link
            }

            actions.add(ActionItem(
                ActionType.SaveMedia,
                context.getString(saveMediaTitleRes),
                R.drawable.mz_download_20
            ))
            actions.add(ActionItem(
                ActionType.CopyMediaLink,
                context.getString(copyLinkTitleRes),
                R.drawable.mz_link_20
            ))
        }

        if (hasImage) {
            actions.add(ActionItem(
                ActionType.CopyImage,
                context.getString(R.string.action_copy_image),
                R.drawable.mz_image_20
            ))
            actions.add(ActionItem(
                ActionType.ShareImage,
                context.getString(R.string.action_share_image),
                R.drawable.mz_share_20
            ))
        }

        return actions
    }


    private fun buildWarningActions(): List<ActionItem> {
        val actions = mutableListOf<ActionItem>()

        if (canDeleteMessage) {
            actions.add(ActionItem(
                ActionType.DeleteMessage,
                context.getString(R.string.action_delete),
                R.drawable.mz_trash_20,
                isWarning = true
            ))
        }

        if (!isMyMessage) {
            actions.add(ActionItem(
                ActionType.Report,
                context.getString(R.string.action_report),
                R.drawable.mz_flag_20,
                isWarning = true
            ))
        }

        return actions
    }

  
    private fun buildActionGroup(actions: List<ActionItem>, isWarning: Boolean = false): LinearLayout {
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(menuBackgroundColor)
                cornerRadius = LayoutHelper.dp(10).toFloat()
            }
            clipToOutline = true
        }

        for ((index, action) in actions.withIndex()) {
            group.addView(buildActionRow(action))
            if (index < actions.size - 1) {
                group.addView(buildDivider())
            }
        }

        return group
    }

  
    private fun buildActionRow(item: ActionItem): FrameLayout {
        val row = FrameLayout(context).apply {
            setBackgroundColor(menuBackgroundColor)
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = context.getDrawable(outValue.resourceId)
            isClickable = true
            isFocusable = true
        }

        val textColor = if (item.isWarning) theme.error else theme.textStrong
        val iconColor = if (item.isWarning) theme.error else theme.textStrong

        // RN: MezonIconCDN renders at 20×20 (or 18×18 for warning/delete)
        val icon = ImageView(context).apply {
            try {
                setMezonIconResource(item.iconRes)
            } catch (_: Exception) {}
            if (item.isWarning) {
                drawable?.let { it.setIconColor(textColor) }
            } else if (item.applyIconTint) {
                setColorFilter(iconColor)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        // RN: actionText { fontSize: h8 = verticalScale(11) } ≈ 15sp on Android
        val title = TextView(context).apply {
            text = item.title
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
        }

        if (item.isWarning) {
            // Warning icon uses the sheet surface inside the menu card.
            val iconBg = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    setColor(warningIconBackgroundColor)
                    cornerRadius = LayoutHelper.dp(16).toFloat()
                }
            }
            // Icon 18×18 inside 32×32 circle
            iconBg.addView(icon, LayoutHelper.createFrame(18, 18, Gravity.CENTER))
            row.addView(iconBg, LayoutHelper.createFrame(32, 32,
                Gravity.CENTER_VERTICAL or Gravity.START, 10f, 0f, 10f, 0f))
            row.addView(title, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                Gravity.CENTER_VERTICAL or Gravity.START, 48f, 0f, 16f, 0f))
        } else {
            // RN: icon { width: 20, height: 20 } — icon is 20dp inside a left-padded area
            row.addView(icon, LayoutHelper.createFrame(20, 20,
                Gravity.CENTER_VERTICAL or Gravity.START, 16f, 0f, 0f, 0f))
            row.addView(title, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                Gravity.CENTER_VERTICAL or Gravity.START, 48f, 0f, 16f, 0f))
        }

        // RN: actionItem paddingVertical=12 → height ≈ 12+20+12 = 44
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(44)
        )

        row.setOnClickListener {
            selectedAction = item.type
            dismiss()
        }

        return row
    }

    /**
     * Thin divider line between action items within a group.
     */
    private fun buildDivider(): View {
        return View(context).apply {
            setBackgroundColor(theme.border)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
        }
    }
}
