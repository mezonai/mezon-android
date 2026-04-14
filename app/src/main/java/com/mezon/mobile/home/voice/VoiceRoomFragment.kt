package com.mezon.mobile.home.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.chat.EmojiController
import com.mezon.mobile.home.clans.CHANNEL_TYPE_VOICE
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.ui.cells.MezonIcon
import io.livekit.android.LiveKit
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
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

private const val TAG = "VoiceRoomFragment"
private const val ARG_CHANNEL_ID = "channel_id"
private const val ARG_CLAN_ID = "clan_id"
private const val ARG_CHANNEL_LABEL = "channel_label"
private const val ARG_IS_GROUP_CALL = "is_group_call"
private const val REQUEST_VOICE_PERMISSIONS = 1401
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
    private lateinit var emojiController: EmojiController
    private lateinit var userController: UserController
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

    private val participants = ArrayList<ParticipantInfo>()
    private val reactionStates = HashMap<String, ParticipantCell.ReactionBadgeType>()
    private var pendingUpdateJob: kotlinx.coroutines.Job? = null
    private var raiseHandCooldownJob: kotlinx.coroutines.Job? = null
    private var isInPipMode = false
    private var isReconnecting = false
    private var isRaiseHandActive = false
    private var lastSwitchCameraElapsedMs = 0L

    fun getChannelLabel(): String = channelLabel
    fun getChannelId(): Long = channelId
    fun getClanId(): Long = clanId
    fun getParticipantCount(): Int = participants.size
    fun getRoom(): Room? = room

    fun enterPipMode() {
        isInPipMode = true
        clearFocusedShare()
        if (::headerView.isInitialized) headerView.visibility = View.GONE
        if (::controlBar.isInitialized) controlBar.visibility = View.GONE
        if (::morePopup.isInitialized) morePopup.dismiss()
        statusBarSpacer?.visibility = View.GONE
        reactionOverlay?.visibility = View.GONE
        raiseHandOverlay?.visibility = View.GONE
        if (::participantAdapter.isInitialized) participantAdapter.notifyDataSetChanged()
        applyVoiceLayoutForMode()
    }

    fun exitPipMode() {
        isInPipMode = false
        if (::headerView.isInitialized) headerView.visibility = View.VISIBLE
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
                    return FocusedContent(track, resolved.displayName, avatar, !p.isMicrophoneEnabled, true, id.toLongOrNull() ?: 0L)
                }
            }
        }

        if (r.localParticipant.isScreenShareEnabled) {
            val track = r.localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? VideoTrack
            if (track != null) {
                val resolved = resolveMember(localId, r.localParticipant.name?.toString() ?: "You")
                val avatar = effectiveAvatarUrl(resolved, r.localParticipant)
                return FocusedContent(track, resolved.displayName, avatar, !r.localParticipant.isMicrophoneEnabled, true, localId.toLongOrNull() ?: 0L)
            }
        }

        for (p in r.remoteParticipants.values) {
            if (p.isCameraEnabled) {
                val track = p.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
                if (track != null) {
                    val id = p.identity?.value ?: ""
                    val resolved = resolveMember(id, p.name?.toString() ?: id)
                    val avatar = effectiveAvatarUrl(resolved, p)
                    return FocusedContent(track, resolved.displayName, avatar, !p.isMicrophoneEnabled, false, id.toLongOrNull() ?: 0L)
                }
            }
        }

        if (r.localParticipant.isCameraEnabled) {
            val track = r.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
            if (track != null) {
                val resolved = resolveMember(localId, r.localParticipant.name?.toString() ?: "You")
                val avatar = effectiveAvatarUrl(resolved, r.localParticipant)
                return FocusedContent(track, resolved.displayName, avatar, !r.localParticipant.isMicrophoneEnabled, false, localId.toLongOrNull() ?: 0L)
            }
        }

        val first = participants.firstOrNull() ?: return null
        return FocusedContent(null, first.name, first.avatarUrl, first.isMuted, false, first.identity.toLongOrNull() ?: 0L)
    }

    private fun getMainActivity(): MainActivity? = getParentActivity() as? MainActivity


    private fun applyAgentHeaderUi() {
        if (!::headerView.isInitialized) return
        headerView.setAgentActive(voiceController.isAiAgentEnabled(clanId, channelId))
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
                room, focused.videoTrack, focused.name,
                focused.avatarUrl, focused.isMuted, focused.userId
            )
        } else {
            manager.updateMiniContent(null, null, channelLabel, null, false, 0L)
        }
    }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        voiceController = entryPoint.voiceController()
        userClanController = entryPoint.userClanController()
        emojiController = entryPoint.emojiController()
        userController = entryPoint.userController()
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
            val senderName = parseReactionMeta(emojis.getOrNull(1), SENDER_NAME_PREFIX)
            val senderAvatar = parseReactionMeta(emojis.getOrNull(2), SENDER_AVATAR_PREFIX)
            if (senderId != 0L && raiseUpReaction != null) {
                val resolved = resolveRaiseHandDisplay(senderId, senderName, senderAvatar)
                raiseHandOverlay?.showRaiseHand(senderId, resolved.first, resolved.second)
            } else if (senderId != 0L && raiseDownReaction != null) {
                raiseHandOverlay?.removeRaiseHand(senderId)
            }
            if (soundReaction != null) {
                reactionHandler.playSoundReaction(soundReaction.removePrefix("sound:"))
            }
            if (!primaryReaction.isNullOrBlank() && soundReaction == null) {
                reactionHandler.showReactionOverlay(listOf(primaryReaction))
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
            if (fragmentView == null || isPaused) return@observe
            val evClan = args.getOrNull(0) as? Long ?: return@observe
            val evCh = args.getOrNull(1) as? Long ?: return@observe
            if (evClan != clanId || evCh != channelId) return@observe
            if (!::headerView.isInitialized) return@observe
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
                    headerView.setAgentLoading(true)
                    try {
                        if (voiceController.isAiAgentEnabled(clanId, channelId)) {
                            voiceController.disconnectAiAgent(channelId, info.roomName)
                        } else {
                            voiceController.addAiAgentToChannel(channelId, info.roomName)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Agent toggle failed", e)
                        Toast.makeText(ctx, "Agent request failed", Toast.LENGTH_SHORT).show()
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
                    val total = participants.size
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
            getParticipants = { participants },
            getRoom = { room },
            onScreenShareClick = { showFocusedShare(it) },
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
            getRoomScope = { roomScope }
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
            onMinimizeClick = { clearFocusedShare() }
        }
        root.addView(focusedShareView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
            Gravity.TOP, 0f, topOffset, 0f, 80f
        ))

        controlBar = VoiceControlBar(context, themeColors).apply {
            setGroupCallMode(isGroupCall)
            onCameraToggle = { enabled ->
                roomScope?.launch {
                    room?.localParticipant?.setCameraEnabled(enabled)
                    headerView.setSwitchCameraVisible(enabled)
                    doUpdateParticipantList()
                }
            }
            onMicToggle = { enabled ->
                roomScope?.launch {
                    room?.localParticipant?.setMicrophoneEnabled(enabled)
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
            it.onBluetoothStateChanged = { updateAudioOutputIcon() }
            it.start()
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
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (needed.isNotEmpty()) {
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
        if (requestCode == REQUEST_VOICE_PERMISSIONS) {
            val audioGranted = grantResults.isNotEmpty() &&
                permissions.indexOf(Manifest.permission.RECORD_AUDIO).let { idx ->
                    idx < 0 || grantResults[idx] == PackageManager.PERMISSION_GRANTED
                }
            if (!audioGranted &&
                ContextCompat.checkSelfPermission(
                    fragmentView?.context ?: return,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                val activity = getParentActivity() ?: return
                AlertDialog.Builder(activity)
                    .setTitle("Microphone Required")
                    .setMessage("Microphone permission is required to join voice channels.")
                    .setPositiveButton("OK") { _, _ -> dismissOverlay() }
                    .show()
                return
            }
            connectToRoom()
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
                room = LiveKit.create(ctx)
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
                room!!.localParticipant.setMicrophoneEnabled(false)
                room!!.localParticipant.setCameraEnabled(false)
                headerView.setSwitchCameraVisible(false)

                Log.d(TAG, "Local participant: identity=${room!!.localParticipant.identity?.value} name=${room!!.localParticipant.name}")
                for (p in room!!.remoteParticipants.values) {
                    Log.d(TAG, "Remote participant: identity=${p.identity?.value} name=${p.name}")
                }

                doUpdateParticipantList()
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
                is RoomEvent.TrackMuted -> updateMuteState()
                is RoomEvent.TrackUnmuted -> updateMuteState()
                is RoomEvent.ActiveSpeakersChanged -> updateSpeakingState(event.speakers)
                else -> {}
            }
        }
    }

    private data class ResolvedMember(val displayName: String, val avatarUrl: String?)

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
                return ResolvedMember(name, avatar)
            }

            val user = userClanController.getUserById(userId)
            if (user != null) {
                val name = user.displayName.ifBlank { user.username.ifBlank { livekitName } }
                val avatar = user.avatarUrl.ifEmpty { null }
                Log.d(TAG, "resolveMember: identity=$identity -> clanUser name=$name avatar=${avatar?.take(40)}")
                return ResolvedMember(name, avatar)
            }
        }

        Log.d(TAG, "resolveMember: identity=$identity -> NO MATCH, livekit name=$livekitName, " +
            "clanMembers(${clanId})=${userClanController.getClanMembers(clanId).size}, " +
            "users=${userClanController.getUserCount()}")
        return ResolvedMember(livekitName, null)
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
        val badge = reactionStates[identity] ?: ParticipantCell.ReactionBadgeType.NONE

        val screenPub = participant.getTrackPublication(Track.Source.SCREEN_SHARE)
        val screenTrack = screenPub?.track as? VideoTrack
        if (screenTrack != null) {
            val screenAspectRatio = resolveScreenShareAspectRatio(screenPub, screenTrack)
            target.add(ParticipantInfo(
                identity = identity,
                name = "$displayName Share Screen",
                avatarUrl = avatarUrl,
                isMuted = !participant.isMicrophoneEnabled,
                isSpeaking = participant.isSpeaking,
                hasVideo = true,
                videoTrack = screenTrack,
                isScreenShare = true,
                contentAspectRatio = screenAspectRatio,
                reactionBadge = badge
            ))
        }

        val cameraPub = participant.getTrackPublication(Track.Source.CAMERA)
        val cameraTrack = if (participant.isCameraEnabled) cameraPub?.track as? VideoTrack else null
        target.add(ParticipantInfo(
            identity = identity,
            name = displayName,
            avatarUrl = avatarUrl,
            isMuted = !participant.isMicrophoneEnabled,
            isSpeaking = participant.isSpeaking,
            hasVideo = cameraTrack != null,
            videoTrack = cameraTrack,
            isScreenShare = false,
            reactionBadge = badge
        ))
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

    private fun updateParticipants(next: List<ParticipantInfo>) {
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

        updateParticipants(nextParticipants)
        Log.d(TAG, "doUpdateParticipantList: ${participants.size} participants")
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
            val muted = !participant.isMicrophoneEnabled
            if (p.isMuted != muted) {
                participants[i] = p.copy(isMuted = muted)
                changed = true
            }
        }
        if (!changed) return
        val count = participantGrid.childCount
        for (i in 0 until count) {
            val child = participantGrid.getChildAt(i) as? ParticipantCell ?: continue
            val pos = participantGrid.getChildAdapterPosition(child)
            if (pos in participants.indices) {
                val pi = participants[pos]
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
        val count = participantGrid.childCount
        for (i in 0 until count) {
            val child = participantGrid.getChildAt(i) as? ParticipantCell ?: continue
            val pos = participantGrid.getChildAdapterPosition(child)
            if (pos in participants.indices) {
                child.updateSpeaking(participants[pos].isSpeaking)
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
        val am = audioManager ?: return
        when (am.getCurrentDevice()) {
            AudioOutputDevice.EARPIECE -> am.setSpeaker()
            AudioOutputDevice.SPEAKER -> {
                if (am.isBluetoothAvailable()) am.setBluetooth() else am.setEarpiece()
            }
            AudioOutputDevice.BLUETOOTH -> am.setEarpiece()
        }
        updateAudioOutputIcon()
    }

    private fun updateAudioOutputIcon() {
        val am = audioManager ?: return
        val icon = when (am.getCurrentDevice()) {
            AudioOutputDevice.EARPIECE -> MezonIcon.voiceLowIcon
            AudioOutputDevice.SPEAKER -> MezonIcon.channelVoice
            AudioOutputDevice.BLUETOOTH -> MezonIcon.bluetoothIcon
        }
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
    }

    private fun showFocusedShare(participant: ParticipantInfo) {
        val r = room ?: return
        val shown = focusedShareView.showShare(participant, r)
        if (!shown) return
        participantGrid.visibility = View.GONE
        headerView.visibility = View.GONE
        applyVoiceLayoutForMode()
    }

    private fun clearFocusedShare() {
        if (!::focusedShareView.isInitialized || !::participantGrid.isInitialized || !::headerView.isInitialized) {
            return
        }
        focusedShareView.clear()
        participantGrid.visibility = View.VISIBLE
        headerView.visibility = View.VISIBLE
        applyVoiceLayoutForMode()
    }

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
        audioManager?.stop()
        audioManager = null
        roomScope?.cancel()
        roomScope = null
        super.onFragmentDestroy()
    }

    private fun sendRaiseHandReaction() {
        val identity = userController.userId
        if (identity == 0L) return
        val localMember = userClanController.getClanMembers(clanId).firstOrNull { it.userId == identity }
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

    private fun parseReactionMeta(raw: String?, prefix: String): String {
        if (raw.isNullOrBlank()) return ""
        return if (raw.startsWith(prefix)) raw.removePrefix(prefix).trim() else raw.trim()
    }

    private fun resolveRaiseHandDisplay(senderId: Long, senderName: String, senderAvatar: String): Pair<String, String?> {
        if (senderId == userController.userId) {
            val selfName = senderName.ifBlank {
                userController.displayName.ifBlank { userController.username }
            }
            val selfAvatar = senderAvatar.ifBlank { userController.avatarUrl }
            return selfName to selfAvatar.ifBlank { null }
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
            return name to avatar.ifBlank { null }
        }
        val fallbackName = senderName.ifBlank { "User" }
        val fallbackAvatar = senderAvatar.ifBlank { null }
        return fallbackName to fallbackAvatar
    }

}
