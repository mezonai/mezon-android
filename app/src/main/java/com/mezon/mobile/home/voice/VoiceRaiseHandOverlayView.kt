package com.mezon.mobile.home.voice

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.LongSparseArray
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.avatarImgproxyUrl

class VoiceRaiseHandOverlayView(
    context: Context,
    private val themeColors: ThemeColors
) : FrameLayout(context) {

    companion object {
        private const val RAISE_HAND_TIMEOUT_MS = 10_000L
        private val ICON_SIZE = LayoutHelper.dp(28)
        private val AVATAR_SIZE = LayoutHelper.dp(28)
    }

    private data class RaiseHandItem(
        val root: LinearLayout,
        val avatarView: ImageView,
        val nameView: TextView,
        val avatarDrawable: AvatarDrawable,
        var cancellable: MezonImageLoader.Cancellable? = null,
        var removeRunnable: Runnable? = null
    )

    private val rowsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
    }
    private val items = LongSparseArray<RaiseHandItem>()

    init {
        isClickable = false
        isFocusable = false
        addView(
            rowsContainer,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = LayoutHelper.dp(68)
                rightMargin = LayoutHelper.dp(8)
            }
        )
    }

    fun showRaiseHand(senderId: Long, displayName: String, username: String, avatarUrl: String?) {
        if (senderId == 0L) return
        val existing = items[senderId]
        if (existing != null) {
            bindItem(existing, senderId, displayName, username, avatarUrl)
            scheduleRemove(senderId, existing)
            return
        }
        val item = createItem(senderId, displayName, username, avatarUrl)
        items.put(senderId, item)
        rowsContainer.addView(
            item.root,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(6)
            }
        )
        scheduleRemove(senderId, item)
    }

    fun removeRaiseHand(senderId: Long) {
        val item = items[senderId] ?: return
        item.removeRunnable?.let { removeCallbacks(it) }
        item.cancellable?.cancel()
        item.cancellable = null
        rowsContainer.removeView(item.root)
        items.remove(senderId)
    }

    fun clearAll() {
        for (i in 0 until items.size()) {
            val key = items.keyAt(i)
            val item = items[key] ?: continue
            item.removeRunnable?.let { removeCallbacks(it) }
            item.cancellable?.cancel()
            item.cancellable = null
        }
        items.clear()
        rowsContainer.removeAllViews()
    }

    private fun scheduleRemove(senderId: Long, item: RaiseHandItem) {
        item.removeRunnable?.let { removeCallbacks(it) }
        val runnable = Runnable { removeRaiseHand(senderId) }
        item.removeRunnable = runnable
        postDelayed(runnable, RAISE_HAND_TIMEOUT_MS)
    }

    private fun createItem(senderId: Long, displayName: String, username: String, avatarUrl: String?): RaiseHandItem {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(40).toFloat()
                setColor(0xCC000000.toInt())
            }
            setPadding(LayoutHelper.dp(6), LayoutHelper.dp(4), LayoutHelper.dp(8), LayoutHelper.dp(4))
            alpha = 0f
            translationY = LayoutHelper.dp(8).toFloat()
        }

        val avatarDrawable = AvatarDrawable()
        val avatarView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        root.addView(
            avatarView,
            LinearLayout.LayoutParams(AVATAR_SIZE, AVATAR_SIZE).apply {
                marginEnd = LayoutHelper.dp(6)
            }
        )

        val nameView = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(
            nameView,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = LayoutHelper.dp(4)
            }
        )

        val iconView = ImageView(context).apply {
            setImageDrawable(MezonIcon.raiseHandIcon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(0xFFEFBC39.toInt(), PorterDuff.Mode.SRC_IN)
            })
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        root.addView(iconView, LinearLayout.LayoutParams(ICON_SIZE, ICON_SIZE))

        val item = RaiseHandItem(root, avatarView, nameView, avatarDrawable)
        bindItem(item, senderId, displayName, username, avatarUrl)
        root.animate().alpha(1f).translationY(0f).setDuration(180L).start()
        return item
    }

    private fun bindItem(item: RaiseHandItem, senderId: Long, displayName: String, username: String, avatarUrl: String?) {
        item.nameView.text = displayName
        item.avatarDrawable.setInfo(senderId, username)
        item.avatarDrawable.setPhoto(null)
        item.avatarView.setImageDrawable(item.avatarDrawable)
        item.cancellable?.cancel()
        item.cancellable = null

        if (avatarUrl.isNullOrBlank()) return
        val loader = MezonImageLoader.getInstance(context)
        val url = avatarImgproxyUrl(avatarUrl, AVATAR_SIZE)
        val cached = loader.getBitmapFromMemory(url, AVATAR_SIZE, AVATAR_SIZE)
        if (cached != null) {
            item.avatarDrawable.setPhoto(cached)
            item.avatarView.setImageDrawable(item.avatarDrawable)
            return
        }
        item.avatarDrawable.setLoadingPlaceholder(true)
        item.cancellable = loader.load(
            url = url,
            reqWidth = AVATAR_SIZE,
            reqHeight = AVATAR_SIZE,
            onSuccess = { bmp ->
                item.avatarDrawable.setLoadingPlaceholder(false)
                item.avatarDrawable.setPhoto(bmp)
                item.avatarView.setImageDrawable(item.avatarDrawable)
            },
            onError = {
                item.cancellable = null
                item.avatarDrawable.setLoadingPlaceholder(false)
                item.avatarView.setImageDrawable(item.avatarDrawable)
            }
        )
    }
}
