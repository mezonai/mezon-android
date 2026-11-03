package com.mezon.mobile.home.qr

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.view.View
import android.widget.FrameLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.call.CallController
import com.mezon.mobile.home.call.CallFragment
import com.mezon.mobile.home.call.CallManager
import com.mezon.mobile.home.call.CallPermissionUi
import com.mezon.mobile.home.friends.FRIEND_STATE_FRIEND
import com.mezon.mobile.home.friends.FriendController
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QrProfileFragment : BaseFragment() {

    companion object {
        private const val ARG_DISPLAY_NAME = "displayName"
        private const val ARG_USERNAME = "username"
        private const val ARG_AVATAR_URL = "avatarUrl"
        private const val ARG_USER_ID = "userId"
        private const val REQUEST_CALL_AUDIO = 9032

        fun newInstance(
            username: String,
            displayName: String,
            avatarUrl: String?,
            userId: Long = 0L,
        ) = QrProfileFragment().apply {
            arguments = android.os.Bundle().apply {
                putString(ARG_USERNAME, username)
                putString(ARG_DISPLAY_NAME, displayName)
                putString(ARG_AVATAR_URL, avatarUrl)
                putLong(ARG_USER_ID, userId)
            }
        }
    }

    private var displayName = ""
    private var username = ""
    private var avatarUrl: String? = null
    private var userId = 0L
    private val profileName get() = displayName.ifBlank { username }

    private lateinit var friendController: FriendController
    private lateinit var dialogsController: DialogsController
    private lateinit var chatController: ChatController
    private lateinit var callController: CallController
    private lateinit var callManager: CallManager

    private var profileDialog: QrProfileDialog? = null
    private var callSettingsDialog: Dialog? = null
    private var pendingCallPermission = false
    private var openingDestination = false

    override fun onInject(entryPoint: FragmentEntryPoint) {
        friendController = entryPoint.friendController()
        dialogsController = entryPoint.dialogsController()
        chatController = entryPoint.chatController()
        callController = entryPoint.callController()
        callManager = entryPoint.callManager()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        displayName = arguments?.getString(ARG_DISPLAY_NAME).orEmpty()
        username = arguments?.getString(ARG_USERNAME).orEmpty()
        avatarUrl = arguments?.getString(ARG_AVATAR_URL)
        userId = arguments?.getLong(ARG_USER_ID, 0L) ?: 0L
        return true
    }

    override fun createView(context: android.content.Context): View = FrameLayout(context).apply {
        setBackgroundColor(themeColors.serverRailBg)
    }

    override fun getStatusBarColor(): Int = themeColors.serverRailBg

    override fun getNavigationBarColor(): Int = themeColors.serverRailBg

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        if (profileDialog != null || openingDestination || isFinished) return
        val activity = getParentActivity() ?: return
        val isSelf = userId != 0L && userId == chatController.getCurrentUserId()
        val dialog = QrProfileDialog(
            context = activity,
            themeColors = themeColors,
            userId = userId,
            name = profileName,
            username = username,
            avatarUrl = avatarUrl,
            showAddFriend = !isSelf && friendController.findFriendByUserId(userId)?.state != FRIEND_STATE_FRIEND,
            onAddFriend = { addFriend() },
            onMessage = { openDm() },
            onCall = { beginVoiceCall() },
        )
        profileDialog = dialog
        if (showDialog(dialog, false, null) == null) profileDialog = null
    }

    override fun dismissDialogOnPause(dialog: Dialog): Boolean = dialog !== profileDialog || !isLastFragment()

    override fun onDialogDismiss(dialog: Dialog) {
        if (dialog !== profileDialog) return
        profileDialog = null
        if (!openingDestination && !isFinished && !isFinishing() && isLastFragment()) finishFragment(false)
    }

    private fun addFriend() {
        profileDialog?.setActionsEnabled(false)
        friendController.sendFriendRequest(userId = userId, username = if (userId != 0L) "" else username) { success ->
            if (isFinished) return@sendFriendRequest
            fragmentScope.launch(Dispatchers.Main.immediate) {
                if (isFinished) return@launch
                profileDialog?.setActionsEnabled(true)
                if (success) {
                    showToast(getString(R.string.friends_toast_send_success), ToastOverlay.ToastType.SUCCESS)
                } else {
                    showToast(getString(R.string.friends_toast_send_fail), ToastOverlay.ToastType.ERROR)
                }
            }
        }
    }

    private fun openDm() {
        if (!hasUserId()) return
        profileDialog?.setActionsEnabled(false)
        fragmentScope.launch(Dispatchers.Main.immediate) {
            try {
                val dmChannelId = withContext(Dispatchers.IO) { dialogsController.getOrCreateDm(userId) }
                if (dmChannelId == 0L) {
                    showToast(getString(R.string.qr_dm_open_failed), ToastOverlay.ToastType.ERROR)
                    return@launch
                }
                val activity = getParentActivity() as? MainActivity ?: return@launch
                chatController.openChannel(dmChannelId, 0L, CHANNEL_TYPE_DM)
                dismissForNavigation()
                activity.openChat(dmChannelId, profileName, 0L, CHANNEL_TYPE_DM, replaceLastFragment = true)
            } finally {
                profileDialog?.setActionsEnabled(true)
            }
        }
    }

    private fun hasUserId(): Boolean {
        if (userId != 0L) return true
        showToast(getString(R.string.qr_dm_open_failed), ToastOverlay.ToastType.ERROR)
        return false
    }

    private fun canCallUser(): Boolean {
        if (isFinished || isFinishing() || !isLastFragment()) return false
        if (!hasUserId()) return false
        if (userId == chatController.getCurrentUserId()) {
            showToast(getString(R.string.cannot_call_yourself), ToastOverlay.ToastType.ERROR)
            return false
        }
        if (friendController.isUserBlocked(userId)) {
            showToast(getString(R.string.no_permission_call_blocked), ToastOverlay.ToastType.ERROR)
            return false
        }
        if (callController.isCallSessionActive()) {
            showToast(getString(R.string.call_ongoing_banner_title), ToastOverlay.ToastType.ERROR)
            return false
        }
        return true
    }

    private fun beginVoiceCall() {
        if (!canCallUser()) return
        val activity = getParentActivity() ?: return
        profileDialog?.setActionsEnabled(false)
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingCallPermission = true
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CALL_AUDIO)
        } else {
            runCallAfterSettingsPrompt()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_CALL_AUDIO || !pendingCallPermission) return
        pendingCallPermission = false
        if (CallPermissionUi.permissionGrantedInResult(permissions, grantResults, Manifest.permission.RECORD_AUDIO)) {
            runCallAfterSettingsPrompt()
        } else {
            profileDialog?.setActionsEnabled(true)
            showToast(getString(R.string.permission_no_audio), ToastOverlay.ToastType.ERROR)
            getParentActivity()?.let {
                CallPermissionUi.showMicOrCameraDeniedFeedback(it, Manifest.permission.RECORD_AUDIO, R.string.permission_no_audio)
            }
        }
    }

    private fun runCallAfterSettingsPrompt() {
        val activity = getParentActivity() ?: return
        if (!callManager.needsFullScreenIntentSettings()) {
            startVoiceCall()
            return
        }
        var callRequested = false
        callSettingsDialog = AlertDialog.Builder(activity)
            .setTitle(getString(R.string.call_full_screen_intent_title))
            .setMessage(getString(R.string.call_full_screen_intent_message))
            .setPositiveButton(getString(R.string.call_full_screen_intent_open_settings)) { dialog, _ ->
                callManager.launchFullScreenIntentSettings(activity)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.call_full_screen_intent_start_call_anyway)) { dialog, _ ->
                callRequested = true
                dialog.dismiss()
                startVoiceCall()
            }
            .create().apply {
                setOnDismissListener {
                    callSettingsDialog = null
                    if (!callRequested) profileDialog?.setActionsEnabled(true)
                }
                show()
            }
    }

    private fun startVoiceCall() {
        profileDialog?.setActionsEnabled(false)
        fragmentScope.launch(Dispatchers.Main.immediate) {
            try {
                val dmChannelId = withContext(Dispatchers.IO) { dialogsController.getOrCreateDm(userId) }
                if (dmChannelId == 0L) {
                    showToast(getString(R.string.qr_dm_open_failed), ToastOverlay.ToastType.ERROR)
                    return@launch
                }
                if (!canCallUser()) return@launch
                callController.startCall(
                    peerId = userId,
                    peerName = profileName,
                    peerAvatar = avatarUrl?.takeIf { it.isNotBlank() },
                    channelId = dmChannelId,
                    clanId = 0L,
                    channelType = CHANNEL_TYPE_DM,
                    isChannelPrivate = false,
                    isVideo = false,
                    preferSpeaker = true,
                )
                dismissForNavigation()
                presentFragment(CallFragment(), removeLast = true)
            } finally {
                profileDialog?.setActionsEnabled(true)
            }
        }
    }

    private fun dismissForNavigation() {
        openingDestination = true
        profileDialog?.dismiss()
    }

    override fun onFragmentDestroy() {
        openingDestination = true
        pendingCallPermission = false
        callSettingsDialog?.dismiss()
        profileDialog?.dismiss()
        super.onFragmentDestroy()
    }

    private fun showToast(message: String, type: ToastOverlay.ToastType) {
        val parent = getLayoutContainer() ?: return
        ToastOverlay(requireContext(), themeColors).show(parent, type, message)
    }
}
