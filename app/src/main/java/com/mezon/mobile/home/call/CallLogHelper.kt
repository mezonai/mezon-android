package com.mezon.mobile.home.call

import android.os.SystemClock
import android.util.Log
import com.mezon.mobile.network.MezonSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val TAG = "CallLogHelper"

object CallLogHelper {

    fun updateCallLog(
        socket: MezonSocket,
        scope: CoroutineScope,
        channelId: Long,
        clanId: Long,
        messageId: Long,
        reason: CallEndReason,
        durationMs: Long
    ) {
        if (messageId == 0L || channelId == 0L) return

        val callStatus = when (reason) {
            CallEndReason.LOCAL_HANGUP, CallEndReason.REMOTE_HANGUP -> "ENDCALL"
            CallEndReason.TIMEOUT, CallEndReason.REMOTE_TIMEOUT -> "TIMEOUTCALL"
            CallEndReason.LOCAL_REJECT, CallEndReason.REMOTE_REJECT -> "REJECTCALL"
            CallEndReason.CANCELLED -> "CANCELCALL"
            CallEndReason.BUSY -> "BUSYCALL"
            else -> "MISSEDCALL"
        }

        val durationSeconds = (durationMs / 1000).toInt()
        val content = JSONObject().apply {
            put("callStatus", callStatus)
            put("duration", durationSeconds)
        }.toString()

        scope.launch {
            try {
                socket.updateChatMessage(
                    clanId = clanId,
                    channelId = channelId,
                    mode = 4,
                    isPublic = false,
                    messageId = messageId,
                    content = content,
                    hideEditted = true
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update call log", e)
            }
        }
    }

    fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}
