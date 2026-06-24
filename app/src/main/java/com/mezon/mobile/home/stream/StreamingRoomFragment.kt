package com.mezon.mobile.home.stream

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.MainActivity
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.clans.CHANNEL_TYPE_STREAMING
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.voice.VoiceChrome
import com.mezon.mobile.home.voice.VoiceStyleCircleButton
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.absoluteResourceUrl
import com.mezon.mobile.util.avatarImgproxyUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private const val ARG_CHANNEL_ID = "channel_id"
private const val ARG_CLAN_ID = "clan_id"
private const val ARG_CHANNEL_LABEL = "channel_label"
private const val ARG_CHANNEL_AVATAR = "channel_avatar"

class StreamingRoomFragment : BaseFragment() {

    companion object {
        fun create(
            channelId: Long,
            clanId: Long,
            channelLabel: String,
            channelAvatar: String = "",
        ): StreamingRoomFragment = StreamingRoomFragment().apply {
            arguments = android.os.Bundle().apply {
                putLong(ARG_CHANNEL_ID, channelId)
                putLong(ARG_CLAN_ID, clanId)
                putString(ARG_CHANNEL_LABEL, channelLabel)
                putString(ARG_CHANNEL_AVATAR, channelAvatar)
            }
        }
    }

    private lateinit var streamingController: StreamingController
    private lateinit var streamingSession: StreamingWebRtcSession
    private lateinit var channelController: ChannelController
    private lateinit var sessionManager: SessionManager
    private lateinit var userClanController: UserClanController
    private lateinit var userController: com.mezon.mobile.home.profile.UserController

    private var channelId = 0L
    private var clanId = 0L
    private var channelLabel = ""
    private var channelAvatar = ""

    private val roomScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var isMinimizing = false

    private lateinit var backgroundView: ImageView
    private lateinit var bannerView: ImageView
    private lateinit var videoRenderer: SurfaceViewRenderer
    private lateinit var membersRow: LinearLayout
    private lateinit var membersOverflow: TextView
    private val memberAvatarHolders = ArrayList<AvatarHolder>()

    private class AvatarHolder(
        val drawable: AvatarDrawable,
        val view: ImageView,
        var currentUrl: String? = null,
        var cancellable: MezonImageLoader.Cancellable? = null,
    )

    override fun onInject(entryPoint: FragmentEntryPoint) {
        streamingController = entryPoint.streamingController()
        streamingSession = entryPoint.streamingWebRtcSession()
        channelController = entryPoint.channelController()
        sessionManager = entryPoint.sessionManager()
        userClanController = entryPoint.userClanController()
        userController = entryPoint.userController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        channelLabel = arguments?.getString(ARG_CHANNEL_LABEL).orEmpty()
        channelAvatar = arguments?.getString(ARG_CHANNEL_AVATAR).orEmpty().trim()
        if (channelAvatar.isBlank()) {
            channelAvatar = channelController.getChannelAvatar(clanId, channelId)
        }

        streamingController.fetchStreamChannelMembers(clanId)
        userClanController.loadClanMembers(clanId)
        userClanController.loadUsers()

        observe(NotificationCenter.channelsDidLoad) { _, _, args ->
            if (channelAvatar.isNotBlank()) return@observe
            val updatedClanId = args.firstOrNull() as? Long ?: return@observe
            if (updatedClanId != clanId) return@observe
            val resolved = channelController.getChannelAvatar(clanId, channelId)
            if (resolved.isBlank()) return@observe
            channelAvatar = resolved
            fragmentView?.post {
                loadBackgroundIfNeeded()
                refreshPlaybackUi()
            }
        }

        observe(NotificationCenter.voiceChannelMembersChanged) { _, _, args ->
            val updatedClanId = args.firstOrNull() as? Long ?: return@observe
            if (updatedClanId == clanId) fragmentView?.post { refreshMembersRow() }
        }
        observe(NotificationCenter.clanMembersDidLoad) { _, _, args ->
            val updatedClanId = args.firstOrNull() as? Long ?: return@observe
            if (updatedClanId == clanId) fragmentView?.post { refreshMembersRow() }
        }
        observe(NotificationCenter.userClansDidLoad) { _, _, _ ->
            fragmentView?.post { refreshMembersRow() }
        }
        fetchChannelAvatarIfNeeded()
        return true
    }

    override fun createView(context: Context): View {
        val root = FrameLayout(context).apply {
            setBackgroundColor(themeColors.surface)
        }

        val statusBarHeight = AndroidUtilities.statusBarHeight

        backgroundView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        bannerView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            visibility = View.GONE
            setImageDrawable(MezonIcon.channelStream.getDrawable(context))
        }

        videoRenderer = SurfaceViewRenderer(context).apply {
            visibility = View.GONE
        }
        entryPoint().webRtcInfra().ensureFactoryReady()
        videoRenderer.init(entryPoint().webRtcInfra().eglContext, null)
        videoRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        videoRenderer.setMirror(true)
        videoRenderer.setEnableHardwareScaler(true)
        videoRenderer.setZOrderMediaOverlay(false)

        val header = buildHeader(context)
        membersRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        membersOverflow = TextView(context).apply {
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
        }
        val footer = buildFooter(context)

        root.addView(videoRenderer, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT.toFloat(), LayoutHelper.MATCH_PARENT.toFloat(), Gravity.CENTER
        ))
        root.addView(backgroundView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT.toFloat(), LayoutHelper.MATCH_PARENT.toFloat(), Gravity.CENTER
        ))
        root.addView(bannerView, LayoutHelper.createFrame(
            240f, 240f, Gravity.CENTER
        ))
        root.addView(header, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT.toFloat(), LayoutHelper.WRAP_CONTENT.toFloat(),
            Gravity.TOP, 12f, statusBarHeight / AndroidUtilities.density, 12f, 0f
        ))
        root.addView(membersRow, LayoutHelper.createFrame(
            LayoutHelper.WRAP_CONTENT.toFloat(), LayoutHelper.WRAP_CONTENT.toFloat(),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0f, 0f, 0f, 88f
        ))
        root.addView(membersOverflow, LayoutHelper.createFrame(
            LayoutHelper.WRAP_CONTENT.toFloat(), LayoutHelper.WRAP_CONTENT.toFloat(),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0f, 0f, 0f, 88f
        ))
        root.addView(footer, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT.toFloat(), LayoutHelper.WRAP_CONTENT.toFloat(),
            Gravity.BOTTOM, 24f, 0f, 24f, 24f
        ))

        bindSession()
        loadBackgroundIfNeeded()
        refreshPlaybackUi()
        refreshMembersRow()
        joinStreamIfNeeded()
        return root
    }

    private fun joinStreamIfNeeded() {
        roomScope.launch {
            sessionManager.withAutoRefresh { session ->
                val userId = userController.userId.toString()
                val username = userController.username.ifEmpty { userId }
                streamingController.applyStreamJoined(clanId, channelId, userController.userId)
                streamingSession.join(
                    clanId = clanId,
                    channelId = channelId,
                    streamId = channelId,
                    userId = userId,
                    username = username,
                    token = session.token,
                )
            }
        }
    }

    private fun imageContext(): Context? = fragmentView?.context

    private fun fetchChannelAvatarIfNeeded() {
        if (channelAvatar.isNotBlank()) return
        roomScope.launch {
            channelController.loadChannelsForClanSuspend(clanId)
            val resolved = channelController.getChannelAvatar(clanId, channelId)
            if (resolved.isBlank()) return@launch
            channelAvatar = resolved
            withContext(Dispatchers.Main.immediate) {
                loadBackgroundIfNeeded()
                refreshPlaybackUi()
                syncStreamingMiniOverlay()
            }
        }
    }

    private fun bindSession() {
        streamingSession.onStreamingStateChanged = {
            fragmentView?.post {
                refreshPlaybackUi()
                loadBackgroundIfNeeded()
            }
        }
        streamingSession.onRemoteVideoTrackChanged = { track ->
            fragmentView?.post {
                attachVideoTrack(track)
                refreshPlaybackUi()
            }
        }
        attachVideoTrack(streamingSession.remoteVideoTrack)
    }

    private fun attachVideoTrack(track: VideoTrack?) {
        streamingSession.remoteVideoTrack?.removeSink(videoRenderer)
        track?.addSink(videoRenderer)
    }

    private fun refreshPlaybackUi() {
        if (!::videoRenderer.isInitialized) return
        val hasVideo = streamingSession.isRemoteVideoStream
        videoRenderer.visibility = if (hasVideo) View.VISIBLE else View.GONE
        val showFallback = !hasVideo
        backgroundView.visibility = if (showFallback && channelAvatar.isNotBlank()) View.VISIBLE else View.GONE
        bannerView.visibility = if (showFallback && channelAvatar.isBlank()) View.VISIBLE else View.GONE
    }

    private fun syncStreamingMiniOverlay() {
        (getParentActivity() as? MainActivity)?.updateStreamingMiniOverlay()
    }

    private fun loadBackgroundIfNeeded() {
        if (streamingSession.isRemoteVideoStream) return
        val rawAvatar = channelAvatar.trim()
        if (rawAvatar.isBlank()) return
        val absolute = absoluteResourceUrl(rawAvatar)
        if (absolute.isBlank()) return
        val width = AndroidUtilities.displaySize.x
        val proxy = avatarImgproxyUrl(absolute, width)
        val ctx = imageContext() ?: return
        MezonImageLoader.getInstance(ctx).load(
            proxy, width, width,
            onSuccess = { bmp ->
                backgroundView.setImageBitmap(bmp)
                refreshPlaybackUi()
                syncStreamingMiniOverlay()
            },
            onError = {}
        )
    }

    private fun refreshMembersRow() {
        if (!::membersRow.isInitialized) return
        clearMemberAvatars()
        membersRow.removeAllViews()
        membersOverflow.visibility = View.GONE

        val memberIds = streamingController.getStreamMembersForChannel(channelId, clanId)
        if (memberIds.isEmpty()) {
            membersRow.visibility = View.GONE
            return
        }
        membersRow.visibility = View.VISIBLE
        val clanMembers = userClanController.getClanMembers(clanId)
        val memberMap = HashMap<Long, ClanMember>(clanMembers.size)
        for (m in clanMembers) memberMap[m.userId] = m

        val maxVisible = 5
        val visible = memberIds.take(maxVisible)
        val overflow = memberIds.size - visible.size
        val avatarSize = LayoutHelper.dp(40)
        val overlap = LayoutHelper.dp(10)

        for ((index, uid) in visible.withIndex()) {
            val member = memberMap[uid]
            val displayName = resolveMemberDisplayName(uid, member)
            val avatarDrawable = AvatarDrawable().apply { setInfo(uid, displayName) }
            val avatarView = ImageView(membersRow.context).apply {
                setImageDrawable(avatarDrawable)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val wrap = FrameLayout(membersRow.context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFFFFFFF.toInt())
                }
                addView(avatarView, FrameLayout.LayoutParams(LayoutHelper.dp(38), LayoutHelper.dp(38), Gravity.CENTER))
            }
            val holder = AvatarHolder(avatarDrawable, avatarView)
            memberAvatarHolders.add(holder)
            loadMemberAvatar(holder, resolveMemberAvatarUrl(uid, member), LayoutHelper.dp(38))
            val lp = LinearLayout.LayoutParams(avatarSize, avatarSize)
            if (index > 0) lp.marginStart = -overlap
            membersRow.addView(wrap, lp)
        }
        if (overflow > 0) {
            membersOverflow.visibility = View.VISIBLE
            membersOverflow.text = "+$overflow"
        }
    }

    private fun resolveMemberDisplayName(userId: Long, member: ClanMember?): String {
        member?.clanNick?.takeIf { it.isNotBlank() }?.let { return it }
        member?.displayName?.takeIf { it.isNotBlank() }?.let { return it }
        member?.username?.takeIf { it.isNotBlank() }?.let { return it }
        userClanController.getUserById(userId)?.displayName?.takeIf { it.isNotBlank() }?.let { return it }
        userClanController.getUserById(userId)?.username?.takeIf { it.isNotBlank() }?.let { return it }
        return userId.toString()
    }

    private fun resolveMemberAvatarUrl(userId: Long, member: ClanMember?): String? {
        val raw = when {
            member != null -> member.clanAvatar.takeIf { it.isNotBlank() }
                ?: member.avatarUrl.takeIf { it.isNotBlank() }
            userId == userController.userId -> userController.avatarUrl.takeIf { it.isNotBlank() }
            else -> null
        } ?: userClanController.getUserById(userId)?.avatarUrl?.takeIf { it.isNotBlank() }
        return raw?.let { absoluteResourceUrl(it).takeIf { url -> url.isNotBlank() } }
    }

    private fun loadMemberAvatar(holder: AvatarHolder, url: String?, sizePx: Int) {
        if (url == holder.currentUrl && holder.drawable.hasPhoto()) return
        holder.currentUrl = url
        holder.drawable.setPhoto(null)
        holder.cancellable?.cancel()
        holder.cancellable = null
        holder.view.setImageDrawable(holder.drawable)
        if (url.isNullOrEmpty()) return
        val proxy = avatarImgproxyUrl(url, sizePx)
        val loader = MezonImageLoader.getInstance(imageContext() ?: holder.view.context)
        val cached = loader.getBitmapFromMemory(proxy, sizePx, sizePx)
        if (cached != null) {
            holder.drawable.setPhoto(cached)
            holder.view.setImageDrawable(holder.drawable)
            return
        }
        holder.cancellable = loader.load(
            proxy, sizePx, sizePx,
            onSuccess = { bmp ->
                holder.cancellable = null
                holder.drawable.setPhoto(bmp)
                holder.view.setImageDrawable(holder.drawable)
            },
            onError = { holder.cancellable = null }
        )
    }

    private fun clearMemberAvatars() {
        for (holder in memberAvatarHolders) holder.cancellable?.cancel()
        memberAvatarHolders.clear()
    }

    private fun buildHeader(context: Context): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val minimize = circleButton(context, MezonIcon.chevronDownSmallIcon) {
            minimizeToOverlay()
        }
        val title = TextView(context).apply {
            text = channelLabel
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        }
        row.addView(minimize, LinearLayout.LayoutParams(LayoutHelper.dp(44), LayoutHelper.dp(44)))
        row.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = LayoutHelper.dp(12)
        })
        return row
    }

    private fun buildFooter(context: Context): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val chat = circleButton(context, MezonIcon.chatIcon) {
            (getParentActivity() as? MainActivity)?.openChat(
                channelId, channelLabel, clanId, CHANNEL_TYPE_STREAMING
            )
        }
        val leave = VoiceStyleCircleButton(
            context,
            MezonIcon.callCancelIcon,
            VoiceChrome.RED_STRONG,
            0,
            0xFFFFFFFF.toInt(),
            VoiceStyleCircleButton.endCallIconSizePx(context),
        ).apply {
            setOnClickListener { leaveStream() }
        }
        row.addView(chat, LinearLayout.LayoutParams(LayoutHelper.dp(50), LayoutHelper.dp(50)))
        row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(leave, LinearLayout.LayoutParams(LayoutHelper.dp(50), LayoutHelper.dp(50)))
        return row
    }

    private fun circleButton(context: Context, icon: MezonIcon, onClick: () -> Unit): FrameLayout {
        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.surfaceVariant)
            }
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                setImageDrawable(icon.getDrawable(context)?.apply {
                    colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
                })
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, FrameLayout.LayoutParams(LayoutHelper.dp(20), LayoutHelper.dp(20), Gravity.CENTER))
        }
    }

    private fun minimizeToOverlay() {
        isMinimizing = true
        streamingSession.onStreamingStateChanged = null
        streamingSession.onRemoteVideoTrackChanged = null
        (getParentActivity() as? MainActivity)?.minimizeStreamingRoom()
    }

    private fun leaveStream() {
        streamingController.applyStreamLeaved(clanId, channelId, userController.userId)
        (getParentActivity() as? MainActivity)?.dismissStreamingRoom(disconnectSession = true)
    }

    fun getChannelId() = channelId
    fun getClanId() = clanId
    fun getChannelLabel() = channelLabel
    fun getChannelAvatar() = channelAvatar

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        loadBackgroundIfNeeded()
        refreshPlaybackUi()
        refreshMembersRow()
    }

    fun rebindSessionUi() {
        if (fragmentView == null) return
        bindSession()
        loadBackgroundIfNeeded()
        refreshPlaybackUi()
        refreshMembersRow()
    }

    override fun onFragmentDestroy() {
        if (!isMinimizing) {
            streamingSession.onStreamingStateChanged = null
            streamingSession.onRemoteVideoTrackChanged = null
        }
        clearMemberAvatars()
        if (::videoRenderer.isInitialized) {
            streamingSession.remoteVideoTrack?.removeSink(videoRenderer)
            videoRenderer.release()
        }
        roomScope.cancel()
        super.onFragmentDestroy()
    }
}
