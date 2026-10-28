package com.mezon.mobile.home.stream

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.clans.CHANNEL_TYPE_STREAMING
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.voice.resolveVoiceMemberIdentity
import com.mezon.mobile.home.voice.applyVoiceButtonPressFeedback
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.absoluteResourceUrl
import com.mezon.mobile.util.avatarImgproxyUrl
import com.mezon.mobile.util.createImgproxyUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** Playback is already joined by MainActivity; this surface owns only rendering/UI. */
class StreamingRoomFragment : BaseFragment() {
    companion object {
        fun create(channelId: Long, clanId: Long, channelLabel: String, channelAvatar: String = "") = StreamingRoomFragment().apply {
            arguments = android.os.Bundle().apply {
                putLong("channel_id", channelId); putLong("clan_id", clanId)
                putString("channel_label", channelLabel); putString("channel_avatar", channelAvatar)
            }
        }
    }
    private lateinit var presence: StreamingController
    private lateinit var session: StreamingWebRtcSession
    private lateinit var channels: ChannelController
    private lateinit var users: UserClanController
    private var channelId = 0L
    private var clanId = 0L
    private var channelLabel = ""
    private var channelAvatar = ""
    private var destroyed = false
    private val roomScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    private var qualitySheet: StreamingQualitySheet? = null
    private lateinit var playback: StreamingPlaybackView
    private lateinit var viewport: StreamingVideoViewport
    private lateinit var poster: ImageView
    private lateinit var spinner: ProgressBar
    private lateinit var header: LinearLayout
    private lateinit var footer: LinearLayout
    private var footerButtons: List<View> = emptyList()
    private val footerSpacers = ArrayList<View>()
    private var compactFooter: Boolean? = null
    private lateinit var title: TextView
    private lateinit var live: TextView
    private lateinit var viewerCount: TextView
    private lateinit var membersRow: LinearLayout
    private lateinit var overflow: TextView
    private lateinit var speaker: FrameLayout
    private lateinit var speakerIcon: ImageView
    private lateinit var minimizeButton: FrameLayout
    private lateinit var qualityButton: FrameLayout
    private lateinit var chatButton: FrameLayout
    private lateinit var fullscreenButton: FrameLayout
    private var controlsVisible = true
    private var isFullscreen = false
    private var displayState: DisplayState? = null
    private data class DisplayState(
        val orientation: Int,
        val statusBarVisible: Boolean,
        val navigationBarVisible: Boolean,
        val systemBarsBehavior: Int
    )
    private var showsViewers = true
    private var requestedBanner: String? = null
    private var bannerRequest: MezonImageLoader.Cancellable? = null
    private var bannerGeneration = 0
    private val visibleIDs = ArrayList<Long>()
    private val avatars = LinkedHashMap<Long, AvatarHolder>()
    private class AvatarHolder(val wrapper: FrameLayout, val image: ImageView, val drawable: AvatarDrawable) {
        var url: String? = null
        var requested = false
        var request: MezonImageLoader.Cancellable? = null
    }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        presence = entryPoint.streamingController(); session = entryPoint.streamingWebRtcSession()
        channels = entryPoint.channelController(); users = entryPoint.userClanController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        channelId = arguments?.getLong("channel_id") ?: 0
        clanId = arguments?.getLong("clan_id") ?: 0
        channelLabel = arguments?.getString("channel_label").orEmpty()
        channelAvatar = arguments?.getString("channel_avatar").orEmpty().ifBlank { channels.getChannelAvatar(clanId, channelId) }
        presence.fetchStreamChannelMembers(clanId, noCache = true)
        users.loadClanMembers(clanId); users.loadUsers()
        for (event in listOf(NotificationCenter.voiceChannelMembersChanged, NotificationCenter.clanMembersDidLoad, NotificationCenter.userClansDidLoad)) {
            observe(event) { _, _, args ->
                if (event == NotificationCenter.userClansDidLoad || args.firstOrNull() == clanId) scheduleMembersRefresh()
            }
        }
        observe(NotificationCenter.channelsDidLoad) { _, _, args ->
            if (args.firstOrNull() == clanId) {
                channelAvatar = channels.getChannelAvatar(clanId, channelId)
                if (::poster.isInitialized) loadBackgroundIfNeeded()
            }
        }
        if (channelAvatar.isBlank()) channels.loadChannelsForClan(clanId, force = false)
        return true
    }

    override fun createView(context: Context): View {
        val root = FrameLayout(context).apply { setBackgroundColor(themeColors.serverRailBg) }
        viewport = StreamingVideoViewport(context).apply {
            onContentTap = { setControlsVisible(!controlsVisible) }
        }
        val picture = FrameLayout(context)
        viewport.addView(picture, FrameLayout.LayoutParams(-1, -1))
        root.addView(viewport, FrameLayout.LayoutParams(-1, -1))
        playback = StreamingPlaybackView(context).apply { setBackgroundColor(themeColors.serverRailBg) }
        picture.addView(playback, FrameLayout.LayoutParams(-1, -1))
        poster = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageResource(R.drawable.mezon_stream_poster) }
        picture.addView(poster, FrameLayout.LayoutParams(-1, -1))
        spinner = ProgressBar(context).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(themeColors.primary) }
        root.addView(spinner, FrameLayout.LayoutParams(LayoutHelper.dp(40), LayoutHelper.dp(40), Gravity.CENTER))
        header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(themeColors.serverRailBg)
            setPadding(LayoutHelper.dp(6), 0, LayoutHelper.dp(6), 0)
        }
        minimizeButton = headerButton(context, MezonIcon.chevronDownSmallIcon, 18) { minimizeToOverlay() }
        header.addView(minimizeButton, LinearLayout.LayoutParams(LayoutHelper.dp(44), LayoutHelper.dp(44)))
        title = label(context, channelLabel, 16f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = LayoutHelper.dp(10)
            marginEnd = LayoutHelper.dp(12)
        })
        live = label(context, "LIVE", 10f, bold = true).apply {
            setTextColor(Color.WHITE)
            includeFontPadding = false
            gravity = Gravity.CENTER; background = rounded(0xFFFF3535.toInt(), 3)
        }
        header.addView(live, LinearLayout.LayoutParams(LayoutHelper.dp(34), LayoutHelper.dp(18)))
        viewerCount = label(context, "", 12f).apply {
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setPadding(LayoutHelper.dp(12), 0, LayoutHelper.dp(4), 0)
            val eye = MezonIcon.eyeIcon.getDrawable(context).mutate().apply {
                setTint(themeColors.onSurfaceVariant)
                setBounds(0, 0, LayoutHelper.dp(14), LayoutHelper.dp(12))
            }
            setCompoundDrawablesRelative(eye, null, null, null)
            compoundDrawablePadding = LayoutHelper.dp(4)
            setOnClickListener { showsViewers = !showsViewers; refreshMembersRow() }
        }
        header.addView(viewerCount, LinearLayout.LayoutParams(-2, LayoutHelper.dp(44)))
        fullscreenButton = headerButton(context, MezonIcon.expandIcon, 16) { setFullscreen(!isFullscreen) }
        header.addView(fullscreenButton, LinearLayout.LayoutParams(LayoutHelper.dp(44), LayoutHelper.dp(44)))
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            topMargin = AndroidUtilities.statusBarHeight + LayoutHelper.dp(8)
        })
        membersRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        overflow = label(context, "", 12f, bold = true).apply {
            gravity = Gravity.CENTER; background = rounded(themeColors.tertiary, 20, themeColors.serverRailBg)
            setPadding(LayoutHelper.dp(10), 0, LayoutHelper.dp(10), 0)
            minWidth = LayoutHelper.dp(40)
        }
        root.addView(membersRow, FrameLayout.LayoutParams(-2, LayoutHelper.dp(40), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = LayoutHelper.dp(100) })
        footer = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(themeColors.serverRailBg)
        }
        speaker = button(context, MezonIcon.voiceWaveDoubleIcon) { session.toggleSpeakerOutput() }
        speakerIcon = speaker.getChildAt(0) as ImageView
        qualityButton = button(context, MezonIcon.settingIcon) {
            qualitySheet?.dismiss()
            qualitySheet = StreamingQualitySheet(context, session, themeColors).also { it.show() }
        }.apply { contentDescription = context.getString(R.string.stream_quality_title) }
        chatButton = button(context, MezonIcon.chatIcon) {
            (getParentActivity() as? MainActivity)?.openChat(channelId, channelLabel, clanId, CHANNEL_TYPE_STREAMING)
        }
        val leave = button(context, MezonIcon.callCancelIcon) {
            (getParentActivity() as? MainActivity)?.dismissStreamingRoom()
        }.apply {
            background = rounded(0xFFFF3535.toInt(), 25)
            (getChildAt(0) as ImageView).setColorFilter(Color.WHITE)
        }
        footerButtons = listOf(speaker, qualityButton, chatButton, leave)
        footerSpacers.clear()
        compactFooter = null
        for ((index, control) in footerButtons.withIndex()) {
            if (index > 0) {
                val spacer = View(context)
                footerSpacers.add(spacer)
                footer.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
            }
            footer.addView(control, LinearLayout.LayoutParams(LayoutHelper.dp(50), LayoutHelper.dp(50)))
        }
        root.addView(footer, FrameLayout.LayoutParams(-1, LayoutHelper.dp(50), Gravity.BOTTOM).apply {
            leftMargin = LayoutHelper.dp(24); rightMargin = LayoutHelper.dp(24); bottomMargin = LayoutHelper.dp(30)
        })
        updateFooterLayout(context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        updateBottomChromeBackground()
        getParentActivity()?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bindSession(); loadBackgroundIfNeeded(); refreshPlaybackUi(); refreshMembersRow()
        fragmentView = root
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            updateControlsLayout(insets)
            insets
        }
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) updateControlsLayout()
        }
        root.requestApplyInsets()
        setControlsVisible(true)
        updateFullscreenButton()
        return root
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        header.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        footer.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        membersRow.visibility = if (visible && showsViewers && membersRow.childCount > 0) View.VISIBLE else View.GONE
        viewport.contentDescription = getString(if (visible) R.string.stream_hide_controls else R.string.stream_show_controls)
    }

    private fun updateControlsLayout(insets: WindowInsetsCompat? = fragmentView?.let { ViewCompat.getRootWindowInsets(it) }) {
        if (!::header.isInitialized) return
        updateBottomChromeBackground()
        val bars = insets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val landscape = requireContext().resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        updateFooterLayout(landscape)
        val top = bars?.top ?: AndroidUtilities.statusBarHeight
        val bottom = bars?.bottom ?: 0
        (header.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = top + LayoutHelper.dp(8)
            leftMargin = bars?.left ?: 0
            rightMargin = bars?.right ?: 0
            header.layoutParams = this
        }
        val footerBottom = bottom + LayoutHelper.dp(if (landscape) 12 else 24)
        (footer.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = (bars?.left ?: 0) + LayoutHelper.dp(24)
            rightMargin = (bars?.right ?: 0) + LayoutHelper.dp(24)
            bottomMargin = footerBottom
            footer.layoutParams = this
        }
        (membersRow.layoutParams as FrameLayout.LayoutParams).apply {
            bottomMargin = footerBottom + LayoutHelper.dp(if (landscape) 62 else 70)
            membersRow.layoutParams = this
        }
    }

    private fun updateFooterLayout(compact: Boolean) {
        if (compactFooter == compact) return
        compactFooter = compact
        val buttonSize = LayoutHelper.dp(if (compact) 48 else 50)
        footerButtons.forEach { control ->
            (control.layoutParams as LinearLayout.LayoutParams).apply {
                width = buttonSize
                height = buttonSize
                control.layoutParams = this
            }
        }
        footerSpacers.forEachIndexed { index, spacer ->
            (spacer.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (compact) LayoutHelper.dp(if (index == footerSpacers.lastIndex) 24 else 14) else 0
                weight = if (compact) 0f else 1f
                spacer.layoutParams = this
            }
        }
        (footer.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (compact) FrameLayout.LayoutParams.WRAP_CONTENT else FrameLayout.LayoutParams.MATCH_PARENT
            height = buttonSize
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            footer.layoutParams = this
        }
    }

    private fun updateBottomChromeBackground() {
        val landscape = footer.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        footer.setBackgroundColor(if (isFullscreen || landscape) Color.TRANSPARENT else themeColors.serverRailBg)
    }

    private fun setFullscreen(enabled: Boolean) {
        if (isFullscreen == enabled) return
        val activity = getParentActivity() ?: return
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (enabled) {
            val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
            displayState = DisplayState(
                activity.requestedOrientation,
                insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true,
                insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true,
                controller.systemBarsBehavior
            )
            isFullscreen = true
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            isFullscreen = false
            displayState?.let { saved ->
                activity.requestedOrientation = saved.orientation
                controller.systemBarsBehavior = saved.systemBarsBehavior
                if (saved.statusBarVisible) controller.show(WindowInsetsCompat.Type.statusBars()) else controller.hide(WindowInsetsCompat.Type.statusBars())
                if (saved.navigationBarVisible) controller.show(WindowInsetsCompat.Type.navigationBars()) else controller.hide(WindowInsetsCompat.Type.navigationBars())
            }
            displayState = null
        }
        updateBottomChromeBackground()
        viewport.resetZoom()
        setControlsVisible(!enabled)
        updateFullscreenButton()
        fragmentView?.requestApplyInsets()
    }

    private fun updateFullscreenButton() {
        fullscreenButton.contentDescription = getString(if (isFullscreen) R.string.stream_exit_fullscreen else R.string.stream_enter_fullscreen)
        (fullscreenButton.getChildAt(0) as ImageView).setImageDrawable(
            (if (isFullscreen) MezonIcon.minimizeIcon else MezonIcon.expandIcon).getDrawable(requireContext())
        )
    }

    fun prepareForMinimize() {
        setFullscreen(false)
        viewport.resetZoom()
        setControlsVisible(true)
    }

    fun handleBackPressed(): Boolean {
        if (isFullscreen) {
            setFullscreen(false)
            return true
        }
        if (viewport.zoom > 1.05f) {
            viewport.resetZoom()
            return true
        }
        return false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateControlsLayout()
    }

    fun applyTheme() {
        val root = fragmentView ?: return
        root.setBackgroundColor(themeColors.serverRailBg)
        playback.setBackgroundColor(themeColors.serverRailBg)
        header.setBackgroundColor(themeColors.serverRailBg)
        updateBottomChromeBackground()
        title.setTextColor(themeColors.textStrong)
        viewerCount.setTextColor(themeColors.textStrong)
        viewerCount.compoundDrawablesRelative.forEach { it?.setTint(themeColors.onSurfaceVariant) }
        spinner.indeterminateTintList = android.content.res.ColorStateList.valueOf(themeColors.primary)
        overflow.setTextColor(themeColors.textStrong)
        overflow.background = rounded(themeColors.tertiary, 20, themeColors.serverRailBg)
        avatars.values.forEach { it.wrapper.background = rounded(themeColors.serverRailBg, 20) }
        for (control in listOf(qualityButton, chatButton)) {
            control.background = controlBackground()
            (control.getChildAt(0) as ImageView).setColorFilter(themeColors.textStrong)
        }
        for (control in listOf(minimizeButton, fullscreenButton)) {
            control.background = null
            (control.getChildAt(0) as ImageView).setColorFilter(themeColors.textStrong)
        }
        refreshAudioOutputUi()
        qualitySheet?.dismiss()
        qualitySheet = null
    }

    private fun bindSession() {
        session.onStreamingStateChanged = {
            roomScope.launch {
                if (destroyed) return@launch
                if (session.activeStreamChannelId == null) (getParentActivity() as? MainActivity)?.dismissStreamingRoom(disconnectSession = false)
                else { refreshPlaybackUi(); (getParentActivity() as? MainActivity)?.updateStreamingMiniOverlay() }
            }
        }
    }

    private fun refreshPlaybackUi() {
        if (!::playback.isInitialized) return
        playback.update(session)
        poster.visibility = if (session.hasVideoFrames) View.GONE else View.VISIBLE
        spinner.visibility = if (session.isBuffering) View.VISIBLE else View.GONE
        refreshAudioOutputUi()
    }

    private fun refreshAudioOutputUi() {
        speakerIcon.setImageDrawable(session.audioOutputIcon.getDrawable(speaker.context))
        speakerIcon.setColorFilter(if (session.isSpeakerOutput) themeColors.onPrimaryContainer else themeColors.textStrong, PorterDuff.Mode.SRC_IN)
        speaker.background = controlBackground(session.isSpeakerOutput)
        speaker.contentDescription = session.audioOutputName + ". " + speaker.context.getString(if (session.isSpeakerOutput) R.string.stream_receiver else R.string.stream_speaker)
        speaker.isSelected = session.isSpeakerOutput
    }

    private fun loadBackgroundIfNeeded() {
        if (!::poster.isInitialized) return
        val raw = channelAvatar.trim().takeUnless { it == "0" || it.contains("avatar-group.png") }.orEmpty()
        if (requestedBanner == raw) return
        requestedBanner = raw; bannerGeneration++
        val generation = bannerGeneration
        bannerRequest?.cancel(); bannerRequest = null
        poster.setImageResource(R.drawable.mezon_stream_poster)
        if (raw.isEmpty()) return
        val absolute = absoluteResourceUrl(raw)
        if (absolute.isBlank()) return
        val width = AndroidUtilities.displaySize.x; val height = AndroidUtilities.displaySize.y
        bannerRequest = MezonImageLoader.getInstance(poster.context).load(createImgproxyUrl(absolute, width, height, "fit"), width, height,
            onSuccess = { bmp -> if (!destroyed && generation == bannerGeneration) { poster.setImageBitmap(bmp); (getParentActivity() as? MainActivity)?.updateStreamingMiniOverlay() } }, onError = {})
    }

    private fun scheduleMembersRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = roomScope.launch { delay(150); refreshMembersRow() }
    }

    private fun refreshMembersRow() {
        if (!::membersRow.isInitialized || destroyed) return
        val ids = presence.getStreamMembersForChannel(channelId, clanId)
        val viewerIDs = ids.drop(1)
        val next = selectStreamingViewers(visibleIDs, viewerIDs)
        val needed = next.toMutableSet().apply { ids.firstOrNull()?.let { add(it) } }
        val memberMap = users.getClanMembers(clanId).asSequence().filter { it.userId in needed }.associateBy { it.userId }
        ids.firstOrNull()?.let { publisher ->
            val identity = resolveVoiceMemberIdentity(publisher, memberMap[publisher], users.getUserById(publisher), presence.getParticipantName(channelId, clanId, publisher).ifBlank { "User $publisher" })
            val text = identity.displayName + "\n" + channelLabel
            if (title.text.toString() != text) title.text = text
        }
        live.visibility = if (ids.isEmpty()) View.GONE else View.VISIBLE
        val count = presence.getViewerCount(channelId, clanId)
        val countText = NumberFormat.getIntegerInstance().format(count)
        if (viewerCount.text.toString() != countText) viewerCount.text = countText
        viewerCount.contentDescription = viewerCount.context.getString(R.string.stream_viewers, count)
        val removed = avatars.keys.filter { it !in next }
        removed.forEach { id -> avatars.remove(id)?.let { it.request?.cancel(); membersRow.removeView(it.wrapper) } }
        for ((index, uid) in next.withIndex()) {
            val identity = resolveVoiceMemberIdentity(uid, memberMap[uid], users.getUserById(uid), presence.getParticipantName(channelId, clanId, uid).ifBlank { "User $uid" })
            val holder = avatars.getOrPut(uid) {
                val drawable = AvatarDrawable().apply { setUserId(uid) }
                val image = ImageView(membersRow.context).apply { setImageDrawable(drawable) }
                val wrap = FrameLayout(membersRow.context).apply {
                    background = rounded(themeColors.serverRailBg, 20)
                    addView(image, FrameLayout.LayoutParams(LayoutHelper.dp(36), LayoutHelper.dp(36), Gravity.CENTER))
                }
                AvatarHolder(wrap, image, drawable)
            }
            holder.wrapper.contentDescription = identity.displayName
            if (holder.wrapper.parent == null) membersRow.addView(holder.wrapper, index, LinearLayout.LayoutParams(LayoutHelper.dp(40), LayoutHelper.dp(40)).apply { if (index > 0) marginStart = -LayoutHelper.dp(10) })
            val url = identity.avatarUrl?.let { absoluteResourceUrl(it) }?.takeIf { it.isNotBlank() }
            if (!holder.requested || holder.url != url) {
                holder.requested = true; holder.url = url
                holder.request?.cancel(); holder.drawable.setPhoto(null)
                if (url != null) holder.request = MezonImageLoader.getInstance(holder.image.context).load(avatarImgproxyUrl(url, LayoutHelper.dp(40)), LayoutHelper.dp(40), LayoutHelper.dp(40),
                    onSuccess = { bmp -> if (!destroyed && holder.url == url) { holder.drawable.setPhoto(bmp); holder.image.invalidate() } }, onError = {})
            }
            (holder.wrapper.layoutParams as LinearLayout.LayoutParams).marginStart = if (index > 0) -LayoutHelper.dp(10) else 0
            holder.wrapper.requestLayout()
        }
        visibleIDs.clear(); visibleIDs.addAll(next)
        val extra = (count - next.size).coerceAtLeast(0)
        if (extra > 0) {
            overflow.text = "+" + NumberFormat.getIntegerInstance().format(extra)
            overflow.contentDescription = overflow.context.getString(R.string.stream_more_viewers, extra)
            if (overflow.parent == null) membersRow.addView(overflow, LinearLayout.LayoutParams(-2, LayoutHelper.dp(40)).apply { marginStart = -LayoutHelper.dp(10) })
        } else membersRow.removeView(overflow)
        membersRow.visibility = if (controlsVisible && showsViewers && (next.isNotEmpty() || extra > 0)) View.VISIBLE else View.GONE
    }

    private fun headerButton(context: Context, icon: MezonIcon, iconSize: Int, action: () -> Unit) =
        button(context, icon, action).apply {
            background = null
            getChildAt(0).layoutParams = FrameLayout.LayoutParams(LayoutHelper.dp(iconSize), LayoutHelper.dp(iconSize), Gravity.CENTER)
            applyVoiceButtonPressFeedback()
        }

    private fun button(context: Context, icon: MezonIcon, action: () -> Unit) = FrameLayout(context).apply {
        background = controlBackground()
        isClickable = true; isFocusable = true
        addView(ImageView(context).apply { setImageDrawable(icon.getDrawable(context)); setColorFilter(themeColors.textStrong); scaleType = ImageView.ScaleType.CENTER_INSIDE }, FrameLayout.LayoutParams(LayoutHelper.dp(20), LayoutHelper.dp(20), Gravity.CENTER))
        setOnClickListener { action() }
    }
    private fun label(context: Context, value: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(themeColors.textStrong); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun controlBackground(selected: Boolean = false) = rounded(
        if (selected) themeColors.primaryContainer else themeColors.channelPanelBg, 25
    ).apply { setStroke(LayoutHelper.dp(1), if (selected) themeColors.primary else themeColors.borderDim) }
    private fun rounded(color: Int, radius: Int, border: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = LayoutHelper.dp(radius).toFloat(); if (border != 0) setStroke(LayoutHelper.dp(2), border)
    }
    fun minimizeToOverlay() { (getParentActivity() as? MainActivity)?.minimizeStreamingRoom() }
    fun getChannelId() = channelId
    fun getClanId() = clanId
    fun getChannelLabel() = channelLabel
    fun getChannelAvatar() = channelAvatar
    override fun onBecomeFullyVisible() { super.onBecomeFullyVisible(); session.resumePresence(); rebindSessionUi() }
    fun rebindSessionUi() { if (::playback.isInitialized) { bindSession(); refreshPlaybackUi(); refreshMembersRow(); loadBackgroundIfNeeded(); setControlsVisible(true) } }
    override fun onFragmentDestroy() {
        setFullscreen(false)
        destroyed = true
        qualitySheet?.dismiss(); bannerRequest?.cancel()
        avatars.values.forEach { it.request?.cancel() }; avatars.clear()
        session.onStreamingStateChanged = null
        playback.update(null)
        getParentActivity()?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        roomScope.cancel()
        super.onFragmentDestroy()
    }
}
