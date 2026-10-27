package com.mezon.mobile.home.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.call.CallFragment
import com.mezon.mobile.home.call.CallPermissionUi
import com.mezon.mobile.home.friends.sendProfileFriendRequest
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Profile actions for user lists that do not have a ChatFragment behind them. */
class UserShortProfileActions(
    private val fragment: BaseFragment,
    private val entryPoint: FragmentEntryPoint,
    private val onOpenDm: (Long, String) -> Unit,
    private val onTransferFunds: (Long, String) -> Unit
) {
    private companion object {
        const val REQUEST_PROFILE_CALL_AUDIO = 9125
    }

    private var pendingCall: (() -> Unit)? = null
    private var resolvingDm = false

    fun show(
        userId: Long,
        username: String,
        displayName: String,
        avatarUrl: String,
        clanId: Long = 0L,
        roleIds: List<Long> = emptyList()
    ) {
        val activity = fragment.getParentActivity() ?: return
        if (userId == 0L || activity.isFinishing || activity.isDestroyed) return
        val name = displayName.ifBlank { username }
        val roles = if (clanId == 0L) emptyList() else {
            entryPoint.roleController().profileRoleChipsForMember(clanId, roleIds).map {
                UserProfileBottomSheet.UserProfileRole(it.roleId, it.title, it.color, it.iconUrl)
            }
        }
        val sheet = UserProfileBottomSheet(
            context = activity,
            userId = userId,
            displayName = name,
            username = username,
            avatarUrl = avatarUrl,
            isOwnProfile = userId == entryPoint.userController().userId,
            isDM = clanId == 0L,
            roles = roles,
            listener = object : UserProfileBottomSheet.UserProfileListener {
                override fun onSendMessage(userId: Long) {
                    resolveDm(userId) { onOpenDm(it, name) }
                }

                override fun onVoiceCall(userId: Long) {
                    startVoiceCall(userId, name, avatarUrl)
                }

                override fun onAddFriend(userId: Long) {
                    sendProfileFriendRequest(entryPoint.friendController(), userId, username)
                }

                override fun onTransferFunds(userId: Long) {
                    this@UserShortProfileActions.onTransferFunds(userId, username)
                }
            }
        )
        sheet.setDrawNavigationBar(true)
        fragment.showDialog(sheet)
    }

    private fun resolveDm(userId: Long, onResolved: (Long) -> Unit) {
        if (resolvingDm || fragment.isFinished) return
        resolvingDm = true
        fragment.fragmentScope.launch(Dispatchers.Main.immediate) {
            try {
                val dmId = withContext(entryPoint.ioDispatcher()) {
                    entryPoint.dialogsController().getOrCreateDm(userId)
                }
                if (fragment.isFinished) return@launch
                if (dmId == 0L) {
                    showError(R.string.contact_shared_error)
                } else {
                    onResolved(dmId)
                }
            } finally {
                resolvingDm = false
            }
        }
    }

    private fun canCall(userId: Long): Boolean {
        if (fragment.isFinished) return false
        if (userId == entryPoint.userController().userId) {
            showError(R.string.cannot_call_yourself)
            return false
        }
        if (entryPoint.friendController().isUserBlocked(userId)) {
            showError(R.string.no_permission_call_blocked)
            return false
        }
        return !entryPoint.callController().isCallSessionActive()
    }

    private fun startVoiceCall(userId: Long, name: String, avatarUrl: String) {
        if (!canCall(userId)) return
        val activity = fragment.getParentActivity() ?: return
        val startCall = {
            promptFullScreenIntent {
                if (canCall(userId)) {
                    resolveDm(userId) { dmId ->
                        if (canCall(userId)) {
                            entryPoint.callController().startCall(
                                peerId = userId,
                                peerName = name,
                                peerAvatar = avatarUrl.ifBlank { null },
                                channelId = dmId,
                                clanId = 0L,
                                channelType = CHANNEL_TYPE_DM,
                                isChannelPrivate = false,
                                isVideo = false
                            )
                            fragment.presentFragment(CallFragment())
                        }
                    }
                }
            }
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCall()
        } else {
            pendingCall = startCall
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_PROFILE_CALL_AUDIO)
        }
    }

    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_PROFILE_CALL_AUDIO) return
        val startCall = pendingCall ?: return
        pendingCall = null
        if (fragment.isFinished) return
        if (CallPermissionUi.permissionGrantedInResult(permissions, grantResults, Manifest.permission.RECORD_AUDIO)) {
            startCall()
        } else {
            showError(R.string.permission_no_audio)
            fragment.getParentActivity()?.let {
                CallPermissionUi.showMicOrCameraDeniedFeedback(it, Manifest.permission.RECORD_AUDIO, R.string.permission_no_audio)
            }
        }
    }

    private fun promptFullScreenIntent(startCall: () -> Unit) {
        val activity = fragment.getParentActivity() ?: return
        val callManager = entryPoint.callManager()
        if (!callManager.needsFullScreenIntentSettings()) {
            startCall()
            return
        }
        fragment.showDialog(
            AlertDialog.Builder(activity)
                .setTitle(fragment.getString(R.string.call_full_screen_intent_title))
                .setMessage(fragment.getString(R.string.call_full_screen_intent_message))
                .setPositiveButton(fragment.getString(R.string.call_full_screen_intent_open_settings)) { dialog, _ ->
                    callManager.launchFullScreenIntentSettings(activity)
                    dialog.dismiss()
                }
                .setNegativeButton(fragment.getString(R.string.call_full_screen_intent_start_call_anyway)) { dialog, _ ->
                    dialog.dismiss()
                    startCall()
                }
                .create()
        )
    }

    private fun showError(messageRes: Int) {
        MezonToast.show(fragment, ToastOverlay.ToastType.ERROR, fragment.getString(messageRes))
    }
}
