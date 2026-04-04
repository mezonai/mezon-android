package com.mezon.mobile.home.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
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
private const val REQUEST_VOICE_PERMISSIONS = 1001

class VoiceRoomFragment : BaseFragment() {

    companion object {
        fun create(channelId: Long, clanId: Long, channelLabel: String): VoiceRoomFragment {
            return VoiceRoomFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHANNEL_ID, channelId)
                    putLong(ARG_CLAN_ID, clanId)
                    putString(ARG_CHANNEL_LABEL, channelLabel)
                }
            }
        }
    }

    private lateinit var voiceController: VoiceController
    private lateinit var userClanController: UserClanController
    private var channelId: Long = 0L
    private var clanId: Long = 0L
    private var channelLabel: String = ""

    private var room: Room? = null
    private var roomScope: CoroutineScope? = null

    private lateinit var headerView: VoiceHeaderView
    private lateinit var controlBar: VoiceControlBar
    private lateinit var participantGrid: RecyclerListView
    private val participantAdapter = ParticipantAdapter()
    private var reactionOverlay: ReactionOverlayView? = null
    private var audioManager: VoiceAudioManager? = null

    data class ParticipantInfo(
        val identity: String,
        val name: String,
        val avatarUrl: String? = null,
        val isMuted: Boolean,
        val isSpeaking: Boolean,
        val hasVideo: Boolean,
        val videoTrack: VideoTrack? = null,
        val isScreenShare: Boolean = false,
        val reactionBadge: ParticipantCell.ReactionBadgeType = ParticipantCell.ReactionBadgeType.NONE
    )

    private val participants = ArrayList<ParticipantInfo>()
    private val reactionStates = HashMap<String, ParticipantCell.ReactionBadgeType>()
    private var pendingUpdateJob: kotlinx.coroutines.Job? = null

    fun getChannelLabel(): String = channelLabel
    fun getParticipantCount(): Int = participants.size
    fun getRoom(): Room? = room

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
                    return FocusedContent(track, resolved.displayName, resolved.avatarUrl, !p.isMicrophoneEnabled, true, id.toLongOrNull() ?: 0L)
                }
            }
        }

        if (r.localParticipant.isScreenShareEnabled) {
            val track = r.localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? VideoTrack
            if (track != null) {
                val resolved = resolveMember(localId, r.localParticipant.name?.toString() ?: "You")
                return FocusedContent(track, resolved.displayName, resolved.avatarUrl, !r.localParticipant.isMicrophoneEnabled, true, localId.toLongOrNull() ?: 0L)
            }
        }

        for (p in r.remoteParticipants.values) {
            if (p.isCameraEnabled) {
                val track = p.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
                if (track != null) {
                    val id = p.identity?.value ?: ""
                    val resolved = resolveMember(id, p.name?.toString() ?: id)
                    return FocusedContent(track, resolved.displayName, resolved.avatarUrl, !p.isMicrophoneEnabled, false, id.toLongOrNull() ?: 0L)
                }
            }
        }

        if (r.localParticipant.isCameraEnabled) {
            val track = r.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
            if (track != null) {
                val resolved = resolveMember(localId, r.localParticipant.name?.toString() ?: "You")
                return FocusedContent(track, resolved.displayName, resolved.avatarUrl, !r.localParticipant.isMicrophoneEnabled, false, localId.toLongOrNull() ?: 0L)
            }
        }

        val first = participants.firstOrNull() ?: return null
        return FocusedContent(null, first.name, first.avatarUrl, first.isMuted, false, first.identity.toLongOrNull() ?: 0L)
    }

    private fun getMainActivity(): MainActivity? = getParentActivity() as? MainActivity

    private fun minimizeToOverlay() {
        getMainActivity()?.minimizeVoiceRoom()
    }

    private fun dismissOverlay() {
        getMainActivity()?.dismissVoiceRoom()
    }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        voiceController = entryPoint.voiceController()
        userClanController = entryPoint.userClanController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        channelLabel = arguments?.getString(ARG_CHANNEL_LABEL) ?: ""

        observe(NotificationCenter.voiceLeftRoom) { _, _, _ ->
            if (fragmentView == null) return@observe
            dismissOverlay()
        }

        observe(NotificationCenter.voiceRoomDisconnected) { _, _, args ->
            if (fragmentView == null) return@observe
            val reason = args.firstOrNull() as? String ?: "unknown"
            showDisconnectDialog(reason)
        }

        observe(NotificationCenter.voiceReactionReceived) { _, _, args ->
            if (fragmentView == null) return@observe
            @Suppress("UNCHECKED_CAST")
            val emojis = args.getOrNull(0) as? List<String> ?: return@observe
            val senderId = args.getOrNull(2) as? Long ?: 0L
            showReactionOverlay(emojis)
            if (senderId != 0L) {
                showPerParticipantBadge(senderId.toString(), emojis)
            }
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
        val statusBarSpacer = View(context)
        root.addView(statusBarSpacer, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT.toFloat(), statusBarHeight / AndroidUtilities.density,
            Gravity.TOP
        ))

        headerView = VoiceHeaderView(context, themeColors).apply {
            setChannelName(channelLabel)
            onMinimizeClick = { minimizeToOverlay() }
            onSwitchCameraClick = { room?.localParticipant?.let { /* switch camera if needed */ } }
            onAudioOutputClick = { cycleAudioOutput() }
            onMoreClick = { /* more menu */ }
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

        controlBar = VoiceControlBar(context, themeColors).apply {
            onCameraToggle = { enabled ->
                roomScope?.launch {
                    room?.localParticipant?.setCameraEnabled(enabled)
                    doUpdateParticipantList()
                }
            }
            onMicToggle = { enabled ->
                roomScope?.launch {
                    room?.localParticipant?.setMicrophoneEnabled(enabled)
                }
            }
            onChatClick = { minimizeToOverlay() }
            onRaiseHandClick = {
                voiceController.sendVoiceReaction(listOf("\uD83D\uDD90"), channelId)
            }
            onEndCallClick = {
                disconnectAndLeave()
                dismissOverlay()
            }
        }
        root.addView(controlBar, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, 66,
            Gravity.BOTTOM, 0f, 0f, 0f, 20f
        ))

        audioManager = VoiceAudioManager(context).also { it.start() }

        getParentActivity()?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        fragmentView = root

        requestPermissionsAndConnect()

        return root
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
                room = LiveKit.create(requireContext())
                Log.d(TAG, "LiveKit room created, connecting...")

                launch { collectRoomEvents() }

                Log.d(TAG, "Loading clan members for clanId=$clanId (current count=${userClanController.getClanMembers(clanId).size})")
                userClanController.loadClanMembers(clanId, noCache = true)
                if (!userClanController.loaded) {
                    userClanController.loadUsers(noCache = true)
                }

                room!!.connect(BuildConfig.MEZON_MEET_WS_URL, token)
                Log.d(TAG, "Connected to room, disabling local mic/camera")
                room!!.localParticipant.setMicrophoneEnabled(false)
                room!!.localParticipant.setCameraEnabled(false)

                Log.d(TAG, "Local participant: identity=${room!!.localParticipant.identity?.value} name=${room!!.localParticipant.name}")
                for (p in room!!.remoteParticipants.values) {
                    Log.d(TAG, "Remote participant: identity=${p.identity?.value} name=${p.name}")
                }

                doUpdateParticipantList()
                Log.d(TAG, "Initial participant list: ${participants.size} participants")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to LiveKit room", e)
                dismissOverlay()
            }
        }
    }

    private suspend fun collectRoomEvents() {
        val r = room ?: return
        r.events.collect { event ->
            when (event) {
                is RoomEvent.Disconnected -> {
                    voiceController.onDisconnectedFromRoom("disconnected")
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
        participant: Participant,
        identity: String,
        livekitName: String
    ) {
        val resolved = resolveMember(identity, livekitName)
        val avatarUrl = resolved.avatarUrl
        val displayName = resolved.displayName
        val badge = reactionStates[identity] ?: ParticipantCell.ReactionBadgeType.NONE

        val screenPub = participant.getTrackPublication(Track.Source.SCREEN_SHARE)
        val screenTrack = screenPub?.track as? VideoTrack
        if (screenTrack != null) {
            participants.add(ParticipantInfo(
                identity = identity,
                name = "$displayName Share Screen",
                avatarUrl = avatarUrl,
                isMuted = !participant.isMicrophoneEnabled,
                isSpeaking = participant.isSpeaking,
                hasVideo = true,
                videoTrack = screenTrack,
                isScreenShare = true,
                reactionBadge = badge
            ))
        }

        val cameraPub = participant.getTrackPublication(Track.Source.CAMERA)
        val cameraTrack = if (participant.isCameraEnabled) cameraPub?.track as? VideoTrack else null
        participants.add(ParticipantInfo(
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

    private fun scheduleUpdateParticipantList() {
        pendingUpdateJob?.cancel()
        pendingUpdateJob = roomScope?.launch {
            delay(100)
            doUpdateParticipantList()
        }
    }

    private fun doUpdateParticipantList() {
        val r = room ?: return
        participants.clear()

        val local = r.localParticipant
        val localId = local.identity?.value ?: ""
        val localName = local.name?.toString()?.ifEmpty { null } ?: "You"
        addParticipantEntries(local, localId, localName)

        for (p in r.remoteParticipants.values) {
            val remoteId = p.identity?.value ?: ""
            val remoteName = p.name?.toString()?.ifEmpty { null } ?: remoteId
            addParticipantEntries(p, remoteId, remoteName)
        }

        Log.d(TAG, "doUpdateParticipantList: ${participants.size} participants")
        participantAdapter.notifyDataSetChanged()
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
    }

    private fun releaseAllRenderers() {
        val count = participantGrid.childCount
        for (i in 0 until count) {
            (participantGrid.getChildAt(i) as? ParticipantCell)?.releaseRenderer()
        }
    }

    private fun disconnectAndLeave() {
        releaseAllRenderers()
        roomScope?.launch {
            try {
                room?.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "disconnect error", e)
            }
            room = null
            voiceController.leaveVoiceChannel()
        }
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
        val am = audioManager ?: return
        when (am.getCurrentDevice()) {
            AudioOutputDevice.EARPIECE -> am.setSpeaker()
            AudioOutputDevice.SPEAKER -> {
                if (am.isBluetoothAvailable()) am.setBluetooth() else am.setEarpiece()
            }
            AudioOutputDevice.BLUETOOTH -> am.setEarpiece()
        }
    }

    private fun showReactionOverlay(emojis: List<String>) {
        reactionOverlay?.showEmojis(emojis)
    }

    private fun showPerParticipantBadge(senderIdentity: String, emojis: List<String>) {
        val isRaiseHand = emojis.any { it == "\uD83D\uDD90" || it == "\u270B" }
        val type = if (isRaiseHand) ParticipantCell.ReactionBadgeType.RAISE_HAND
            else ParticipantCell.ReactionBadgeType.SOUND_EFFECT
        reactionStates[senderIdentity] = type

        val idx = participants.indexOfFirst { it.identity == senderIdentity }
        if (idx >= 0) {
            participants[idx] = participants[idx].copy(reactionBadge = type)
            val count = participantGrid.childCount
            for (i in 0 until count) {
                val child = participantGrid.getChildAt(i) as? ParticipantCell ?: continue
                if (participantGrid.getChildAdapterPosition(child) == idx) {
                    child.setReactionBadge(type)
                    break
                }
            }
        }

        roomScope?.launch {
            delay(3000)
            reactionStates.remove(senderIdentity)
            val pos = participants.indexOfFirst { it.identity == senderIdentity }
            if (pos >= 0) {
                participants[pos] = participants[pos].copy(reactionBadge = ParticipantCell.ReactionBadgeType.NONE)
                val count = participantGrid.childCount
                for (i in 0 until count) {
                    val child = participantGrid.getChildAt(i) as? ParticipantCell ?: continue
                    if (participantGrid.getChildAdapterPosition(child) == pos) {
                        child.clearReactionBadge()
                        break
                    }
                }
            }
        }
    }

    override fun onFragmentDestroy() {
        pendingUpdateJob?.cancel()
        getParentActivity()?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        releaseAllRenderers()
        reactionOverlay?.cancelAll()
        audioManager?.stop()
        audioManager = null
        roomScope?.cancel()
        roomScope = null
        super.onFragmentDestroy()
    }

    private inner class ParticipantAdapter : RecyclerView.Adapter<ParticipantVH>() {

        override fun getItemCount() = participants.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ParticipantVH {
            val cell = ParticipantCell(parent.context, themeColors).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    LayoutHelper.dp(150)
                ).apply {
                    val m = LayoutHelper.dp(5)
                    setMargins(m, m, m, m)
                }
            }
            return ParticipantVH(cell)
        }

        override fun onBindViewHolder(holder: ParticipantVH, position: Int) {
            val p = participants[position]
            holder.cell.setParticipant(
                p.identity.toLongOrNull() ?: 0L,
                p.name,
                p.avatarUrl,
                p.isMuted,
                p.isSpeaking,
                p.hasVideo,
                p.isScreenShare
            )

            holder.cell.setReactionBadge(p.reactionBadge)

            val r = room
            if (p.videoTrack != null && r != null) {
                holder.cell.attachVideoTrack(r, p.videoTrack)
            } else {
                holder.cell.detachVideoTrack()
            }
        }

        override fun onViewRecycled(holder: ParticipantVH) {
            holder.cell.detachVideoTrack()
        }
    }

    private class ParticipantVH(val cell: ParticipantCell) : RecyclerView.ViewHolder(cell)
}
