package com.mezon.mobile.home.voice

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.MemberResolver
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.chat.EmojiController
import com.mezon.mobile.home.chat.UserProfileBottomSheet
import com.mezon.mobile.home.friends.FriendController
import com.mezon.mobile.home.friends.sendProfileFriendRequest
import com.mezon.mobile.home.clans.CHANNEL_TYPE_VOICE
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.PermissionPolicy
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.ui.cells.MezonIcon
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "VoiceRoomFragment"
private const val ARG_CHANNEL_ID = "channel_id"
private const val ARG_CLAN_ID = "clan_id"
private const val ARG_CHANNEL_LABEL = "channel_label"
private const val ARG_IS_GROUP_CALL = "is_group_call"
private const val REQUEST_VOICE_PERMISSIONS = 1401
private const val REQUEST_MIC_TOGGLE = 1402
private const val REQUEST_CAMERA_TOGGLE = 1403
private const val SWITCH_CAMERA_THROTTLE_MS = 300L
private const val RAISE_HAND_COOLDOWN_MS = 10_000L
private const val RAISE_UP_PREFIX = "raising-up:"
private const val RAISE_DOWN_PREFIX = "raising-down:"
private const val SENDER_NAME_PREFIX = "sender-name:"
private const val SENDER_AVATAR_PREFIX = "sender-avatar:"
private const val VOICE_AGENT_DEFAULT_AVATAR =
    "https://imgproxy.mezon.ai/K0YUZRIosDOcz5lY6qrgC6UIXmQgWzLjZv7VJ1RAA8c/rs:fit:100:100:1/mb:2097152/plain/https://cdn.mezon.vn/0/0/1779484387973271600/1737423959329_undefined173740153013517374015248704886401586613166392.png@webp"

class VoiceRoomFragment : BaseFragment() {

    companion object {
        fun create(channelId: Long, clanId: Long, channelLabel: String, isGroupCall: Boolean = false): VoiceRoomFragment {
            return VoiceRoomFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHANNEL_ID, channelId)
                    putLong(ARG_CLAN_ID, clanId)
                    putString(ARG_CHANNEL_LABEL, channelLabel)
                    putBoolean(ARG_IS_GROUP_CALL, isGroupCall)
                }
            }
        }
    }

    private lateinit var voiceController: VoiceController
    private lateinit var userClanController: UserClanController
    private lateinit var clansController: ClansController
    private lateinit var channelController: ChannelController
    private lateinit var memberResolver: MemberResolver
    private lateinit var emojiController: EmojiController
    private lateinit var userController: UserController
    private lateinit var dialogsController: DialogsController
    private lateinit var friendController: FriendController
    private lateinit var permissionPolicy: PermissionPolicy
    private var channelId: Long = 0L
    private var clanId: Long = 0L
    private var channelLabel: String = ""
    private var isGroupCall: Boolean = false

    private var room: Room? = null
    private var roomScope: CoroutineScope? = null

    private lateinit var headerView: VoiceHeaderView
    private lateinit var controlBar: VoiceControlBar
    private lateinit var participantGrid: RecyclerListView
    private lateinit var participantAdapter: VoiceParticipantAdapter
    private var statusBarSpacer: View? = null
    private var reactionOverlay: ReactionOverlayView? = null
    private var raiseHandOverlay: VoiceRaiseHandOverlayView? = null
    private lateinit var focusedShareView: VoiceFocusedShareView
    private lateinit var morePopup: VoiceMorePopup
    private lateinit var reactionHandler: VoiceReactionHandler
    private var audioManager: VoiceAudioManager? = null
    private var participantModerationSheet: UserProfileBottomSheet? = null

    private val participants = ArrayList<ParticipantInfo>()
    private val reactionStates = HashMap<String, ParticipantCell.ReactionBadgeType>()
    private var pendingUpdateJob: kotlinx.coroutines.Job? = null
    private var raiseHandCooldownJob: kotlinx.coroutines.Job? = null
    private var isInPipMode = false
    private var isReconnecting = false
    private var isRaiseHandActive = false
    private var lastSwitchCameraElapsedMs = 0L
    private var focusedShareIdentity: String? = null
    private var wasMicPermissionRequestedBefore = false
    private var wasCameraPermissionRequestedBefore = false

    fun getChannelLabel(): String = channelLabel
    fun getChannelId(): Long = channelId
    fun getClanId(): Long = clanId
    fun getParticipantCount(): Int = participants.size
    fun getRoom(): Room? = room

    fun enterPipMode() {
        isInPipMode = true
        Log.d(TAG, "enterPipMode participants=${participants.size}")
        if (::focusedShareView.isInitialized) focusedShareView.setPipMode(true)
        if (::headerView.isInitialized) headerView.visibility = View.GONE
        if (::controlBar.isInitialized) controlBar.visibility = View.GONE
        if (::morePopup.isInitialized) morePopup.dismiss()
        statusBarSpacer?.visibility = View.GONE
        reactionOverlay?.visibility = View.GONE
        raiseHandOverlay?.visibility = View.GONE
        if (::participantAdapter.isInitialized) participantAdapter.notifyDataSetChanged()
        syncFocusedShareForPip()
        applyVoiceLayoutForMode()
    }

    fun exitPipMode() {
        isInPipMode = false
        Log.d(TAG, "exitPipMode focusedVisible=${::focusedShareView.isInitialized && focusedShareView.visibility == View.VISIBLE}")
        if (::focusedShareView.isInitialized) focusedShareView.setPipMode(false)
        if (::headerView.isInitialized) {
            val focusedVisible = ::focusedShareView.isInitialized && focusedShareView.visibility == View.VISIBLE
            headerView.visibility = if (focusedVisible) View.GONE else View.VISIBLE
        }
        if (::controlBar.isInitialized) controlBar.visibility = View.VISIBLE
        statusBarSpacer?.visibility = View.VISIBLE
        reactionOverlay?.visibility = View.VISIBLE
        raiseHandOverlay?.visibility = View.VISIBLE
        if (::participantAdapter.isInitialized) participantAdapter.notifyDataSetChanged()
        applyVoiceLayoutForMode()
    }

    data class FocusedContent(
        val videoTrack: VideoTrack?,
        val name: String,
        val username: String,
        val avatarUrl: String?,
        val isMuted: Boolean,
        val isScreenShare: Boolean,
        val userId: Long
    )

    fun getFocusedContent(): FocusedContent? {
        val r = room ?: return null
        val localId = r.localParticipant.identity?.value ?: ""

        for (p in r.remoteParticipants.values) {
            if (p.isScreenShareEnabled) {
                val track = p.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? VideoTrack
                if (track != null) {
                    val id = p.identity?.value ?: ""
                    val resolved = resolveMember(id, p.name?.toString() ?: id)
                    val avatar = effectiveAvatarUrl(resolved, p)
                    return FocusedContent(track, resolved.displayName, resolved.username, avatar, isParticipantMicMuted(p), true, id.toLongOrNull() ?: 0L)
                }
            }
        }

        if (r.localParticipant.isScreenShareEnabled) {
            val track = r.localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? VideoTrack
            if (track != null) {
                val resolved = resolveMember(localId, r.localParticipant.name?.toString() ?: "You")
                val avatar = effectiveAvatarUrl(resolved, r.localParticipant)
                return FocusedContent(track, resolved.displayName, resolved.username, avatar, isParticipantMicMuted(r.localParticipant), true, localId.toLongOrNull() ?: 0L)
            }
        }

        for (p in r.remoteParticipants.values) {
            if (p.isCameraEnabled) {
                val track = p.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
                if (track != null) {
                    val id = p.identity?.value ?: ""
                    val resolved = resolveMember(id, p.name?.toString() ?: id)
                    val avatar = effectiveAvatarUrl(resolved, p)
                    return FocusedContent(track, resolved.displayName, resolved.username, avatar, isParticipantMicMuted(p), false, id.toLongOrNull() ?: 0L)
                }
            }
        }

        if (r.localParticipant.isCameraEnabled) {
            val track = r.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
            if (track != null) {
                val resolved = resolveMember(localId, r.localParticipant.name?.toString() ?: "You")
                val avatar = effectiveAvatarUrl(resolved, r.localParticipant)
                return FocusedContent(track, resolved.displayName, resolved.username, avatar, isParticipantMicMuted(r.localParticipant), false, localId.toLongOrNull() ?: 0L)
            }
        }

        val first = participants.firstOrNull() ?: return null
        return FocusedContent(null, first.name, first.username, first.avatarUrl, first.isMuted, false, first.identity.toLongOrNull() ?: 0L)
    }

    private fun getMainActivity(): MainActivity? = getParentActivity() as? MainActivity


    private fun applyAgentHeaderUi() {
        if (!::headerView.isInitialized) return
        headerView.setAgentActive(voiceController.isAiAgentEnabled(clanId, channelId))
    }

    private fun getAgentToggleFallbackRoomNames(): List<String> {
        val result = linkedSetOf<String>()
        val infoRoom = voiceController.currentVoiceInfo?.roomName
        if (!infoRoom.isNullOrBlank()) {
            result.add(infoRoom)
        }
        val activeRoom = room
        if (activeRoom != null) {
            runCatching {
                val value = activeRoom.javaClass.getMethod("getName").invoke(activeRoom) as? String
                if (!value.isNullOrBlank()) result.add(value)
            }
            runCatching {
                val roomInfo = activeRoom.javaClass.getMethod("getRoomInfo").invoke(activeRoom)
                val sid = roomInfo?.javaClass?.getMethod("getSid")?.invoke(roomInfo) as? String
                if (!sid.isNullOrBlank()) result.add(sid)
            }
        }
        result.add(channelId.toString())
        return result.toList()
    }

    private fun minimizeToOverlay() {
        getMainActivity()?.minimizeVoiceRoom()
    }

    private fun dismissOverlay() {
        getMainActivity()?.dismissVoiceRoom()
    }

    private fun updateMiniOverlayIfNeeded() {
        val activity = getMainActivity() ?: return
        val manager = activity.voiceOverlayManager ?: return
        if (!manager.isMinimized()) return
        val focused = getFocusedContent()
        if (focused != null) {
            manager.updateMiniContent(
                room, focused.videoTrack, focused.name, focused.username,
                focused.avatarUrl, focused.isMuted, focused.userId
            )
        } else {
            manager.updateMiniContent(null, null, channelLabel, "", null, false, 0L)
        }
    }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        voiceController = entryPoint.voiceController()
        userClanController = entryPoint.userClanController()
        clansController = entryPoint.clansController()
        channelController = entryPoint.channelController()
        memberResolver = entryPoint.memberResolver()
        emojiController = entryPoint.emojiController()
        userController = entryPoint.userController()
        dialogsController = entryPoint.dialogsController()
        friendController = entryPoint.friendController()
        permissionPolicy = entryPoint.permissionPolicy()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        channelLabel = arguments?.getString(ARG_CHANNEL_LABEL) ?: ""
        isGroupCall = arguments?.getBoolean(ARG_IS_GROUP_CALL, clanId == 0L) ?: (clanId == 0L)

        observe(NotificationCenter.voiceRoomDisconnected) { _, _, args ->
            if (fragmentView == null) return@observe
            val reason = args.firstOrNull() as? String ?: "unknown"
            showDisconnectDialog(reason)
        }

        observe(NotificationCenter.voiceReactionReceived) { _, _, args ->
            if (fragmentView == null) return@observe
            if (isGroupCall) return@observe
            val reactionChannelId = args.getOrNull(1) as? Long ?: return@observe
            if (reactionChannelId != channelId) return@observe
            @Suppress("UNCHECKED_CAST")
            val emojis = args.getOrNull(0) as? List<String> ?: return@observe
            val senderId = args.getOrNull(2) as? Long ?: 0L
            val raiseUpReaction = emojis.firstOrNull { it.startsWith(RAISE_UP_PREFIX) }
            val raiseDownReaction = emojis.firstOrNull { it.startsWith(RAISE_DOWN_PREFIX) }
            val soundReaction = emojis.firstOrNull { it.startsWith("sound:") }
            val primaryReaction = emojis.firstOrNull {
                !it.startsWith(SENDER_NAME_PREFIX) &&
                    !it.startsWith(SENDER_AVATAR_PREFIX) &&
                    !it.startsWith(RAISE_UP_PREFIX) &&
                    !it.startsWith(RAISE_DOWN_PREFIX) &&
                    !it.startsWith("sound:")
            }
            val senderName = findReactionMeta(emojis, SENDER_NAME_PREFIX)
            val senderAvatar = findReactionMeta(emojis, SENDER_AVATAR_PREFIX)
            if (senderId != 0L && raiseUpReaction != null) {
                val resolved = resolveRaiseHandDisplay(senderId, senderName, senderAvatar)
                raiseHandOverlay?.showRaiseHand(senderId, resolved.displayName, resolved.username, resolved.avatarUrl)
            } else if (senderId != 0L && raiseDownReaction != null) {
                raiseHandOverlay?.removeRaiseHand(senderId)
            }
            if (soundReaction != null) {
                reactionHandler.playSoundReaction(soundReaction.removePrefix("sound:"))
            }
            if (!primaryReaction.isNullOrBlank() && soundReaction == null) {
                val overlayName = resolveReactionDisplayName(senderId, senderName)
                reactionHandler.showReactionOverlay(listOf(primaryReaction), overlayName)
            }
            if (senderId == userController.userId) {
                when {
                    raiseUpReaction != null -> setRaiseHandActive(true)
                    raiseDownReaction != null -> setRaiseHandActive(false)
                }
            }
            if (senderId != 0L) {
                reactionHandler.showPerParticipantBadge(senderId.toString(), emojis)
            }
        }


        observe(NotificationCenter.voiceAiAgentStateChanged) { _, _, args ->
            if (fragmentView == null) return@observe
            val evClan = args.getOrNull(0) as? Long ?: return@observe
            val evCh = args.getOrNull(1) as? Long ?: return@observe
            if (evCh != channelId) return@observe
            if (evClan != clanId) {
                Log.w(TAG, "voiceAiAgentStateChanged clan mismatch evClan=$evClan localClan=$clanId channelId=$channelId")
                return@observe
            }
            if (!::headerView.isInitialized) return@observe
            val evEnabled = args.getOrNull(2) as? Boolean
            Log.d(TAG, "voiceAiAgentStateChanged applyUi enabled=$evEnabled clan=$evClan ch=$evCh")
            applyAgentHeaderUi()
        }

        observe(NotificationCenter.clanMembersDidLoad) { _, _, args ->
            if (fragmentView == null) return@observe
            val loadedClanId = args.firstOrNull() as? Long ?: return@observe
            if (loadedClanId == clanId && room != null) {
                Log.d(TAG, "Clan members loaded for clanId=$clanId, refreshing participant list")
                scheduleUpdateParticipantList()
            }
        }

        observe(NotificationCenter.userClansDidLoad) { _, _, _ ->
            if (fragmentView == null) return@observe
            if (room != null) {
                Log.d(TAG, "User clans loaded, refreshing participant list")
                scheduleUpdateParticipantList()
            }
        }

        return true
    }

    override fun onResume() {
        super.onResume()
        applyAgentHeaderUi()
    }

    override fun createView(context: Context): View {
        val gradientBg = GradientDrawable(
            GradientDrawable.Orientation.RIGHT_LEFT,
            intArrayOf(themeColors.serverRailBg, themeColors.serverRailBg)
        )

        val root = FrameLayout(context).apply {
            background = gradientBg
        }

        val statusBarHeight = AndroidUtilities.statusBarHeight
        statusBarSpacer = View(context).also { spacer ->
            root.addView(spacer, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT.toFloat(), statusBarHeight / AndroidUtilities.density,
                Gravity.TOP
            ))
        }

        headerView = VoiceHeaderView(context, themeColors).apply {
            setChannelName(channelLabel)
            setAgentVisible(!isGroupCall)
            setSwitchCameraVisible(false)
            setMinimizeVisible(!isGroupCall)
            setMoreVisible(!isGroupCall)
            onMinimizeClick = { minimizeToOverlay() }
            onAgentClick = agentClick@{
                val scope = roomScope
                val ctx = context
                if (scope == null) {
                    Toast.makeText(ctx, "Connecting...", Toast.LENGTH_SHORT).show()
                    return@agentClick
                }
                val info = voiceController.currentVoiceInfo
                if (info == null || info.channelId != channelId) return@agentClick
                scope.launch {
                    val before = voiceController.isAiAgentEnabled(clanId, channelId)
                    val roomCandidates = getAgentToggleFallbackRoomNames()
                    Log.d(TAG, "agentToggle start enabledBefore=$before clan=$clanId ch=$channelId room=${info.roomName} candidates=$roomCandidates")
                    headerView.setAgentLoading(true)
                    try {
                        if (before) {
                            voiceController.disconnectAiAgent(clanId, channelId, info.roomName, roomCandidates)
                        } else {
                            voiceController.addAiAgentToChannel(clanId, channelId, info.roomName, roomCandidates)
                        }
                    } catch (e: Exception) {
                        val serverSide = e is RuntimeException &&
                            e.message?.contains("failed (5") == true
                        Log.e(
                            TAG,
                            "Agent toggle failed enabledBefore=$before clan=$clanId ch=$channelId serverSide=$serverSide",
                            e
                        )
                        val msg = if (serverSide) {
                            getString(R.string.voice_room_agent_server_error)
                        } else {
                            getString(R.string.voice_room_agent_request_failed)
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                    } finally {
                        headerView.setAgentLoading(false)
                        applyAgentHeaderUi()
                    }
                }
            }
            onSwitchCameraClick = { switchLocalCamera() }
            onAudioOutputClick = { cycleAudioOutput() }
            onMoreClick = { anchor ->
                getParentActivity()?.let { activity ->
                    morePopup.show(
                        anchor = anchor,
                        parentActivity = activity,
                        onEmojiClick = { reactionHandler.showEmojiReactionPicker() },
                        onSoundClick = { reactionHandler.showSoundReactionPicker() }
                    )
                }
            }
        }
        root.addView(headerView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, 56,
            Gravity.TOP, 0f, (statusBarHeight / AndroidUtilities.density), 0f, 0f
        ))

        participantGrid = RecyclerListView(context).apply {
            val gridManager = GridLayoutManager(context, 2)
            gridManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val total = getGridParticipants().size
                    if (total == 1) return 2
                    if (total % 2 != 0 && position == total - 1) return 2
                    return 1
                }
            }
            layoutManager = gridManager
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator = null
        }
        participantAdapter = VoiceParticipantAdapter(
            themeColors = themeColors,
            getParticipants = { getGridParticipants() },
            getRoom = { room },
            onScreenShareClick = { showFocusedShare(it) },
            onParticipantLongPress = { openParticipantModerationSheet(it) },
            itemKeyProvider = { participantKey(it) },
            isCompactMode = { isInPipMode }
        )
        morePopup = VoiceMorePopup(themeColors)
        reactionHandler = VoiceReactionHandler(
            themeColors = themeColors,
            voiceController = voiceController,
            emojiController = emojiController,
            notificationCenter = notificationCenter,
            channelId = channelId,
            getActivity = { getParentActivity() },
            getReactionOverlay = { reactionOverlay },
            getParticipantGrid = { participantGrid },
            participants = participants,
            reactionStates = reactionStates,
            getRoomScope = { roomScope },
            getLocalSenderMeta = { resolveLocalSenderMeta() }
        )
        participantGrid.adapter = participantAdapter
        val topOffset = (statusBarHeight / AndroidUtilities.density) + 56f
        root.addView(participantGrid, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
            Gravity.TOP, 10f, topOffset, 10f, 80f
        ))

        reactionOverlay = ReactionOverlayView(context)
        root.addView(reactionOverlay, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
            Gravity.TOP, 0f, topOffset, 0f, 80f
        ))
        raiseHandOverlay = VoiceRaiseHandOverlayView(context, themeColors)
        root.addView(raiseHandOverlay, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
            Gravity.TOP, 0f, topOffset, 0f, 80f
        ))

        focusedShareView = VoiceFocusedShareView(context, themeColors).apply {
            onEmojiClick = { reactionHandler.showEmojiReactionPicker() }
            onMinimizeClick = {
                if (isInPipMode) {
                    minimizeToOverlay()
                } else {
                    clearFocusedShare()
                }
            }
            setPipMode(isInPipMode)
        }
        root.addView(focusedShareView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
            Gravity.TOP, 0f, topOffset, 0f, 80f
        ))

        controlBar = VoiceControlBar(context, themeColors).apply {
            setGroupCallMode(isGroupCall)
            onCameraToggle = { enabled ->
                if (enabled) {
                    requestCameraToggle()
                } else {
                    roomScope?.launch {
                        runCatching { room?.localParticipant?.setCameraEnabled(false) }
                        headerView.setSwitchCameraVisible(false)
                        doUpdateParticipantList()
                    }
                }
            }
            onMicToggle = { enabled ->
                if (enabled) {
                    requestMicToggle()
                } else {
                    roomScope?.launch {
                        runCatching { room?.localParticipant?.setMicrophoneEnabled(false) }
                    }
                }
            }
            onChatClick = { openChatHistoryForCurrentChannel() }
            onRaiseHandClick = {
                sendRaiseHandReaction()
            }
            onEndCallClick = {
                disconnectAndLeave()
                dismissOverlay()
            }
        }
        root.addView(controlBar, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            Gravity.BOTTOM, 0f, 0f, 0f, 20f
        ))

        audioManager = VoiceAudioManager(context).also {
            it.onOutputChanged = { updateAudioOutputIcon() }
        }
        updateAudioOutputIcon()

        getParentActivity()?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        fragmentView = root

        requestPermissionsAndConnect()
        applyVoiceLayoutForMode()

        return root
    }

    private fun applyVoiceLayoutForMode() {
        if (!::participantGrid.isInitialized || !::focusedShareView.isInitialized || fragmentView == null) {
            return
        }
        val statusOffset = AndroidUtilities.statusBarHeight / AndroidUtilities.density
        val topOffset = if (isInPipMode) 0f else statusOffset + 56f
        val horizontalInset = if (isInPipMode) 2f else 10f
        val bottomInset = if (isInPipMode) 0f else 80f

        participantGrid.layoutParams = LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
            Gravity.TOP, horizontalInset, topOffset, horizontalInset, bottomInset
        )
        reactionOverlay?.layoutParams = LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
            Gravity.TOP, 0f, topOffset, 0f, bottomInset
        )
        raiseHandOverlay?.layoutParams = LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
            Gravity.TOP, 0f, topOffset, 0f, bottomInset
        )
        focusedShareView.layoutParams = LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
            Gravity.TOP,
            0f,
            if (focusedShareView.visibility == View.VISIBLE) 0f else topOffset,
            0f,
            if (focusedShareView.visibility == View.VISIBLE) 0f else bottomInset
        )
    }

    private fun requestPermissionsAndConnect() {
        val needed = mutableListOf<String>()
        val ctx = fragmentView?.context ?: return

        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (needed.isNotEmpty()) {
            if (needed.contains(Manifest.permission.RECORD_AUDIO)) {
                wasMicPermissionRequestedBefore = true
            }
            getParentActivity()?.requestPermissions(needed.toTypedArray(), REQUEST_VOICE_PERMISSIONS)
        } else {
            connectToRoom()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQUEST_VOICE_PERMISSIONS -> {
                val ctx = fragmentView?.context ?: return
                val audioGranted = ContextCompat.checkSelfPermission(
                    ctx, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (!audioGranted) {
                    showPermissionDeniedDialog(
                        titleRes = R.string.voice_room_mic_permission_title,
                        messageRes = R.string.voice_room_mic_permission_message,
                        dismissOnCancel = false
                    )
                }
                connectToRoom()
            }
            REQUEST_MIC_TOGGLE -> handleMicTogglePermissionResult()
            REQUEST_CAMERA_TOGGLE -> handleCameraTogglePermissionResult()
        }
    }

    private fun handleMicTogglePermissionResult() {
        val ctx = fragmentView?.context ?: return
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            enableMicrophone()
        } else {
            if (::controlBar.isInitialized) controlBar.setMicEnabled(false)
            showPermissionDeniedDialog(
                titleRes = R.string.voice_room_mic_permission_title,
                messageRes = R.string.voice_room_mic_permission_message,
                dismissOnCancel = false
            )
        }
    }

    private fun handleCameraTogglePermissionResult() {
        val ctx = fragmentView?.context ?: return
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            enableCamera()
        } else {
            if (::controlBar.isInitialized) controlBar.setCameraEnabled(false)
            showPermissionDeniedDialog(
                titleRes = R.string.voice_room_camera_permission_title,
                messageRes = R.string.voice_room_camera_permission_message,
                dismissOnCancel = false
            )
        }
    }

    private fun showPermissionDeniedDialog(
        titleRes: Int,
        messageRes: Int,
        dismissOnCancel: Boolean
    ) {
        val activity = getParentActivity() ?: return
        val builder = AlertDialog.Builder(activity)
            .setTitle(getString(titleRes))
            .setMessage(getString(messageRes))
            .setPositiveButton(getString(R.string.common_open_settings)) { _, _ ->
                openAppPermissionSettings()
                if (dismissOnCancel) dismissOverlay()
            }
            .setNegativeButton(getString(R.string.common_cancel)) { _, _ ->
                if (dismissOnCancel) dismissOverlay()
            }
        if (dismissOnCancel) {
            builder.setOnCancelListener { dismissOverlay() }
        }
        builder.show()
    }

    private fun requestMicToggle() {
        val ctx = fragmentView?.context ?: return
        val activity = getParentActivity() ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            enableMicrophone()
            return
        }
        if (!activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) &&
            wasMicPermissionRequestedBefore) {
            controlBar.setMicEnabled(false)
            showPermissionDeniedDialog(
                titleRes = R.string.voice_room_mic_permission_title,
                messageRes = R.string.voice_room_mic_permission_message,
                dismissOnCancel = false
            )
            return
        }
        wasMicPermissionRequestedBefore = true
        activity.requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_MIC_TOGGLE
        )
    }

    private fun requestCameraToggle() {
        val ctx = fragmentView?.context ?: return
        val activity = getParentActivity() ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            enableCamera()
            return
        }
        if (!activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) &&
            wasCameraPermissionRequestedBefore) {
            controlBar.setCameraEnabled(false)
            showPermissionDeniedDialog(
                titleRes = R.string.voice_room_camera_permission_title,
                messageRes = R.string.voice_room_camera_permission_message,
                dismissOnCancel = false
            )
            return
        }
        wasCameraPermissionRequestedBefore = true
        activity.requestPermissions(
            arrayOf(Manifest.permission.CAMERA),
            REQUEST_CAMERA_TOGGLE
        )
    }

    private fun openAppPermissionSettings() {
        val activity = getParentActivity() ?: return
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", activity.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Unable to open app permission settings", e)
        }
    }

    private fun enableMicrophone() {
        val scope = roomScope ?: return
        val participant = room?.localParticipant ?: return
        scope.launch {
            runCatching { participant.setMicrophoneEnabled(true) }
                .onFailure { e ->
                    Log.e(TAG, "setMicrophoneEnabled(true) failed", e)
                    if (::controlBar.isInitialized) controlBar.setMicEnabled(false)
                }
        }
    }

    private fun enableCamera() {
        val scope = roomScope ?: return
        val participant = room?.localParticipant ?: return
        scope.launch {
            runCatching { participant.setCameraEnabled(true) }
                .onSuccess {
                    if (::headerView.isInitialized) headerView.setSwitchCameraVisible(true)
                    doUpdateParticipantList()
                }
                .onFailure { e ->
                    Log.e(TAG, "setCameraEnabled(true) failed", e)
                    if (::controlBar.isInitialized) controlBar.setCameraEnabled(false)
                }
        }
    }

    private fun connectToRoom() {
        roomScope?.cancel()
        roomScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        roomScope?.launch {
            Log.d(TAG, "connectToRoom: channelId=$channelId clanId=$clanId")
            var token = voiceController.meetToken
            if (token.isNullOrEmpty()) {
                Log.d(TAG, "No existing token, joining voice channel...")
                token = voiceController.joinVoiceChannel(channelId, clanId, channelLabel)
                if (token.isNullOrEmpty()) {
                    Log.e(TAG, "Failed to get meet token — joinVoiceChannel returned null")
                    dismissOverlay()
                    return@launch
                }
            }
            Log.d(TAG, "Got token (${token.length} chars), connecting to ${BuildConfig.MEZON_MEET_WS_URL}")

            try {
                val ctx = fragmentView?.context ?: getParentActivity() ?: return@launch
                val voiceAudio = audioManager
                    ?: VoiceAudioManager(ctx).also { created ->
                        audioManager = created
                        created.onOutputChanged = { updateAudioOutputIcon() }
                    }
                room = LiveKit.create(
                    ctx,
                    overrides = LiveKitOverrides(
                        audioOptions = AudioOptions(audioHandler = voiceAudio.asLiveKitAudioHandler())
                    )
                )
                Log.d(TAG, "LiveKit room created, connecting...")

                launch { collectRoomEvents() }

                Log.d(TAG, "Loading clan members for clanId=$clanId (current count=${userClanController.getClanMembers(clanId).size})")
                userClanController.loadClanMembers(clanId, noCache = true)
                if (!userClanController.loaded) {
                    userClanController.loadUsers(noCache = true)
                }

                room!!.connect(BuildConfig.MEZON_MEET_WS_URL, token)
                voiceController.onRoomConnected(channelId)
                Log.d(TAG, "Connected to room, disabling local mic/camera")
                applyAgentHeaderUi()
                runCatching { room!!.localParticipant.setMicrophoneEnabled(false) }
                    .onFailure { Log.w(TAG, "setMicrophoneEnabled(false) failed (likely no permission)", it) }
                runCatching { room!!.localParticipant.setCameraEnabled(false) }
                    .onFailure { Log.w(TAG, "setCameraEnabled(false) failed (likely no permission)", it) }
                headerView.setSwitchCameraVisible(false)

                Log.d(TAG, "Local participant: identity=${room!!.localParticipant.identity?.value} name=${room!!.localParticipant.name}")
                for (p in room!!.remoteParticipants.values) {
                    Log.d(TAG, "Remote participant: identity=${p.identity?.value} name=${p.name}")
                }

                doUpdateParticipantList()
                updateAudioOutputIcon()
                Log.d(TAG, "Initial participant list: ${participants.size} participants")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to LiveKit room", e)
                voiceController.leaveVoiceChannel()
                dismissOverlay()
            }
        }
    }

    private suspend fun collectRoomEvents() {
        val r = room ?: return
        r.events.collect { event ->
            when (event) {
                is RoomEvent.Reconnecting -> {
                    isReconnecting = true
                    headerView.setReconnecting(true)
                }
                is RoomEvent.Reconnected -> {
                    isReconnecting = false
                    headerView.setReconnecting(false)
                    doUpdateParticipantList()
                    updateMiniOverlayIfNeeded()
                }
                is RoomEvent.Disconnected -> {
                    isReconnecting = false
                    headerView.setReconnecting(false)
                    val reason = resolveDisconnectedReason(event)
                    if (reason == "client_initiated") {
                        if (voiceController.isJoined || voiceController.isConnecting) {
                            voiceController.leaveVoiceChannel()
                        }
                    } else {
                        voiceController.onDisconnectedFromRoom(reason)
                    }
                }
                is RoomEvent.ParticipantConnected -> {
                    Log.d(TAG, "Event: ParticipantConnected ${event.participant.identity?.value}")
                    scheduleUpdateParticipantList()
                }
                is RoomEvent.ParticipantDisconnected -> {
                    Log.d(TAG, "Event: ParticipantDisconnected ${event.participant.identity?.value}")
                    scheduleUpdateParticipantList()
                }
                is RoomEvent.TrackSubscribed -> {
                    Log.d(TAG, "Event: TrackSubscribed source=${event.publication.source} participant=${event.participant.identity?.value}")
                    scheduleUpdateParticipantList()
                }
                is RoomEvent.TrackUnsubscribed -> {
                    Log.d(TAG, "Event: TrackUnsubscribed source=${event.publications.source} participant=${event.participant.identity?.value}")
                    scheduleUpdateParticipantList()
                }
                is RoomEvent.TrackPublished -> {
                    Log.d(TAG, "Event: TrackPublished source=${event.publication.source} participant=${event.participant.identity?.value}")
                    scheduleUpdateParticipantList()
                }
                is RoomEvent.TrackUnpublished -> {
                    Log.d(TAG, "Event: TrackUnpublished source=${event.publication.source} participant=${event.participant.identity?.value}")
                    scheduleUpdateParticipantList()
                }
                is RoomEvent.TrackMuted -> {
                    updateMuteState()
                    scheduleUpdateParticipantList()
                }
                is RoomEvent.TrackUnmuted -> {
                    updateMuteState()
                    scheduleUpdateParticipantList()
                }
                is RoomEvent.ActiveSpeakersChanged -> updateSpeakingState(event.speakers)
                else -> {}
            }
        }
    }

    private data class ResolvedMember(
        val displayName: String,
        val username: String,
        val avatarUrl: String?
    )

    private fun effectiveAvatarUrl(resolved: ResolvedMember, participant: Participant): String? {
        if (participant.kind == Participant.Kind.AGENT) return VOICE_AGENT_DEFAULT_AVATAR
        return resolved.avatarUrl
    }

    private fun resolveMember(identity: String, livekitName: String): ResolvedMember {
        val userId = identity.toLongOrNull()
        if (userId != null) {
            val members = userClanController.getClanMembers(clanId)
            val member = members.firstOrNull { it.userId == userId }
            if (member != null) {
                val name = member.clanNick.ifBlank {
                    member.displayName.ifBlank { member.username.ifBlank { livekitName } }
                }
                val avatar = member.clanAvatar.ifEmpty {
                    member.avatarUrl.ifEmpty { null }
                }
                Log.d(TAG, "resolveMember: identity=$identity -> clanMember name=$name avatar=${avatar?.take(40)}")
                return ResolvedMember(name, member.username, avatar)
            }

            val user = userClanController.getUserById(userId)
            if (user != null) {
                val name = user.displayName.ifBlank { user.username.ifBlank { livekitName } }
                val avatar = user.avatarUrl.ifEmpty { null }
                Log.d(TAG, "resolveMember: identity=$identity -> clanUser name=$name avatar=${avatar?.take(40)}")
                return ResolvedMember(name, user.username, avatar)
            }
        }

        Log.d(TAG, "resolveMember: identity=$identity -> NO MATCH, livekit name=$livekitName, " +
            "clanMembers(${clanId})=${userClanController.getClanMembers(clanId).size}, " +
            "users=${userClanController.getUserCount()}")
        return ResolvedMember(livekitName, livekitName, null)
    }

    private fun addParticipantEntries(
        target: MutableList<ParticipantInfo>,
        participant: Participant,
        identity: String,
        livekitName: String
    ) {
        val resolved = resolveMember(identity, livekitName)
        val avatarUrl = effectiveAvatarUrl(resolved, participant)
        val displayName = resolved.displayName
        val username = resolved.username
        val badge = reactionStates[identity] ?: ParticipantCell.ReactionBadgeType.NONE

        val screenPub = participant.getTrackPublication(Track.Source.SCREEN_SHARE)
        val screenTrack = screenPub?.track as? VideoTrack
        if (screenTrack != null && !isTrackPublicationMuted(screenPub)) {
            val screenAspectRatio = resolveScreenShareAspectRatio(screenPub, screenTrack)
            target.add(ParticipantInfo(
                identity = identity,
                name = "$displayName Share Screen",
                username = username,
                avatarUrl = avatarUrl,
                isMuted = isParticipantMicMuted(participant),
                isSpeaking = participant.isSpeaking,
                hasVideo = true,
                videoTrack = screenTrack,
                isScreenShare = true,
                contentAspectRatio = screenAspectRatio,
                reactionBadge = badge
            ))
        }

        val cameraPub = participant.getTrackPublication(Track.Source.CAMERA)
        val cameraTrack = if (participant.isCameraEnabled && !isTrackPublicationMuted(cameraPub)) {
            cameraPub?.track as? VideoTrack
        } else {
            null
        }
        target.add(ParticipantInfo(
            identity = identity,
            name = displayName,
            username = username,
            avatarUrl = avatarUrl,
            isMuted = isParticipantMicMuted(participant),
            isSpeaking = participant.isSpeaking,
            hasVideo = cameraTrack != null,
            videoTrack = cameraTrack,
            isScreenShare = false,
            mirrorVideo = shouldMirrorCameraTrack(participant, cameraTrack),
            reactionBadge = badge
        ))
    }

    private fun shouldMirrorCameraTrack(participant: Participant, track: VideoTrack?): Boolean {
        if (participant !== room?.localParticipant) return false
        val localTrack = track as? LocalVideoTrack ?: return false
        return localTrack.options.position == CameraPosition.FRONT
    }

    private fun resolveScreenShareAspectRatio(publication: Any?, track: Any?): Float {
        val publicationRatio = extractAspectRatio(publication)
        if (publicationRatio > 0f) return publicationRatio
        val trackRatio = extractAspectRatio(track)
        if (trackRatio > 0f) return trackRatio
        return 16f / 9f
    }

    private fun extractAspectRatio(source: Any?): Float {
        if (source == null) return 0f
        val dims = runCatching {
            source.javaClass.methods.firstOrNull {
                it.name == "getDimensions" && it.parameterCount == 0
            }?.invoke(source)
        }.getOrNull() ?: return 0f
        val width = runCatching {
            (dims.javaClass.methods.firstOrNull {
                it.name == "getWidth" && it.parameterCount == 0
            }?.invoke(dims) as? Number)?.toFloat()
        }.getOrNull() ?: 0f
        val height = runCatching {
            (dims.javaClass.methods.firstOrNull {
                it.name == "getHeight" && it.parameterCount == 0
            }?.invoke(dims) as? Number)?.toFloat()
        }.getOrNull() ?: 0f
        if (width <= 0f || height <= 0f) return 0f
        return width / height
    }

    private fun isTrackPublicationMuted(publication: Any?): Boolean {
        if (publication == null) return false
        return runCatching {
            val mutedMethod = publication.javaClass.methods.firstOrNull {
                (it.name == "isMuted" || it.name == "getMuted") && it.parameterCount == 0
            } ?: return@runCatching false
            mutedMethod.invoke(publication) as? Boolean ?: false
        }.getOrDefault(false)
    }

    private fun isParticipantMicMuted(participant: Participant): Boolean {
        if (!participant.isMicrophoneEnabled) return true
        val micPub = participant.getTrackPublication(Track.Source.MICROPHONE) ?: return false
        return isTrackPublicationMuted(micPub)
    }

    private fun scheduleUpdateParticipantList() {
        pendingUpdateJob?.cancel()
        pendingUpdateJob = roomScope?.launch {
            delay(100)
            doUpdateParticipantList()
        }
    }

    private fun participantKey(item: ParticipantInfo): String {
        return "${item.identity}_${item.isScreenShare}"
    }

    private fun getGridParticipants(): List<ParticipantInfo> {
        if (!isInPipMode) return participants
        val single = resolvePipGridParticipant() ?: return emptyList()
        return listOf(single)
    }

    private fun resolvePipGridParticipant(): ParticipantInfo? {
        return participants.firstOrNull { !it.isScreenShare && it.hasVideo } ?: participants.firstOrNull { !it.isScreenShare }
    }

    private fun updateParticipants(next: List<ParticipantInfo>) {
        if (isInPipMode) {
            participants.clear()
            participants.addAll(next)
            participantAdapter.notifyDataSetChanged()
            return
        }
        val previous = ArrayList(participants)
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = previous.size
            override fun getNewListSize(): Int = next.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return participantKey(previous[oldItemPosition]) == participantKey(next[newItemPosition])
            }
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return previous[oldItemPosition] == next[newItemPosition]
            }
        })
        participants.clear()
        participants.addAll(next)
        diff.dispatchUpdatesTo(participantAdapter)
    }

    private fun doUpdateParticipantList() {
        val r = room ?: return
        val nextParticipants = ArrayList<ParticipantInfo>()

        val local = r.localParticipant
        val localId = local.identity?.value ?: ""
        val localName = local.name?.toString()?.ifEmpty { null } ?: "You"
        addParticipantEntries(nextParticipants, local, localId, localName)

        for (p in r.remoteParticipants.values) {
            val remoteId = p.identity?.value ?: ""
            val remoteName = p.name?.toString()?.ifEmpty { null } ?: remoteId
            addParticipantEntries(nextParticipants, p, remoteId, remoteName)
        }

        val prioritized = ArrayList<ParticipantInfo>(nextParticipants.size)
        for (p in nextParticipants) {
            if (p.isScreenShare) prioritized.add(p)
        }
        for (p in nextParticipants) {
            if (!p.isScreenShare) prioritized.add(p)
        }
        updateParticipants(prioritized)
        Log.d(TAG, "doUpdateParticipantList: ${participants.size} participants")
        dismissFocusedShareIfStale()
        syncFocusedShareForPip()
        updateMiniOverlayIfNeeded()
    }

    private fun updateMuteState() {
        val r = room ?: return
        val allParticipants = HashMap<String, Participant>()
        r.localParticipant.identity?.value?.let { allParticipants[it] = r.localParticipant }
        for (p in r.remoteParticipants.values) {
            p.identity?.value?.let { allParticipants[it] = p }
        }
        var changed = false
        for (i in participants.indices) {
            val p = participants[i]
            val participant = allParticipants[p.identity] ?: continue
            val muted = isParticipantMicMuted(participant)
            if (p.isMuted != muted) {
                participants[i] = p.copy(isMuted = muted)
                changed = true
            }
        }
        if (!changed) return
        if (isInPipMode) {
            participantAdapter.notifyDataSetChanged()
            updateMiniOverlayIfNeeded()
            return
        }
        val gridParticipants = getGridParticipants()
        val count = participantGrid.childCount
        for (i in 0 until count) {
            val child = participantGrid.getChildAt(i) as? ParticipantCell ?: continue
            val pos = participantGrid.getChildAdapterPosition(child)
            if (pos in gridParticipants.indices) {
                val pi = gridParticipants[pos]
                child.updateMuted(pi.isMuted)
            }
        }
        updateMiniOverlayIfNeeded()
    }

    private fun updateSpeakingState(speakers: List<Participant>) {
        val speakerIds = speakers.map { it.identity?.value ?: "" }.toSet()
        for (i in participants.indices) {
            val p = participants[i]
            val speaking = p.identity in speakerIds
            if (p.isSpeaking != speaking) {
                participants[i] = p.copy(isSpeaking = speaking)
            }
        }
        if (isInPipMode) {
            participantAdapter.notifyDataSetChanged()
            updateMiniOverlayIfNeeded()
            return
        }
        val gridParticipants = getGridParticipants()
        val count = participantGrid.childCount
        for (i in 0 until count) {
            val child = participantGrid.getChildAt(i) as? ParticipantCell ?: continue
            val pos = participantGrid.getChildAdapterPosition(child)
            if (pos in gridParticipants.indices) {
                child.updateSpeaking(gridParticipants[pos].isSpeaking)
            }
        }
        updateMiniOverlayIfNeeded()
    }

    private fun releaseAllRenderers() {
        if (::focusedShareView.isInitialized) {
            focusedShareView.releaseRenderer()
        }
        val count = participantGrid.childCount
        for (i in 0 until count) {
            (participantGrid.getChildAt(i) as? ParticipantCell)?.releaseRenderer()
        }
    }

    private fun disconnectAndLeave() {
        releaseAllRenderers()
        val activeRoom = room
        room = null
        try {
            activeRoom?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "disconnect error", e)
        }
        voiceController.leaveVoiceChannel()
    }

    private fun showDisconnectDialog(reason: String) {
        val activity = getParentActivity() ?: return
        val message = when (reason) {
            "removed" -> "You have been removed from the voice channel"
            "duplicate" -> "You have been disconnected due to another join"
            "deleted" -> "The voice channel has been deleted"
            else -> "You have been disconnected from the voice channel"
        }
        AlertDialog.Builder(activity)
            .setTitle("Disconnected")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> dismissOverlay() }
            .show()
    }

    private fun resolveDisconnectedReason(event: RoomEvent.Disconnected): String {
        val reasonText = event.reason.toString().uppercase()
        return when {
            reasonText.contains("CLIENT_INITIATED") -> "client_initiated"
            reasonText.contains("PARTICIPANT_REMOVED") -> "removed"
            reasonText.contains("DUPLICATE_IDENTITY") -> "duplicate"
            reasonText.contains("ROOM_DELETED") -> "deleted"
            else -> "disconnected"
        }
    }

    private fun cycleAudioOutput() {
        audioManager?.cycleOutput()
    }

    private fun updateAudioOutputIcon() {
        val icon = audioManager?.currentOutputIcon() ?: MezonIcon.voiceWaveIcon
        headerView.setAudioOutputIcon(icon)
    }

    private fun switchLocalCamera() {
        val participant = room?.localParticipant ?: return
        if (!participant.isCameraEnabled) return
        val localVideo = participant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSwitchCameraElapsedMs < SWITCH_CAMERA_THROTTLE_MS) return
        lastSwitchCameraElapsedMs = now
        localVideo.switchCamera()
        scheduleUpdateParticipantList()
        roomScope?.launch {
            delay(600)
            doUpdateParticipantList()
        }
    }

    private fun showFocusedShare(participant: ParticipantInfo) {
        val r = room ?: return
        val shown = focusedShareView.showShare(participant, r)
        if (!shown) return
        focusedShareIdentity = participant.identity
        participantGrid.visibility = View.GONE
        headerView.visibility = View.GONE
        applyVoiceLayoutForMode()
    }

    private fun clearFocusedShare() {
        if (!::focusedShareView.isInitialized || !::participantGrid.isInitialized || !::headerView.isInitialized) {
            return
        }
        focusedShareView.clear()
        focusedShareIdentity = null
        participantGrid.visibility = View.VISIBLE
        headerView.visibility = if (isInPipMode) View.GONE else View.VISIBLE
        applyVoiceLayoutForMode()
    }

    private fun dismissFocusedShareIfStale() {
        if (isInPipMode) return
        if (!::focusedShareView.isInitialized) return
        if (focusedShareView.visibility != View.VISIBLE) return
        val focusedId = focusedShareIdentity ?: return
        val stillSharing = participants.any { it.identity == focusedId && it.isScreenShare && it.videoTrack != null }
        if (!stillSharing) {
            Log.d(TAG, "dismissFocusedShareIfStale: identity=$focusedId no longer sharing, clearing focus")
            clearFocusedShare()
        }
    }

    private fun syncFocusedShareForPip() {
        if (!isInPipMode || !::focusedShareView.isInitialized || !::participantGrid.isInitialized || !::headerView.isInitialized) {
            return
        }
        val shareParticipant = resolvePipShareParticipant()
        if (shareParticipant != null) {
            Log.d(
                TAG,
                "syncFocusedShareForPip focus identity=${shareParticipant.identity} name=${shareParticipant.name}"
            )
            showFocusedShare(shareParticipant)
        } else {
            Log.d(TAG, "syncFocusedShareForPip fallback_grid")
            clearFocusedShare()
        }
    }

    private fun resolvePipShareParticipant(): ParticipantInfo? {
        val availableShares = participants.filter { it.isScreenShare && it.videoTrack != null }
        if (availableShares.isEmpty()) return null
        val localIdentity = room?.localParticipant?.identity?.value
        return availableShares.firstOrNull { it.identity != localIdentity } ?: availableShares.first()
    }

    private fun openParticipantModerationSheet(participant: ParticipantInfo) {
        val context = fragmentView?.context ?: getParentActivity() ?: return
        val activity = getParentActivity() ?: return
        val identity = participant.identity
        val userId = identity.toLongOrNull() ?: 0L
        val canManageVoiceUser = canManageVoiceUser(userId)
        val liveParticipant = sequenceOf(room?.localParticipant).filterNotNull()
            .plus(room?.remoteParticipants?.values ?: emptyList())
            .firstOrNull { it.identity?.value == identity }
        val mutedNow = liveParticipant?.let { isParticipantMicMuted(it) } ?: participant.isMuted
        val showMuteAction = mutedNow.not()
        val fallbackName = participant.name.removeSuffix(" Share Screen")
        val member = if (userId != 0L) {
            memberResolver.resolveMember(userId, clanId, channelId, CHANNEL_TYPE_VOICE)
        } else {
            null
        }
        val displayNameRaw = when {
            member != null -> {
                val nick = member.clanNick.trim()
                when {
                    nick.isNotEmpty() -> nick
                    member.displayName.isNotBlank() -> member.displayName
                    else -> member.username.ifBlank { fallbackName }
                }
            }
            else -> fallbackName
        }
        val sublineRaw = when {
            member != null -> {
                val u = member.username.trim()
                when {
                    u.isNotEmpty() -> u
                    member.displayName.isNotBlank() -> member.displayName
                    else -> participant.username
                }
            }
            else -> participant.username
        }
        val displayName = displayNameRaw.trim()
            .ifBlank { sublineRaw.trim() }
            .ifBlank { identity }
        val sublineTrim = sublineRaw.trim()
        val participantSubline = if (sublineTrim.isEmpty() || sublineTrim.equals(displayName, ignoreCase = true)) {
            ""
        } else {
            sublineTrim
        }
        val avatarForUi = when {
            member != null -> {
                val ca = member.clanAvatar.trim()
                if (ca.isNotEmpty()) ca else member.avatarUrl.ifBlank { participant.avatarUrl }
            }
            else -> participant.avatarUrl
        }
        val voiceStatus = if (!isGroupCall && userId != 0L) {
            voiceController.getUserVoiceStatus(userId)
        } else {
            null
        }
        val voiceChannelLabelSync = voiceStatus?.let { vs ->
            val ch = channelController.findChannelById(vs.channelId)
            if (ch != null && ch.clanId == vs.clanId) {
                ch.channelLabel
            } else {
                channelController.getChannels(vs.clanId).firstOrNull { it.channelId == vs.channelId }?.channelLabel
            }
        }.orEmpty()

        fun presentSheet(voiceChannelLabel: String) {
            val showVoicePresence = voiceStatus != null && voiceChannelLabel.isNotBlank()
            val showHeaderActions = userId != 0L && userId != userController.userId && clanId != 0L && !isGroupCall
            val voiceChEntity = voiceStatus?.let { channelController.findChannelById(it.channelId) }
            participantModerationSheet?.dismiss()
            val targetUsername = when {
                member != null -> member.username.ifBlank { participant.username }
                else -> participant.username
            }.ifBlank { participantSubline }.ifBlank { displayName }
            val sheet = UserProfileBottomSheet(
                context = context,
                userId = userId,
                displayName = displayName,
                username = participantSubline.ifBlank { displayName },
                avatarUrl = avatarForUi,
                aboutMe = null,
                memberSince = null,
                isOwnProfile = false,
                isDM = false,
                listener = object : UserProfileBottomSheet.UserProfileListener {
                    override fun onAddFriend(userId: Long) {
                        sendProfileFriendRequest(friendController, userId, targetUsername)
                    }
                    override fun onTransferFunds(userId: Long) {
                        openProfileTransferFunds(userId, targetUsername)
                    }

                    override fun onSendMessage(userId: Long) {
                        fragmentScope.launch {
                            val dmId = withContext(Dispatchers.IO) { dialogsController.getOrCreateDm(userId) }
                            withContext(Dispatchers.Main) {
                                if (dmId != 0L) {
                                    getMainActivity()?.openChat(dmId, displayName.ifBlank { participantSubline }, 0L, CHANNEL_TYPE_DM)
                                    dismissOverlay()
                                } else {
                                    MezonToast.show(this@VoiceRoomFragment, ToastOverlay.ToastType.ERROR, getString(R.string.contact_shared_error))
                                }
                            }
                        }
                    }
                },
                voiceParticipantExtras = UserProfileBottomSheet.VoiceParticipantExtras(
                    showHeaderActions = showHeaderActions,
                    onFriendClick = {
                        sendProfileFriendRequest(friendController, userId, targetUsername)
                    },
                    canManageVoiceUser = canManageVoiceUser,
                    showMuteAction = showMuteAction,
                    onMuteAction = { showMuteParticipantConfirm(identity, displayName) },
                    onKickAction = { showKickParticipantConfirm(identity, displayName) },
                    showVoicePresence = showVoicePresence,
                    voiceChannelLabel = voiceChannelLabel,
                    onJoinVoiceChannel = joinVoiceAction@{
                        val vs = voiceStatus ?: return@joinVoiceAction
                        (activity as? MainActivity)?.showVoiceRoom(vs.channelId, vs.clanId, voiceChannelLabel)
                    },
                    voiceChannelType = voiceChEntity?.type ?: CHANNEL_TYPE_VOICE,
                    voiceChannelPrivate = voiceChEntity?.isPrivate ?: false
                )
            )
            participantModerationSheet = sheet
            sheet.setDrawNavigationBar(true)
            sheet.show()
        }

        if (voiceStatus != null && voiceChannelLabelSync.isBlank()) {
            val scope = roomScope
            if (scope != null) {
                scope.launch {
                    val fetched = channelController.findOrFetchChannelLabel(voiceStatus.channelId, voiceStatus.clanId)
                    presentSheet(fetched)
                }
                return
            }
        }
        presentSheet(voiceChannelLabelSync)
    }

    private fun canManageVoiceUser(targetUserId: Long): Boolean {
        if (isInPipMode || isGroupCall) return false
        if (clanId == 0L || channelId == 0L) return false
        if (targetUserId == 0L || targetUserId == userController.userId) return false
        return permissionPolicy.checkAnyPermission(
            listOf(PermissionPolicy.ADMINISTRATOR, PermissionPolicy.MANAGE_CHANNEL),
            channelId,
            clanId,
        )
    }

    private fun showMuteParticipantConfirm(identity: String, displayName: String) {
        val activity = getParentActivity() ?: return
        android.app.AlertDialog.Builder(activity)
            .setTitle(getString(R.string.voice_room_mute_modal_title))
            .setMessage(getString(R.string.voice_room_mute_modal_content, displayName))
            .setPositiveButton(getString(R.string.voice_room_mute_modal_action)) { _, _ ->
                executeModerationAction(identity = identity, action = VoiceModerationAction.MUTE)
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun showKickParticipantConfirm(identity: String, displayName: String) {
        val activity = getParentActivity() ?: return
        android.app.AlertDialog.Builder(activity)
            .setTitle(getString(R.string.voice_room_kick_modal_title))
            .setMessage(getString(R.string.voice_room_kick_modal_content, displayName))
            .setPositiveButton(getString(R.string.voice_room_kick_modal_action)) { _, _ ->
                executeModerationAction(identity = identity, action = VoiceModerationAction.KICK)
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun executeModerationAction(identity: String, action: VoiceModerationAction) {
        val liveKitRoomName = room?.name?.takeIf { it.isNotBlank() }
        val roomName = liveKitRoomName
            ?: voiceController.currentVoiceInfo?.roomName?.takeIf { it.isNotBlank() }
            ?: channelId.toString()
        if (liveKitRoomName == null) {
            Log.w(
                TAG,
                "executeModerationAction: LiveKit room name empty, using fallback roomName=$roomName " +
                    "(matches RN useRoomContext().name when set)"
            )
        }
        fragmentScope.launch {
            runCatching {
                if (action == VoiceModerationAction.MUTE) {
                    voiceController.muteParticipant(clanId, channelId, roomName, identity)
                } else {
                    voiceController.kickParticipant(clanId, channelId, roomName, identity)
                }
            }.onSuccess {
                Log.d(TAG, "voice moderation ok action=$action roomName=$roomName targetIdentity=$identity")
            }.onFailure { e ->
                Log.e(TAG, "voice moderation failed action=$action roomName=$roomName targetIdentity=$identity", e)
                val activity = getParentActivity() ?: return@onFailure
                val message = if (action == VoiceModerationAction.MUTE) {
                    getString(R.string.voice_room_moderation_mute_failed)
                } else {
                    getString(R.string.voice_room_moderation_kick_failed)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private enum class VoiceModerationAction { MUTE, KICK }

    override fun onFragmentDestroy() {
        pendingUpdateJob?.cancel()
        raiseHandCooldownJob?.cancel()
        raiseHandCooldownJob = null
        isRaiseHandActive = false
        isReconnecting = false
        if (::headerView.isInitialized) {
            headerView.setReconnecting(false)
        }
        getParentActivity()?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        clearFocusedShare()
        releaseAllRenderers()
        val activeRoom = room
        room = null
        try {
            activeRoom?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "disconnect on destroy error", e)
        }
        if (voiceController.isJoined || voiceController.isConnecting) {
            voiceController.leaveVoiceChannel()
        }
        reactionOverlay?.cancelAll()
        raiseHandOverlay?.clearAll()
        if (::morePopup.isInitialized) {
            morePopup.dismiss()
        }
        participantModerationSheet?.dismiss()
        participantModerationSheet = null
        audioManager?.release()
        audioManager = null
        roomScope?.cancel()
        roomScope = null
        super.onFragmentDestroy()
    }

    private fun resolveLocalSenderMeta(): VoiceReactionHandler.SenderMeta {
        val identity = userController.userId
        val localMember = if (identity != 0L && clanId != 0L) {
            userClanController.getClanMembers(clanId).firstOrNull { it.userId == identity }
        } else {
            null
        }
        val displayName = if (localMember != null) {
            localMember.clanNick.ifBlank {
                localMember.displayName.ifBlank {
                    localMember.username.ifBlank {
                        userController.displayName.ifBlank { userController.username }
                    }
                }
            }
        } else {
            userController.displayName.ifBlank { userController.username }
        }
        val avatarUrl = if (localMember != null) {
            localMember.clanAvatar.ifBlank {
                localMember.avatarUrl.ifBlank { userController.avatarUrl }
            }
        } else {
            userController.avatarUrl
        }
        return VoiceReactionHandler.SenderMeta(displayName, avatarUrl.ifBlank { null })
    }

    private fun sendRaiseHandReaction() {
        val identity = userController.userId
        if (identity == 0L) return
        val meta = resolveLocalSenderMeta()
        val displayName = meta.name
        val avatarUrl = meta.avatarUrl.orEmpty()
        if (isRaiseHandActive) {
            voiceController.sendVoiceReaction(
                listOf(
                    "$RAISE_DOWN_PREFIX$channelId",
                    "$SENDER_NAME_PREFIX$displayName",
                    "$SENDER_AVATAR_PREFIX$avatarUrl"
                ),
                channelId
            )
            setRaiseHandActive(false)
        } else {
            voiceController.sendVoiceReaction(
                listOf(
                    "$RAISE_UP_PREFIX$channelId",
                    "$SENDER_NAME_PREFIX$displayName",
                    "$SENDER_AVATAR_PREFIX$avatarUrl"
                ),
                channelId
            )
            setRaiseHandActive(true)
            raiseHandCooldownJob?.cancel()
            raiseHandCooldownJob = roomScope?.launch {
                delay(RAISE_HAND_COOLDOWN_MS)
                setRaiseHandActive(false)
            }
        }
    }

    private fun setRaiseHandActive(active: Boolean) {
        isRaiseHandActive = active
        if (::controlBar.isInitialized) {
            controlBar.setRaiseHandActive(active)
        }
        if (!active) {
            raiseHandCooldownJob?.cancel()
            raiseHandCooldownJob = null
        }
    }

    private fun openChatHistoryForCurrentChannel() {
        val activity = getMainActivity() ?: return
        activity.openChat(channelId, channelLabel, clanId, CHANNEL_TYPE_VOICE)
        minimizeToOverlay()
    }

    private fun findReactionMeta(list: List<String>, prefix: String): String {
        val match = list.firstOrNull { it.startsWith(prefix) } ?: return ""
        return match.removePrefix(prefix).trim()
    }

    private fun resolveReactionDisplayName(senderId: Long, senderName: String): String? {
        val trimmed = senderName.trim()
        if (trimmed.isNotEmpty()) return trimmed
        if (senderId == 0L) return null
        if (senderId == userController.userId) {
            return userController.displayName.ifBlank { userController.username }.ifBlank { null }
        }
        val members = userClanController.getClanMembers(clanId)
        val member = members.firstOrNull { it.userId == senderId }
        if (member != null) {
            val resolved = member.clanNick.ifBlank {
                member.displayName.ifBlank { member.username }
            }
            if (resolved.isNotBlank()) return resolved
        }
        val user = userClanController.getUserById(senderId)
        if (user != null) {
            val resolved = user.displayName.ifBlank { user.username }
            if (resolved.isNotBlank()) return resolved
        }
        return null
    }

    private data class RaiseHandDisplay(
        val displayName: String,
        val username: String,
        val avatarUrl: String?
    )

    private fun resolveRaiseHandDisplay(senderId: Long, senderName: String, senderAvatar: String): RaiseHandDisplay {
        if (senderId == userController.userId) {
            val selfName = senderName.ifBlank {
                userController.displayName.ifBlank { userController.username }
            }
            val selfAvatar = senderAvatar.ifBlank { userController.avatarUrl }
            return RaiseHandDisplay(
                displayName = selfName,
                username = userController.username,
                avatarUrl = selfAvatar.ifBlank { null }
            )
        }
        val members = userClanController.getClanMembers(clanId)
        val member = members.firstOrNull { it.userId == senderId }
        if (member != null) {
            val name = senderName.ifBlank {
                member.clanNick.ifBlank {
                    member.displayName.ifBlank { member.username }
                }
            }
            val avatar = senderAvatar.ifBlank {
                member.clanAvatar.ifBlank { member.avatarUrl }
            }
            return RaiseHandDisplay(
                displayName = name,
                username = member.username,
                avatarUrl = avatar.ifBlank { null }
            )
        }
        val user = userClanController.getUserById(senderId)
        val fallbackName = senderName.ifBlank { user?.username ?: "User" }
        return RaiseHandDisplay(
            displayName = fallbackName,
            username = user?.username.orEmpty(),
            avatarUrl = senderAvatar.ifBlank { user?.avatarUrl }.orEmpty().ifBlank { null }
        )
    }

}
