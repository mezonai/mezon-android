package com.mezon.mobile.home.stream

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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
    private lateinit var poster: ImageView
    private lateinit var spinner: ProgressBar
    private lateinit var title: TextView
    private lateinit var live: TextView
    private lateinit var viewerCount: TextView
    private lateinit var membersRow: LinearLayout
    private lateinit var overflow: TextView
    private lateinit var speaker: FrameLayout
    private lateinit var speakerIcon: ImageView
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
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        playback = StreamingPlaybackView(context)
        root.addView(playback, FrameLayout.LayoutParams(-1, -1))
        poster = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageResource(R.drawable.mezon_stream_poster) }
        root.addView(poster, FrameLayout.LayoutParams(-1, -1))
        spinner = ProgressBar(context).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }
        root.addView(spinner, FrameLayout.LayoutParams(LayoutHelper.dp(40), LayoutHelper.dp(40), Gravity.CENTER))
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(LayoutHelper.dp(12), 0, LayoutHelper.dp(12), 0) }
        header.addView(button(context, MezonIcon.chevronDownSmallIcon) { minimizeToOverlay() }, LinearLayout.LayoutParams(LayoutHelper.dp(44), LayoutHelper.dp(44)))
        title = label(context, channelLabel, 16f, bold = true).apply { maxLines = 2 }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = LayoutHelper.dp(4) })
        live = label(context, "LIVE", 10f, bold = true).apply {
            gravity = Gravity.CENTER; background = rounded(0xFFFF3535.toInt(), 3)
        }
        header.addView(live, LinearLayout.LayoutParams(LayoutHelper.dp(34), LayoutHelper.dp(18)))
        viewerCount = label(context, "", 14f).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(10), LayoutHelper.dp(12), 0, LayoutHelper.dp(12))
            val eye = MezonIcon.eyeIcon.getDrawable(context).mutate().apply {
                setTint(0xFFCCCCCC.toInt())
                setBounds(0, 0, LayoutHelper.dp(16), LayoutHelper.dp(16))
            }
            setCompoundDrawablesRelative(eye, null, null, null)
            compoundDrawablePadding = LayoutHelper.dp(6)
            setOnClickListener { showsViewers = !showsViewers; refreshMembersRow() }
        }
        header.addView(viewerCount, LinearLayout.LayoutParams(-2, -2))
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = AndroidUtilities.statusBarHeight })
        membersRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        overflow = label(context, "", 12f, bold = true).apply {
            gravity = Gravity.CENTER; background = rounded(0xFF242424.toInt(), 20, Color.BLACK)
            setPadding(LayoutHelper.dp(10), 0, LayoutHelper.dp(10), 0)
            minWidth = LayoutHelper.dp(40)
        }
        root.addView(membersRow, FrameLayout.LayoutParams(-2, LayoutHelper.dp(40), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = LayoutHelper.dp(100) })
        val footer = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        speaker = button(context, MezonIcon.voiceWaveDoubleIcon) { session.toggleSpeakerOutput() }
        speakerIcon = speaker.getChildAt(0) as ImageView
        val quality = button(context, MezonIcon.settingIcon) {
            qualitySheet?.dismiss()
            qualitySheet = StreamingQualitySheet(context, session).also { it.show() }
        }.apply { contentDescription = context.getString(R.string.stream_quality_title) }
        val chat = button(context, MezonIcon.chatIcon) {
            (getParentActivity() as? MainActivity)?.openChat(channelId, channelLabel, clanId, CHANNEL_TYPE_STREAMING)
        }
        val leave = button(context, MezonIcon.callCancelIcon) {
            (getParentActivity() as? MainActivity)?.dismissStreamingRoom()
        }.apply { background = rounded(0xFFFF3535.toInt(), 25) }
        for ((index, control) in listOf(speaker, quality, chat, leave).withIndex()) {
            if (index > 0) footer.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            footer.addView(control, LinearLayout.LayoutParams(LayoutHelper.dp(50), LayoutHelper.dp(50)))
        }
        root.addView(footer, FrameLayout.LayoutParams(-1, LayoutHelper.dp(50), Gravity.BOTTOM).apply {
            leftMargin = LayoutHelper.dp(24); rightMargin = LayoutHelper.dp(24); bottomMargin = LayoutHelper.dp(30)
        })
        getParentActivity()?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bindSession(); loadBackgroundIfNeeded(); refreshPlaybackUi(); refreshMembersRow()
        return root
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
        speakerIcon.setImageDrawable(session.audioOutputIcon.getDrawable(speaker.context))
        speakerIcon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        speaker.background = rounded(if (session.isSpeakerOutput) 0xFF333333.toInt() else 0xFF161616.toInt(), 25)
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
                    background = rounded(Color.BLACK, 20)
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
        membersRow.visibility = if (showsViewers && (next.isNotEmpty() || extra > 0)) View.VISIBLE else View.GONE
    }

    private fun button(context: Context, icon: MezonIcon, action: () -> Unit) = FrameLayout(context).apply {
        background = rounded(0xFF161616.toInt(), 25)
        isClickable = true; isFocusable = true
        addView(ImageView(context).apply { setImageDrawable(icon.getDrawable(context)); setColorFilter(Color.WHITE); scaleType = ImageView.ScaleType.CENTER_INSIDE }, FrameLayout.LayoutParams(LayoutHelper.dp(20), LayoutHelper.dp(20), Gravity.CENTER))
        setOnClickListener { action() }
    }
    private fun label(context: Context, value: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(Color.WHITE); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun rounded(color: Int, radius: Int, border: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = LayoutHelper.dp(radius).toFloat(); if (border != 0) setStroke(LayoutHelper.dp(2), border)
    }
    fun minimizeToOverlay() { (getParentActivity() as? MainActivity)?.minimizeStreamingRoom() }
    fun getChannelId() = channelId
    fun getClanId() = clanId
    fun getChannelLabel() = channelLabel
    fun getChannelAvatar() = channelAvatar
    override fun onBecomeFullyVisible() { super.onBecomeFullyVisible(); session.resumePresence(); rebindSessionUi() }
    fun rebindSessionUi() { if (::playback.isInitialized) { bindSession(); refreshPlaybackUi(); refreshMembersRow(); loadBackgroundIfNeeded() } }
    override fun onFragmentDestroy() {
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
