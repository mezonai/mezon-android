package com.mezon.mobile.home.voice

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import androidx.recyclerview.widget.RecyclerView
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
import com.mezon.mobile.util.createImgproxyUrl
import com.mezon.mobile.home.voice.sfu.MezonSfuSession
import com.mezon.mobile.home.voice.sfu.SfuConnectionState
import com.mezon.mobile.home.voice.sfu.SfuParticipant
import com.mezon.mobile.home.voice.sfu.SfuRole
import org.webrtc.VideoTrack
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
private const val ARG_ROLE = "join_role"
private const val REQUEST_VOICE_PERMISSIONS = 1401
private const val REQUEST_MIC_TOGGLE = 1402
private const val REQUEST_CAMERA_TOGGLE = 1403
private const val SWITCH_CAMERA_THROTTLE_MS = 300L
private const val RAISE_HAND_COOLDOWN_MS = 10_000L
private const val RAISE_UP_PREFIX = "raising-up:"
private const val RAISE_DOWN_PREFIX = "raising-down:"
private const val SENDER_NAME_PREFIX = "sender-name:"
private const val SENDER_AVATAR_PREFIX = "sender-avatar:"
private val VOICE_AGENT_DEFAULT_AVATAR = createImgproxyUrl(
    "https://cdn.mezon.vn/0/0/1779484387973271600/1737423959329_undefined173740153013517374015248704886401586613166392.png",
    100,
    100
)

class VoiceRoomFragment : BaseFragment() {

    companion object {
        fun create(channelId: Long, clanId: Long, channelLabel: String, isGroupCall: Boolean = false, role: SfuRole = SfuRole.SPEAKER): VoiceRoomFragment {
            return VoiceRoomFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHANNEL_ID, channelId)
                    putLong(ARG_CLAN_ID, clanId)
                    putString(ARG_CHANNEL_LABEL, channelLabel)
                    putBoolean(ARG_IS_GROUP_CALL, isGroupCall)
                    putString(ARG_ROLE, role.name)
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

    private lateinit var sfuSession: MezonSfuSession
    private var roomScope: CoroutineScope? = null
    private var joinRole: SfuRole = SfuRole.SPEAKER
    private var sfuRemote: List<SfuParticipant> = emptyList()
    private val memberResolveCache = HashMap<String, ResolvedMember>()
    private var isGridScrolling = false
    private var pendingGridUpdate = false
    private var localMicOn = false
    private var localCameraOn = false
    private var localCameraTrack: VideoTrack? = null
    private var localScreenTrack: VideoTrack? = null
    private var localScreenOn = false
    private var localCameraFront = true
    private var sfuConnected = false
    private var localPttActive = false
    private var speakingIds: Set<String> = emptySet()

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
    fun hasActiveSession(): Boolean = sfuConnected

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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
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
        val screen = participants.firstOrNull { it.isScreenShare && it.videoTrack != null }
        if (screen != null) {
            return FocusedContent(
                screen.videoTrack, screen.name, screen.username, screen.avatarUrl,
                screen.isMuted, true, screen.identity.toLongOrNull() ?: 0L
            )
        }
        val camera = participants.firstOrNull { !it.isScreenShare && it.videoTrack != null }
        if (camera != null) {
            return FocusedContent(
                camera.videoTrack, camera.name, camera.username, camera.avatarUrl,
                camera.isMuted, false, camera.identity.toLongOrNull() ?: 0L
            )
        }
        val first = participants.firstOrNull() ?: return null
        return FocusedContent(null, first.name, first.username, first.avatarUrl, first.isMuted, false, first.identity.toLongOrNull() ?: 0L)
    }

    private fun getMainActivity(): MainActivity? = getParentActivity() as? MainActivity


    private fun applyAgentHeaderUi() {
        if (!::headerView.isInitialized) return
        headerView.setAgentVisible(canManageVoiceChannel())
        headerView.setAgentActive(voiceController.isAiAgentEnabled(clanId, channelId))
    }

    private fun getAgentToggleFallbackRoomNames(): List<String> {
        val result = linkedSetOf<String>()
        val infoRoom = voiceController.currentVoiceInfo?.roomName
        if (!infoRoom.isNullOrBlank()) {
            result.add(infoRoom)
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
                focused.videoTrack, focused.name, focused.username,
                focused.avatarUrl, focused.isMuted, focused.userId
            )
        } else {
            manager.updateMiniContent(null, channelLabel, "", null, false, 0L)
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
        sfuSession = entryPoint.mezonSfuSession()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        channelLabel = arguments?.getString(ARG_CHANNEL_LABEL) ?: ""
        isGroupCall = arguments?.getBoolean(ARG_IS_GROUP_CALL, clanId == 0L) ?: (clanId == 0L)
        joinRole = runCatching { SfuRole.valueOf(arguments?.getString(ARG_ROLE) ?: SfuRole.SPEAKER.name) }
            .getOrDefault(SfuRole.SPEAKER)

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

        observe(NotificationCenter.clanRolesDidLoad) { _, _, args ->
            if (fragmentView == null) return@observe
            val changedClanId = args.getOrNull(0) as? Long ?: return@observe
            if (changedClanId != clanId) return@observe
            applyAgentHeaderUi()
        }

        observe(NotificationCenter.clanMembersDidLoad) { _, _, args ->
            if (fragmentView == null) return@observe
            val loadedClanId = args.firstOrNull() as? Long ?: return@observe
            if (loadedClanId == clanId && sfuConnected) {
                Log.d(TAG, "Clan members loaded for clanId=$clanId, refreshing participant list")
                memberResolveCache.clear()
                scheduleUpdateParticipantList()
            }
        }

        observe(NotificationCenter.userClansDidLoad) { _, _, _ ->
            if (fragmentView == null) return@observe
            if (sfuConnected) {
                Log.d(TAG, "User clans loaded, refreshing participant list")
                memberResolveCache.clear()
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
            clipChildren = false
            clipToPadding = false
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
            setAgentVisible(canManageVoiceChannel())
            setSwitchCameraVisible(false)
            setMinimizeVisible(!isGroupCall)
            setMoreVisible(!isGroupCall)
            onMinimizeClick = { minimizeToOverlay() }
            onAgentClick = agentClick@{
                if (!canManageVoiceChannel()) return@agentClick
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
                        showAudienceActions = joinRole == SfuRole.AUDIENCE,
                        raiseHandActive = isRaiseHandActive,
                        onRaiseHandClick = { sendRaiseHandReaction() },
                        onMessageClick = { openChatHistoryForCurrentChannel() },
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
            gridManager.isItemPrefetchEnabled = false
            gridManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    if (getGridParticipants().size == 1) return 2
                    return 1
                }
            }
            layoutManager = gridManager
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator = null
            setItemViewCacheSize(8)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        isGridScrolling = false
                        if (pendingGridUpdate) {
                            pendingGridUpdate = false
                            doUpdateParticipantList()
                        }
                    } else {
                        isGridScrolling = true
                    }
                }
            })
        }
        participantAdapter = VoiceParticipantAdapter(
            themeColors = themeColors,
            getParticipants = { getGridParticipants() },
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
                    sfuSession.setCameraEnabled(false)
                    localCameraOn = false
                    voiceController.isLocalVideoEnabled = false
                    headerView.setSwitchCameraVisible(false)
                    doUpdateParticipantList()
                }
            }
            onMicToggle = { enabled ->
                if (enabled) {
                    requestMicToggle()
                } else {
                    sfuSession.setMicEnabled(false)
                    localMicOn = false
                    doUpdateParticipantList()
                }
            }
            onMicPressStart = { sfuSession.pttPress() }
            onMicPressEnd = { sfuSession.pttRelease() }
            onChatClick = { openChatHistoryForCurrentChannel() }
            onRaiseHandClick = { sendRaiseHandReaction() }
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
            it.start()
        }
        Log.d(TAG, "createView joinRole=$joinRole argRole=${arguments?.getString(ARG_ROLE)}")
        if (joinRole == SfuRole.AUDIENCE) {
            controlBar.setPushToTalkMode(true)
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
        val pttInset = if (::controlBar.isInitialized && controlBar.isPttMode())
            controlBar.pttContentHeightDp() + 30f else 0f
        val bottomInset = if (isInPipMode) 0f else maxOf(80f, pttInset)

        if (::headerView.isInitialized) {
            headerView.layoutParams = LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, 56,
                Gravity.TOP, 0f, if (isInPipMode) 0f else statusOffset, 0f, 0f
            )
            headerView.bringToFront()
        }

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
        sfuSession.setMicEnabled(true)
        localMicOn = true
        doUpdateParticipantList()
    }

    private fun enableCamera() {
        sfuSession.setCameraEnabled(true)
        localCameraOn = true
        localCameraFront = true
        voiceController.isLocalVideoEnabled = true
        if (::headerView.isInitialized) headerView.setSwitchCameraVisible(true)
        doUpdateParticipantList()
    }

    private fun connectToRoom() {
        roomScope?.cancel()
        roomScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        roomScope?.launch {
            Log.d(TAG, "connectToRoom: channelId=$channelId clanId=$clanId role=$joinRole")
            var token = voiceController.meetToken
            if (token.isNullOrEmpty()) {
                token = voiceController.joinVoiceChannel(channelId, clanId, channelLabel)
                if (token.isNullOrEmpty()) {
                    Log.e(TAG, "Failed to get meet token")
                    dismissOverlay()
                    return@launch
                }
            }

            userClanController.loadClanMembers(clanId, noCache = true)
            if (!userClanController.loaded) {
                userClanController.loadUsers(noCache = true)
            }

            sfuSession.onConnectionState = { state -> onSfuState(state) }
            sfuSession.onParticipants = { list ->
                sfuRemote = list
                scheduleUpdateParticipantList()
            }
            sfuSession.onRoleChanged = { r ->
                Log.d(TAG, "onRoleChanged server -> $r")
                joinRole = r
                if (::controlBar.isInitialized) controlBar.setPushToTalkMode(r == SfuRole.AUDIENCE)
                applyVoiceLayoutForMode()
            }
            sfuSession.onError = { code, _ -> Log.e(TAG, "sfu error: $code") }
            sfuSession.onLocalVideoTrack = { track ->
                localCameraTrack = track
                doUpdateParticipantList()
            }
            sfuSession.onLocalScreenTrack = { track ->
                localScreenTrack = track
                localScreenOn = track != null
                doUpdateParticipantList()
            }
            sfuSession.onSpeaking = { ids ->
                speakingIds = ids
                applySpeakingToCells()
            }
            sfuSession.onPushToTalkActive = { active ->
                localPttActive = active
                doUpdateParticipantList()
            }
            sfuSession.tokenProvider = { voiceController.refreshMeetToken(channelId) }

            sfuSession.join(channelId, clanId, userController.userId.toString(), token, joinRole)
            voiceController.onRoomConnected(channelId)
            applyAgentHeaderUi()
            voiceController.isLocalVideoEnabled = false
            headerView.setSwitchCameraVisible(false)
            doUpdateParticipantList()
            updateAudioOutputIcon()
        }
    }

    private fun onSfuState(state: SfuConnectionState) {
        when (state) {
            SfuConnectionState.CONNECTED -> {
                sfuConnected = true
                headerView.setReconnecting(false)
                audioManager?.applyDefaultRouting()
                doUpdateParticipantList()
                updateMiniOverlayIfNeeded()
            }
            SfuConnectionState.DISCONNECTED -> {
                headerView.setReconnecting(true)
            }
            SfuConnectionState.FAILED -> {
                if (voiceController.isJoined || voiceController.isConnecting) {
                    voiceController.onDisconnectedFromRoom("disconnected")
                }
            }
            else -> {}
        }
    }

    private data class ResolvedMember(
        val displayName: String,
        val username: String,
        val avatarUrl: String?
    )

    private fun resolveMember(identity: String, fallbackName: String): ResolvedMember {
        memberResolveCache[identity]?.let { return it }
        val userId = identity.toLongOrNull()
        if (userId != null) {
            val members = userClanController.getClanMembers(clanId)
            val member = members.firstOrNull { it.userId == userId }
            if (member != null) {
                val name = member.clanNick.ifBlank {
                    member.displayName.ifBlank { member.username.ifBlank { fallbackName } }
                }
                val avatar = member.clanAvatar.ifEmpty {
                    member.avatarUrl.ifEmpty { null }
                }
                return ResolvedMember(name, member.username, avatar).also { memberResolveCache[identity] = it }
            }

            val user = userClanController.getUserById(userId)
            if (user != null) {
                val name = user.displayName.ifBlank { user.username.ifBlank { fallbackName } }
                val avatar = user.avatarUrl.ifEmpty { null }
                return ResolvedMember(name, user.username, avatar).also { memberResolveCache[identity] = it }
            }
        }

        return ResolvedMember(fallbackName, fallbackName, null)
    }

    private fun addLocalEntries(target: MutableList<ParticipantInfo>) {
        val identity = userController.userId.toString()
        val resolved = resolveMember(identity, userController.displayName.ifBlank { userController.username }.ifBlank { "You" })
        val badge = reactionStates[identity] ?: ParticipantCell.ReactionBadgeType.NONE
        if (localScreenOn && localScreenTrack != null) {
            target.add(ParticipantInfo(
                identity = identity,
                name = "${resolved.displayName} Share Screen",
                username = resolved.username,
                avatarUrl = resolved.avatarUrl,
                isMuted = !(localMicOn || localPttActive),
                isSpeaking = identity in speakingIds,
                hasVideo = true,
                videoTrack = localScreenTrack,
                isScreenShare = true,
                role = joinRole,
                reactionBadge = badge
            ))
        }
        val cameraTrack = if (localCameraOn) localCameraTrack else null
        target.add(ParticipantInfo(
            identity = identity,
            name = resolved.displayName,
            username = resolved.username,
            avatarUrl = resolved.avatarUrl,
            isMuted = !(localMicOn || localPttActive),
            isSpeaking = identity in speakingIds,
            hasVideo = cameraTrack != null,
            videoTrack = cameraTrack,
            isScreenShare = false,
            mirrorVideo = false,
            role = joinRole,
            reactionBadge = badge
        ))
    }

    private fun addRemoteEntries(target: MutableList<ParticipantInfo>, participant: SfuParticipant) {
        val identity = participant.userId ?: participant.id
        val resolved = resolveMember(identity, identity)
        val badge = reactionStates[identity] ?: ParticipantCell.ReactionBadgeType.NONE
        if (participant.screen != null && participant.screenActive) {
            target.add(ParticipantInfo(
                identity = identity,
                name = "${resolved.displayName} Share Screen",
                username = resolved.username,
                avatarUrl = resolved.avatarUrl,
                isMuted = participant.muted,
                isSpeaking = identity in speakingIds,
                hasVideo = true,
                videoTrack = participant.screen,
                isScreenShare = true,
                role = participant.role,
                reactionBadge = badge
            ))
        }
        val remoteCamera = if (participant.cameraActive) participant.video else null
        target.add(ParticipantInfo(
            identity = identity,
            name = resolved.displayName,
            username = resolved.username,
            avatarUrl = resolved.avatarUrl,
            isMuted = participant.muted,
            isSpeaking = identity in speakingIds,
            hasVideo = remoteCamera != null,
            videoTrack = remoteCamera,
            isScreenShare = false,
            role = participant.role,
            reactionBadge = badge
        ))
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

    private fun applySpeakingToCells() {
        if (!::participantGrid.isInitialized) return
        if (isInPipMode) {
            if (::participantAdapter.isInitialized) participantAdapter.notifyDataSetChanged()
            return
        }
        val gridParticipants = getGridParticipants()
        val count = participantGrid.childCount
        for (i in 0 until count) {
            val child = participantGrid.getChildAt(i) as? ParticipantCell ?: continue
            val pos = participantGrid.getChildAdapterPosition(child)
            if (pos in gridParticipants.indices) {
                child.updateSpeaking(gridParticipants[pos].identity in speakingIds)
            }
        }
    }

    private fun doUpdateParticipantList() {
        if (fragmentView == null) return
        if (isGridScrolling && !isInPipMode) {
            pendingGridUpdate = true
            return
        }
        val nextParticipants = ArrayList<ParticipantInfo>()
        addLocalEntries(nextParticipants)
        for (p in sfuRemote) {
            addRemoteEntries(nextParticipants, p)
        }

        val prioritized = ArrayList<ParticipantInfo>(nextParticipants.size)
        for (p in nextParticipants) {
            if (p.isScreenShare) prioritized.add(p)
        }
        for (p in nextParticipants) {
            if (!p.isScreenShare) prioritized.add(p)
        }
        updateParticipants(prioritized)
        dismissFocusedShareIfStale()
        refreshFocusedShareTrack()
        syncFocusedShareForPip()
        updateMiniOverlayIfNeeded()
    }

    private fun refreshFocusedShareTrack() {
        if (isInPipMode) return
        if (!::focusedShareView.isInitialized) return
        if (focusedShareView.visibility != View.VISIBLE) return
        val focusedId = focusedShareIdentity ?: return
        val participant = participants.firstOrNull {
            it.identity == focusedId && it.isScreenShare && it.videoTrack != null
        } ?: return
        focusedShareView.refreshTrack(participant)
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
        if (::sfuSession.isInitialized) sfuSession.leave()
        sfuConnected = false
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

    private fun cycleAudioOutput() {
        audioManager?.cycleOutput()
    }

    private fun updateAudioOutputIcon() {
        val icon = audioManager?.currentOutputIcon() ?: MezonIcon.voiceWaveIcon
        headerView.setAudioOutputIcon(icon)
    }

    private fun switchLocalCamera() {
        if (!localCameraOn) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSwitchCameraElapsedMs < SWITCH_CAMERA_THROTTLE_MS) return
        lastSwitchCameraElapsedMs = now
        localCameraFront = !localCameraFront
        sfuSession.switchCamera()
        doUpdateParticipantList()
    }

    private fun showFocusedShare(participant: ParticipantInfo) {
        val shown = focusedShareView.showShare(participant)
        if (!shown) return
        focusedShareIdentity = participant.identity
        participantGrid.visibility = View.GONE
        headerView.visibility = View.GONE
        if (::controlBar.isInitialized) controlBar.setPttCompact(true)
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
        if (::controlBar.isInitialized) controlBar.setPttCompact(false)
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
        val localIdentity = userController.userId.toString()
        return availableShares.firstOrNull { it.identity != localIdentity } ?: availableShares.first()
    }

    private fun openParticipantModerationSheet(participant: ParticipantInfo) {
        val context = fragmentView?.context ?: getParentActivity() ?: return
        val activity = getParentActivity() ?: return
        val identity = participant.identity
        val userId = identity.toLongOrNull() ?: 0L
        val canManageVoiceUser = canManageVoiceUser(userId)
        val mutedNow = participant.isMuted
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

    private fun canManageVoiceChannel(): Boolean {
        if (isGroupCall) return false
        if (clanId == 0L || channelId == 0L) return false
        return permissionPolicy.checkAnyPermission(
            listOf(PermissionPolicy.ADMINISTRATOR, PermissionPolicy.MANAGE_CHANNEL),
            channelId,
            clanId,
        )
    }

    private fun canManageVoiceUser(targetUserId: Long): Boolean {
        if (isInPipMode) return false
        if (targetUserId == 0L || targetUserId == userController.userId) return false
        return canManageVoiceChannel()
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
        val targetUserId = identity.toLongOrNull() ?: return
        fragmentScope.launch {
            runCatching {
                if (action == VoiceModerationAction.MUTE) {
                    voiceController.muteParticipant(clanId, channelId, targetUserId)
                } else {
                    voiceController.kickParticipant(clanId, channelId, targetUserId)
                }
            }.onSuccess {
                Log.d(TAG, "voice moderation ok action=$action targetUserId=$targetUserId")
            }.onFailure { e ->
                Log.e(TAG, "voice moderation failed action=$action targetUserId=$targetUserId", e)
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
        if (::sfuSession.isInitialized) {
            sfuSession.onConnectionState = null
            sfuSession.onParticipants = null
            sfuSession.onRoleChanged = null
            sfuSession.onError = null
            sfuSession.onLocalVideoTrack = null
            sfuSession.onLocalScreenTrack = null
            sfuSession.leave()
        }
        sfuConnected = false
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
