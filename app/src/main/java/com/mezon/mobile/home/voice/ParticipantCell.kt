package com.mezon.mobile.home.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.createImgproxyUrl
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack

class ParticipantCell(
    context: Context,
    private val themeColors: ThemeColors
) : FrameLayout(context) {

    enum class ReactionBadgeType { NONE, SOUND_EFFECT, RAISE_HAND }

    companion object {
        private val AVATAR_SIZE = LayoutHelper.dp(50)
        private val CORNER_RADIUS = LayoutHelper.dp(10).toFloat()
        private val MIC_ICON_SIZE = LayoutHelper.dp(14)
        private val NAME_BOTTOM_PADDING = LayoutHelper.dp(8)
        private val NAME_H_PADDING = LayoutHelper.dp(10)
        private val OVERLAY_V_PADDING = LayoutHelper.dp(6)
        private val OVERLAY_H_PADDING = LayoutHelper.dp(8)
        private val OVERLAY_RADIUS = LayoutHelper.dp(20).toFloat()
        private val BADGE_SIZE = LayoutHelper.dp(30)
        private val BADGE_ICON_SIZE = LayoutHelper.dp(18)
        private val BADGE_MARGIN = LayoutHelper.dp(4)
        private val SHARE_ICON_SIZE = LayoutHelper.dp(14)

        private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(14f)
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.DEFAULT
        }
        private val speakingBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = LayoutHelper.dp(1).toFloat()
        }
    }

    private val avatarDrawable = AvatarDrawable()
    private val avatarView: ImageView
    private var surfaceRenderer: SurfaceViewRenderer? = null
    private var currentVideoTrack: VideoTrack? = null
    private var rendererInitialized = false

    private val borderDrawable: GradientDrawable

    private var userId: Long = 0
    private var displayName: String = ""
    private var isMuted = false
    private var isSpeaking = false
    private var videoEnabled = false
    private var nameLayout: StaticLayout? = null
    private var micDrawable: Drawable? = null
    private var micMutedDrawable: Drawable? = null
    private var shareScreenDrawable: Drawable? = null
    private var currentAvatarUrl: String? = null
    private var avatarDisposable: MezonImageLoader.Cancellable? = null
    private var attachedToWindow = false
    private var isScreenShare = false
    private val nameOverlay: LinearLayout
    private val nameOverlayIcon: ImageView
    private val nameOverlayText: TextView
    private val reactionBadge: FrameLayout
    private val reactionBadgeIcon: ImageView
    private var currentBadgeType = ReactionBadgeType.NONE

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false

        borderDrawable = GradientDrawable().apply {
            cornerRadius = CORNER_RADIUS
            setColor(themeColors.channelPanelBg)
            setStroke(LayoutHelper.dp(1), themeColors.borderDim)
        }
        background = borderDrawable

        avatarView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        addView(avatarView, LayoutParams(AVATAR_SIZE, AVATAR_SIZE, Gravity.CENTER).apply {
            bottomMargin = LayoutHelper.dp(10)
        })

        micDrawable = MezonIcon.microphoneIcon.getDrawable(context).mutate()
        micMutedDrawable = MezonIcon.microphoneSlashIcon.getDrawable(context).mutate()
        shareScreenDrawable = MezonIcon.shareScreenIcon.getDrawable(context).mutate()

        nameOverlayIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        nameOverlayText = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        nameOverlay = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pillBg = GradientDrawable().apply {
                cornerRadius = OVERLAY_RADIUS
                setColor(0x96000000.toInt())
            }
            background = pillBg
            setPadding(OVERLAY_H_PADDING, OVERLAY_V_PADDING, OVERLAY_H_PADDING, OVERLAY_V_PADDING)
            addView(nameOverlayIcon, LinearLayout.LayoutParams(MIC_ICON_SIZE, MIC_ICON_SIZE))
            val textLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            textLp.marginStart = LayoutHelper.dp(4)
            addView(nameOverlayText, textLp)
            visibility = GONE
        }
        addView(nameOverlay, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = NAME_BOTTOM_PADDING
        })

        reactionBadgeIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        reactionBadge = FrameLayout(context).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.blurple)
            }
            background = bg
            visibility = GONE
            addView(reactionBadgeIcon, LayoutParams(BADGE_ICON_SIZE, BADGE_ICON_SIZE, Gravity.CENTER))
        }
        addView(reactionBadge, LayoutParams(BADGE_SIZE, BADGE_SIZE, Gravity.TOP or Gravity.END).apply {
            topMargin = BADGE_MARGIN
            marginEnd = BADGE_MARGIN
        })
    }

    fun setParticipant(
        userId: Long,
        name: String,
        avatarUrl: String?,
        muted: Boolean,
        speaking: Boolean,
        hasVideo: Boolean,
        screenShare: Boolean = false
    ) {
        val userChanged = this.userId != userId
        this.userId = userId
        this.displayName = name
        this.isMuted = muted
        this.isSpeaking = speaking
        this.videoEnabled = hasVideo
        this.isScreenShare = screenShare

        if (userChanged) {
            avatarDrawable.setInfo(userId, name)
        }
        avatarView.visibility = if (hasVideo) GONE else VISIBLE
        nameOverlay.visibility = if (hasVideo) VISIBLE else GONE

        loadAvatar(avatarUrl)
        if (!avatarDrawable.hasPhoto()) {
            avatarView.setImageDrawable(avatarDrawable)
        }
        updateBorder()
        updateNameOverlayContent()

        if (!hasVideo) {
            detachVideoTrack()
        }

        buildNameLayout()
        invalidate()
    }

    private fun loadAvatar(url: String?) {
        if (url == currentAvatarUrl && (avatarDisposable != null || avatarDrawable.hasPhoto())) return
        currentAvatarUrl = url
        avatarDisposable?.cancel()
        avatarDisposable = null
        if (url.isNullOrEmpty()) {
            avatarDrawable.setPhoto(null)
            avatarView.setImageDrawable(avatarDrawable)
            return
        }
        val proxyUrl = createImgproxyUrl(url, AVATAR_SIZE * 2, AVATAR_SIZE * 2, "fill")
        val loader = MezonImageLoader.getInstance(context)
        val cached = loader.getBitmapFromMemory(proxyUrl, AVATAR_SIZE, AVATAR_SIZE)
        if (cached != null) {
            avatarDrawable.setPhoto(cached)
            avatarView.setImageDrawable(avatarDrawable)
            return
        }
        if (attachedToWindow) {
            startImageLoad(loader, proxyUrl)
        }
    }

    private fun startImageLoad(loader: MezonImageLoader, proxyUrl: String) {
        avatarDisposable = loader.load(
            proxyUrl, AVATAR_SIZE, AVATAR_SIZE,
            onSuccess = { bmp ->
                avatarDisposable = null
                avatarDrawable.setPhoto(bmp)
                avatarView.setImageDrawable(avatarDrawable)
                invalidate()
            },
            onError = {
                avatarDisposable = null
            }
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedToWindow = true
        val url = currentAvatarUrl
        if (!url.isNullOrEmpty() && avatarDisposable == null && !avatarDrawable.hasPhoto()) {
            val proxyUrl = createImgproxyUrl(url, AVATAR_SIZE * 2, AVATAR_SIZE * 2, "fill")
            startImageLoad(MezonImageLoader.getInstance(context), proxyUrl)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attachedToWindow = false
        avatarDisposable?.cancel()
        avatarDisposable = null
    }

    fun attachVideoTrack(room: Room, videoTrack: VideoTrack) {
        if (currentVideoTrack == videoTrack && surfaceRenderer != null) return
        detachVideoTrack()

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
        avatarView.visibility = GONE
        nameOverlay.visibility = VISIBLE
        updateNameOverlayContent()
    }

    fun detachVideoTrack() {
        currentVideoTrack?.removeRenderer(surfaceRenderer ?: return)
        currentVideoTrack = null
        surfaceRenderer?.visibility = GONE
        nameOverlay.visibility = GONE
        avatarView.visibility = VISIBLE
    }

    fun releaseRenderer() {
        detachVideoTrack()
        avatarDisposable?.cancel()
        avatarDisposable = null
        surfaceRenderer?.let {
            removeView(it)
            it.release()
        }
        surfaceRenderer = null
        rendererInitialized = false
    }

    fun updateSpeaking(speaking: Boolean) {
        if (this.isSpeaking == speaking) return
        this.isSpeaking = speaking
        updateBorder()
        invalidate()
    }

    fun updateMuted(muted: Boolean) {
        if (this.isMuted == muted) return
        this.isMuted = muted
        updateNameOverlayContent()
        buildNameLayout()
        invalidate()
    }

    fun setReactionBadge(type: ReactionBadgeType) {
        if (currentBadgeType == type) return
        currentBadgeType = type
        when (type) {
            ReactionBadgeType.NONE -> {
                reactionBadge.visibility = GONE
            }
            ReactionBadgeType.SOUND_EFFECT -> {
                val icon = MezonIcon.activityIcon.getDrawable(context).mutate()
                icon.colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                reactionBadgeIcon.setImageDrawable(icon)
                reactionBadge.visibility = VISIBLE
            }
            ReactionBadgeType.RAISE_HAND -> {
                val icon = MezonIcon.raiseHandIcon.getDrawable(context).mutate()
                icon.colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                reactionBadgeIcon.setImageDrawable(icon)
                reactionBadge.visibility = VISIBLE
            }
        }
    }

    fun clearReactionBadge() {
        setReactionBadge(ReactionBadgeType.NONE)
    }

    private fun updateNameOverlayContent() {
        val icon = if (isScreenShare) {
            shareScreenDrawable?.apply {
                colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
            }
        } else {
            (if (isMuted) micMutedDrawable else micDrawable)?.apply {
                colorFilter = PorterDuffColorFilter(themeColors.colorText, PorterDuff.Mode.SRC_IN)
            }
        }
        nameOverlayIcon.setImageDrawable(icon)
        nameOverlayText.text = displayName
    }

    private fun updateBorder() {
        if (isSpeaking) {
            borderDrawable.setStroke(LayoutHelper.dp(1), themeColors.textLink)
        } else {
            borderDrawable.setStroke(LayoutHelper.dp(1), themeColors.borderDim)
        }
    }

    private fun buildNameLayout() {
        val iconSize = if (isScreenShare) SHARE_ICON_SIZE else MIC_ICON_SIZE
        val maxWidth = measuredWidth - NAME_H_PADDING * 2 - iconSize - LayoutHelper.dp(4)
        if (maxWidth <= 0) return
        val ellipsized = TextUtils.ellipsize(displayName, namePaint, maxWidth.toFloat(), TextUtils.TruncateAt.END)
        nameLayout = StaticLayout.Builder.obtain(ellipsized, 0, ellipsized.length, namePaint, maxWidth)
            .setMaxLines(1)
            .setIncludePad(false)
            .build()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        buildNameLayout()
    }

    override fun onDraw(canvas: Canvas) {
        if (videoEnabled) return
        val nl = nameLayout ?: return
        drawAudioNameRow(canvas, nl)
    }

    private fun drawAudioNameRow(canvas: Canvas, nl: StaticLayout) {
        val totalW = MIC_ICON_SIZE + LayoutHelper.dp(4) + nl.width
        val startX = (width - totalW) / 2f
        val y = height - NAME_BOTTOM_PADDING - nl.height.toFloat()

        drawMicIcon(canvas, startX.toInt(), (y + (nl.height - MIC_ICON_SIZE) / 2f).toInt())

        canvas.save()
        canvas.translate(startX + MIC_ICON_SIZE + LayoutHelper.dp(4), y)
        nl.draw(canvas)
        canvas.restore()
    }

    private fun drawMicIcon(canvas: Canvas, x: Int, y: Int) {
        val drawable = if (isMuted) micMutedDrawable else micDrawable
        drawable?.let {
            it.colorFilter = PorterDuffColorFilter(themeColors.colorText, PorterDuff.Mode.SRC_IN)
            it.setBounds(x, y, x + MIC_ICON_SIZE, y + MIC_ICON_SIZE)
            it.draw(canvas)
        }
    }

}
