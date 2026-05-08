package com.mezon.mobile.home.call

import android.util.Log
import com.mezon.mobile.network.MezonSocket
import com.mezon.mobile.network.channelTypeToStreamMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CallLogHelper"

@Singleton
class CallLogHelper @Inject constructor() {

    fun updateAfterCallEnd(
        socket: MezonSocket,
        scope: CoroutineScope,
        channelId: Long,
        clanId: Long,
        channelType: Int,
        isChannelPrivate: Boolean,
        messageId: Long,
        reason: CallEndReason,
        wasConnected: Boolean,
        durationMs: Long,
        isVideo: Boolean
    ) {
        if (messageId == 0L || channelId == 0L) return
        val mode = channelTypeToStreamMode(channelType)
        val isPublic = !isChannelPrivate
        val pair = mapReasonToCallLog(reason, wasConnected, durationMs) ?: return
        val (type, t) = pair
        val content = buildCallLogJson(t, type, isVideo)
        scope.launch {
            try {
                socket.updateChatMessage(
                    clanId = clanId,
                    channelId = channelId,
                    mode = mode,
                    isPublic = isPublic,
                    messageId = messageId,
                    content = content,
                    hideEditted = true
                )
            } catch (e: Exception) {
                Log.e(TAG, "updateCallLog failed", e)
            }
        }
    }

    private fun mapReasonToCallLog(
        reason: CallEndReason,
        wasConnected: Boolean,
        durationMs: Long
    ): Pair<Int, String>? {
        if (wasConnected) {
            return Pair(CallLogMessageType.FINISHCALL, formatFinishDurationMs(durationMs))
        }
        return when (reason) {
            CallEndReason.TIMEOUT,
            CallEndReason.REMOTE_TIMEOUT -> Pair(CallLogMessageType.TIMEOUTCALL, "")
            CallEndReason.REMOTE_HANGUP,
            CallEndReason.BUSY,
            CallEndReason.REMOTE_REJECT,
            CallEndReason.LOCAL_REJECT -> Pair(CallLogMessageType.REJECTCALL, "")
            CallEndReason.LOCAL_HANGUP,
            CallEndReason.CANCELLED,
            CallEndReason.CLEAR_CALL -> Pair(CallLogMessageType.CANCELCALL, "")
            CallEndReason.ERROR,
            CallEndReason.ICE_FAILED -> Pair(CallLogMessageType.CANCELCALL, "")
        }
    }
}
