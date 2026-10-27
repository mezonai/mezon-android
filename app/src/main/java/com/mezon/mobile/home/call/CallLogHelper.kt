package com.mezon.mobile.home.call

import android.util.Log
import com.mezon.mezon.rtapi.channelMessageUpdate
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.channelTypeToStreamMode
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Lazy

private const val TAG = "CallLogHelper"

@Singleton
class CallLogHelper @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val chatController: Lazy<ChatController>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    fun updateAfterCallEnd(
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
        scope.launch(ioDispatcher) {
            try {
                // Match iOS: prefer the socket API and retain its HTTP fallback.
                val request = channelMessageUpdate {
                    this.clanId = clanId
                    this.channelId = channelId
                    this.mode = mode
                    this.isPublic = isPublic
                    this.messageId = messageId
                    this.content = content
                    this.hideEditted = true
                }
                sessionManager.withAutoRefresh { session ->
                    api.updateChannelMessage(session.apiUrl, session.token, request)
                }
                chatController.get().applyCallLogUpdate(channelId, clanId, channelType, messageId, content)
            } catch (e: CancellationException) {
                throw e
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
