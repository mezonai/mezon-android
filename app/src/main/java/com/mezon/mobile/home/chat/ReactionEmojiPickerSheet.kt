package com.mezon.mobile.home.chat

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.emoji.EmojiView

class ReactionEmojiPickerSheet(
    context: Context,
    private val themeColors: ThemeColors,
    private val emojiController: EmojiController,
    private val notificationCenter: NotificationCenter,
    private val onEmojiPicked: (emojiId: Long, emojiShortname: String) -> Unit
) : BottomSheet(context, needFocusable = true) {

    private var emojiView: EmojiView? = null

    private val reloadObserver = object : NotificationCenter.NotificationCenterDelegate {
        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            if (id == NotificationCenter.emojisNeedReload) {
                emojiView?.onEmojisReloaded()
            }
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        val screenH = AndroidUtilities.displaySize.y
        val panelHeight = (screenH * 0.55f).toInt().coerceAtLeast(LayoutHelper.dp(350f))

        val ev = EmojiView(context, themeColors, emojiOnly = true).apply {
            init(emojiController)
            delegate = object : EmojiView.EmojiViewDelegate {
                override fun onEmojiSelected(emoji: EmojiItem) {
                    val id = emoji.id.toLongOrNull() ?: return
                    onEmojiPicked(id, emoji.shortname)
                    dismiss()
                }

                override fun onStickerSelected(sticker: StickerItem, isAudio: Boolean) {}
                override fun onGifSelected(gifUrl: String) {}
                override fun onBackspace() {}

                override fun onDismissRequested() {
                    dismiss()
                }
            }
            onOpen()
        }
        emojiView = ev

        val container = FrameLayout(context).apply {
            addView(ev, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, panelHeight
            ))
        }

        notificationCenter.addObserver(reloadObserver, NotificationCenter.emojisNeedReload)

        setCustomView(container)
        super.onCreate(savedInstanceState)
    }

    override fun dismiss() {
        notificationCenter.removeObserver(reloadObserver, NotificationCenter.emojisNeedReload)
        super.dismiss()
    }
}
