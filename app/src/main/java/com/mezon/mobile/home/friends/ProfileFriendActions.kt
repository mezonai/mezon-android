package com.mezon.mobile.home.friends

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.ui.cells.ToastOverlay

fun sendProfileFriendRequest(
    friendController: FriendController,
    userId: Long,
    username: String,
) {
    if (userId == 0L) return
    if (friendController.isUserBlocked(userId)) {
        showProfileFriendToast(ToastOverlay.ToastType.ERROR, R.string.friends_toast_blocked_user)
        return
    }
    when (friendController.findFriendByUserId(userId)?.state) {
        FRIEND_STATE_FRIEND -> {
            showProfileFriendToast(ToastOverlay.ToastType.INFO, R.string.friends_toast_already_friend)
            return
        }
        FRIEND_STATE_INVITE_SENT -> {
            showProfileFriendToast(ToastOverlay.ToastType.INFO, R.string.friends_toast_wait_accept)
            return
        }
        FRIEND_STATE_BLOCKED -> {
            showProfileFriendToast(ToastOverlay.ToastType.ERROR, R.string.friends_toast_blocked_user)
            return
        }
        else -> Unit
    }
    friendController.sendFriendRequest(userId, username) { success ->
        showProfileFriendToast(
            if (success) ToastOverlay.ToastType.SUCCESS else ToastOverlay.ToastType.ERROR,
            if (success) R.string.friends_toast_send_success else R.string.friends_toast_send_fail
        )
    }
}

fun cancelProfileFriendRequest(
    friendController: FriendController,
    userId: Long,
    username: String,
) {
    if (userId == 0L) return
    friendController.deleteFriendRelation(userId, username) { success ->
        if (!success) {
            showProfileFriendToast(ToastOverlay.ToastType.ERROR, R.string.friends_toast_send_fail)
        }
    }
}

private fun showProfileFriendToast(type: ToastOverlay.ToastType, messageRes: Int) {
    Handler(Looper.getMainLooper()).post {
        val activity = MainActivity.instance
        if (activity != null) {
            val message = activity.getString(messageRes)
            ToastOverlay(activity, activity.themeColors).show(
                activity.drawerLayoutContainer,
                type,
                message
            )
            return@post
        }
        val ctx = AndroidUtilities.applicationContext ?: return@post
        Toast.makeText(ctx, ctx.getString(messageRes), Toast.LENGTH_SHORT).show()
    }
}
