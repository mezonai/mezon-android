package com.mezon.mobile.home.chat

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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors


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
    private val listener: MessageActionListener
) : BottomSheet(context) {

    enum class ActionType {
        Reply,
        EditMessage,
        ForwardMessage,
        CopyText,
        PinMessage,
        UnPinMessage,
        DeleteMessage,
        CreateThread,
        MarkUnRead,
        SaveMedia,
        CopyMediaLink,
        CopyImage,
        ShareImage,
        Report
    }

    interface MessageActionListener {
        fun onActionSelected(action: ActionType, message: MessageEntity)
    }

    private val theme: ThemeColors = ThemeColors.instance

    private val secondaryColor: Int
        get() = when (theme.resolvedMode) {
            com.mezon.mobile.ui.theme.ThemeMode.LIGHT -> 0xFFFFFFFF.toInt()   // RN light: #ffffff
            com.mezon.mobile.ui.theme.ThemeMode.DARK -> 0xFF1C1D23.toInt()    // RN dark:  #1c1d23
            com.mezon.mobile.ui.theme.ThemeMode.ABYSS -> 0xFF19153C.toInt()   // RN abyss: #19153C
            else -> 0xFF1C1D23.toInt()
        }
    private val tertiaryColor: Int
        get() = when (theme.resolvedMode) {
            com.mezon.mobile.ui.theme.ThemeMode.LIGHT -> 0xFFF2F3F6.toInt()   // RN light: #f2f3f6
            com.mezon.mobile.ui.theme.ThemeMode.DARK -> 0xFF383A43.toInt()    // RN dark:  #383a43
            com.mezon.mobile.ui.theme.ThemeMode.ABYSS -> 0xFF141319.toInt()   // RN abyss: #141319
            else -> 0xFF383A43.toInt()
        }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, LayoutHelper.dp(20), 0, LayoutHelper.dp(20))
        }

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
        val isWarning: Boolean = false
    )

  
    private fun buildFrequentActions(): List<ActionItem> {
        val actions = mutableListOf<ActionItem>()

        actions.add(ActionItem(
            ActionType.Reply,
            context.getString(R.string.action_reply),
            R.drawable.ic_arrowangleleftuplight  
        ))

        actions.add(ActionItem(
            ActionType.ForwardMessage,
            context.getString(R.string.action_forward),
            R.drawable.ic_arrowanglerightuplight 
        ))

        if (isMyMessage) {
            actions.add(ActionItem(
                ActionType.EditMessage,
                context.getString(R.string.action_edit),
                R.drawable.ic_pencil_iconlight 
            ))
        }

        if (!isDM && canManageThread) {
            actions.add(ActionItem(
                ActionType.CreateThread,
                context.getString(R.string.action_create_thread),
                R.drawable.ic_thread_icon
            ))
        }

        return actions
    }

 
    private fun buildNormalActions(): List<ActionItem> {
        val actions = mutableListOf<ActionItem>()

        actions.add(ActionItem(
            ActionType.CopyText,
            context.getString(R.string.action_copy_text),
            R.drawable.ic_copy_icon
        ))

        if (!isDM) {
            if (isPinned) {
                actions.add(ActionItem(
                    ActionType.UnPinMessage,
                    context.getString(R.string.action_unpin),
                    R.drawable.ic_pin_icon
                ))
            } else {
                actions.add(ActionItem(
                    ActionType.PinMessage,
                    context.getString(R.string.action_pin),
                    R.drawable.ic_pin_icon
                ))
            }
        }

        actions.add(ActionItem(
            ActionType.MarkUnRead,
            context.getString(R.string.action_mark_unread),
            R.drawable.ic_chat_mark_unread_icon
        ))

        return actions
    }

 
    private fun buildMediaActions(): List<ActionItem> {
        if (!hasMedia && !hasImage) return emptyList()
        val actions = mutableListOf<ActionItem>()

        if (hasMedia) {
            actions.add(ActionItem(
                ActionType.SaveMedia,
                context.getString(R.string.action_save_media),
                R.drawable.ic_download_icon
            ))
            actions.add(ActionItem(
                ActionType.CopyMediaLink,
                context.getString(R.string.action_copy_link),
                R.drawable.ic_link_icon
            ))
        }

        if (hasImage) {
            actions.add(ActionItem(
                ActionType.CopyImage,
                context.getString(R.string.action_copy_image),
                R.drawable.ic_image_icon
            ))
            actions.add(ActionItem(
                ActionType.ShareImage,
                context.getString(R.string.action_share_image),
                R.drawable.ic_share_box
            ))
        }

        return actions
    }


    private fun buildWarningActions(): List<ActionItem> {
        val actions = mutableListOf<ActionItem>()

        if (canDeleteMessage || isMyMessage) {
            actions.add(ActionItem(
                ActionType.DeleteMessage,
                context.getString(R.string.action_delete),
                R.drawable.ic_trash_icon,
                isWarning = true
            ))
        }

        if (!isMyMessage) {
            actions.add(ActionItem(
                ActionType.Report,
                context.getString(R.string.action_report),
                R.drawable.ic_flag_icon,
                isWarning = true
            ))
        }

        return actions
    }

  
    private fun buildActionGroup(actions: List<ActionItem>, isWarning: Boolean = false): LinearLayout {
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(secondaryColor)
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
            setBackgroundColor(secondaryColor)
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = context.getDrawable(outValue.resourceId)
            isClickable = true
            isFocusable = true
        }

        val textColor = if (item.isWarning)
            theme.getColor(ThemeColors.key_dialogTextRed)
        else
            theme.getColor(ThemeColors.key_dialogTextBlack)

        val iconColor = if (item.isWarning)
            theme.getColor(ThemeColors.key_dialogTextRed)
        else
            theme.getColor(ThemeColors.key_dialogIcon)

        // RN: MezonIconCDN renders at 20×20 (or 18×18 for warning/delete)
        val icon = ImageView(context).apply {
            try {
                setImageResource(item.iconRes)
            } catch (_: Exception) {}
            setColorFilter(iconColor)
            scaleType = ImageView.ScaleType.FIT_CENTER  // Scale down large PNGs to fit
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
            // RN: warningIcon { 32×32, bg tertiary, borderRadius 50, marginRight 10 }
            val iconBg = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    setColor(tertiaryColor)
                    cornerRadius = LayoutHelper.dp(16).toFloat()
                }
            }
            // Icon 18×18 inside 32×32 circle
            iconBg.addView(icon, LayoutHelper.createFrame(18, 18, Gravity.CENTER))
            row.addView(iconBg, LayoutHelper.createFrame(32, 32,
                Gravity.CENTER_VERTICAL or Gravity.START, 16f, 0f, 10f, 0f))
            row.addView(title, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                Gravity.CENTER_VERTICAL or Gravity.START, 58f, 0f, 16f, 0f))
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
            dismiss()
            listener.onActionSelected(item.type, message)
        }

        return row
    }

    /**
     * Thin divider line between action items within a group.
     */
    private fun buildDivider(): View {
        return View(context).apply {
            setBackgroundColor(theme.getColor(ThemeColors.key_divider))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply {
                leftMargin = LayoutHelper.dp(56)
            }
        }
    }
}
