package com.mezon.mobile.home.voice

import android.content.Context
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.absoluteResourceUrl
import com.mezon.mobile.util.avatarImgproxyUrl
import com.mezon.mobile.util.createImgproxyUrl
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack

class VoiceOverlayView(
    context: Context,
    private val themeColors: ThemeColors
) : FrameLayout(context) {

    companion object {
        private val CORNER_RADIUS = LayoutHelper.dp(10).toFloat()
        private val AVATAR_SIZE = LayoutHelper.dp(50)
        private val MIC_ICON_SIZE = LayoutHelper.dp(14)
        private val NAME_TEXT_SIZE = LayoutHelper.sp(12f)
        private val BORDER_WIDTH = LayoutHelper.dp(1)
    }

    var onTapExpand: (() -> Unit)? = null

    private var isDragging = false
    private var touchX = 0f
    private var touchY = 0f
    private var origX = 0f
    private var origY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var surfaceRenderer: SurfaceViewRenderer? = null
    private var rendererInitialized = false
    private var currentVideoTrack: VideoTrack? = null

    private val avatarDrawable = AvatarDrawable()
    private val avatarView: ImageView
    private val backgroundImageView: ImageView
    private val nameRow: LinearLayout
    private val micIcon: ImageView
    private val nameText: TextView
    private var avatarDisposable: MezonImageLoader.Cancellable? = null
    private var currentAvatarUrl: String? = null

    init {
        val bgDrawable = GradientDrawable().apply {
            cornerRadius = CORNER_RADIUS
            setColor(themeColors.serverRailBg)
            setStroke(BORDER_WIDTH, themeColors.textDisabled)
        }
        background = bgDrawable
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, CORNER_RADIUS)
            }
        }
        elevation = LayoutHelper.dp(8).toFloat()
        isClickable = true

        backgroundImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = GONE
        }
        addView(backgroundImageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        avatarView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        addView(avatarView, LayoutParams(AVATAR_SIZE, AVATAR_SIZE, Gravity.CENTER).apply {
            bottomMargin = LayoutHelper.dp(16)
        })

        nameRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val rowBg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12).toFloat()
                setColor(0x99000000.toInt())
            }
            background = rowBg
            setPadding(LayoutHelper.dp(8), LayoutHelper.dp(4), LayoutHelper.dp(8), LayoutHelper.dp(4))
        }

        micIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        nameRow.addView(micIcon, LinearLayout.LayoutParams(MIC_ICON_SIZE, MIC_ICON_SIZE).apply {
            marginEnd = LayoutHelper.dp(4)
            gravity = Gravity.CENTER_VERTICAL
        })

        nameText = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        nameRow.addView(nameText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_VERTICAL })

        addView(nameRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = LayoutHelper.dp(8)
            leftMargin = LayoutHelper.dp(8)
            rightMargin = LayoutHelper.dp(8)
        })
    }

    fun setContent(
        room: Room?,
        videoTrack: VideoTrack?,
        name: String,
        username: String,
        avatarUrl: String?,
        isMuted: Boolean,
        userId: Long
    ) {
        backgroundImageView.visibility = GONE
        backgroundImageView.setImageDrawable(null)
        if (videoTrack != null && room != null) {
            showVideoContent(room, videoTrack)
            avatarView.visibility = GONE
        } else {
            detachVideo()
            avatarView.visibility = VISIBLE
            avatarDrawable.setInfo(userId, username)
            loadAvatar(avatarUrl, userId, username)
        }

        val micDrawable = if (isMuted) {
            MezonIcon.microphoneSlashIcon.getDrawable(context)
        } else {
            MezonIcon.microphoneIcon.getDrawable(context)
        }
        micDrawable.colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
        micIcon.setImageDrawable(micDrawable)
        nameText.text = name
        nameRow.visibility = VISIBLE
    }

    fun setStreamContent(channelLabel: String, avatarUrl: String?) {
        detachVideo()
        avatarView.visibility = GONE
        avatarDisposable?.cancel()
        avatarDisposable = null
        backgroundImageView.visibility = VISIBLE

        val streamIcon = MezonIcon.channelStream.getDrawable(context)?.apply {
            colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
        }
        micIcon.setImageDrawable(streamIcon)
        nameText.text = channelLabel
        nameRow.visibility = VISIBLE
        loadStreamBackground(avatarUrl)
    }

    private fun loadStreamBackground(rawUrl: String?) {
        backgroundImageView.scaleType = ImageView.ScaleType.FIT_CENTER
        val absolute = rawUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { absoluteResourceUrl(it).takeIf { url -> url.isNotEmpty() } }
        if (absolute.isNullOrEmpty()) {
            backgroundImageView.setImageDrawable(MezonIcon.channelStream.getDrawable(context))
            return
        }
        val targetWidth = width.takeIf { it > 0 } ?: VoiceOverlayManager.MINI_W
        val targetHeight = height.takeIf { it > 0 } ?: VoiceOverlayManager.MINI_H
        val proxyUrl = createImgproxyUrl(absolute, targetWidth, targetHeight, "fit")
        val loader = MezonImageLoader.getInstance(context)
        val cached = loader.getBitmapFromMemory(proxyUrl, targetWidth, targetHeight)
        if (cached != null) {
            backgroundImageView.setImageBitmap(cached)
            return
        }
        avatarDisposable = loader.load(
            proxyUrl, targetWidth, targetHeight,
            onSuccess = { bmp ->
                avatarDisposable = null
                backgroundImageView.setImageBitmap(bmp)
            },
            onError = {
                avatarDisposable = null
                backgroundImageView.setImageDrawable(MezonIcon.channelStream.getDrawable(context))
            }
        )
    }

    private fun showVideoContent(room: Room, videoTrack: VideoTrack) {
        if (currentVideoTrack == videoTrack && surfaceRenderer != null) return
        detachVideo()

        val renderer = surfaceRenderer ?: SurfaceViewRenderer(context).apply {
            setEnableHardwareScaler(true)
        }.also {
            surfaceRenderer = it
            addView(it, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        if (!rendererInitialized) {
            room.initVideoRenderer(renderer)
            rendererInitialized = true
        }

        videoTrack.addRenderer(renderer)
        currentVideoTrack = videoTrack
        renderer.visibility = VISIBLE
    }

    private fun detachVideo() {
        currentVideoTrack?.removeRenderer(surfaceRenderer ?: return)
        currentVideoTrack = null
        surfaceRenderer?.visibility = GONE
    }

    private fun loadAvatar(url: String?, userId: Long, name: String) {
        if (url == currentAvatarUrl && avatarDrawable.hasPhoto()) return
        currentAvatarUrl = url
        avatarDisposable?.cancel()
        avatarDisposable = null

        if (url.isNullOrEmpty()) {
            avatarDrawable.setLoadingPlaceholder(false)
            avatarDrawable.setPhoto(null)
            avatarView.setImageDrawable(avatarDrawable)
            return
        }

        val proxyUrl = avatarImgproxyUrl(url, AVATAR_SIZE)
        val loader = MezonImageLoader.getInstance(context)
        val cached = loader.getBitmapFromMemory(proxyUrl, AVATAR_SIZE, AVATAR_SIZE)
        if (cached != null) {
            avatarDrawable.setLoadingPlaceholder(false)
            avatarDrawable.setPhoto(cached)
            avatarView.setImageDrawable(avatarDrawable)
            return
        }

        avatarDrawable.setLoadingPlaceholder(true)
        avatarView.setImageDrawable(avatarDrawable)
        avatarDisposable = loader.load(
            proxyUrl, AVATAR_SIZE, AVATAR_SIZE,
            onSuccess = { bmp ->
                avatarDisposable = null
                avatarDrawable.setLoadingPlaceholder(false)
                avatarDrawable.setPhoto(bmp)
                avatarView.setImageDrawable(avatarDrawable)
            },
            onError = {
                avatarDisposable = null
                avatarDrawable.setLoadingPlaceholder(false)
                avatarView.setImageDrawable(avatarDrawable)
            }
        )
    }

    fun releaseRenderer() {
        detachVideo()
        avatarDisposable?.cancel()
        avatarDisposable = null
        backgroundImageView.setImageBitmap(null)
        backgroundImageView.setImageDrawable(null)
        backgroundImageView.visibility = GONE
        surfaceRenderer?.let {
            removeView(it)
            it.release()
        }
        surfaceRenderer = null
        rendererInitialized = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                touchX = event.rawX
                touchY = event.rawY
                origX = x
                origY = y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchX
                val dy = event.rawY - touchY
                if (!isDragging && (dx * dx + dy * dy > touchSlop * touchSlop)) {
                    isDragging = true
                }
                if (isDragging) {
                    x = origX + dx
                    y = origY + dy
                    constrainToParent()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    onTapExpand?.invoke()
                }
                isDragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun constrainToParent() {
        val parent = parent as? View ?: return
        x = x.coerceIn(0f, (parent.width - width).toFloat())
        y = y.coerceIn(0f, (parent.height - height).toFloat())
    }
}
