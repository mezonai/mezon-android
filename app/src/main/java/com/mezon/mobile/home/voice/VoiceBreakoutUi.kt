package com.mezon.mobile.home.voice

import android.app.Activity
import android.app.Dialog
import android.widget.Toast
import android.view.WindowManager
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.home.voice.sfu.MezonSfuSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The voice fragment survives mini-overlay/PiP; call state lives in VoiceController. */
internal class VoiceBreakoutUi(
    private val controller: VoiceController,
    private val sfu: MezonSfuSession,
    private val scope: CoroutineScope,
    private val activity: () -> Activity?,
    private val canPresent: () -> Boolean,
    private val roomName: (Long) -> String,
    private val move: (VoiceController.PreparedBreakoutMove) -> Boolean,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private var monitor: Job? = null
    private var preparation: Job? = null
    private var preparationId = 0L
    private var preparingRevision: Long? = null
    private var preparingGeneration = 0L
    private var dialog: Dialog? = null
    private var dialogActivity: Activity? = null
    private var dialogRevision: Long? = null

    fun start() {
        if (monitor?.isActive == true) return
        monitor = scope.launch(uiDispatcher) {
            while (isActive) {
                update()
                delay(250)
            }
        }
    }

    fun destroy() {
        monitor?.cancel()
        pause()
    }

    /** Hide transient UI without consuming the call's invitation or accepted move. */
    fun pause() {
        cancelPreparation()
        dismissPopup()
    }

    private fun dismissPopup() {
        val previous = dialog
        dialog = null
        dialogActivity = null
        dialogRevision = null
        try {
            previous?.dismiss()
        } catch (_: IllegalArgumentException) {
            // Android may have already detached the old Activity's decor view.
        }
    }

    private fun cancelPreparation() {
        preparationId++
        preparation?.cancel()
        preparation = null
        preparingRevision = null
    }

    private fun availableActivity(requireWindowFocus: Boolean = false): Activity? = activity()?.takeIf {
        !it.isFinishing && !it.isDestroyed && !it.isInPictureInPictureMode &&
            !MainActivity.applicationPaused && canPresent() && it.window.decorView.isAttachedToWindow &&
            (!requireWindowFocus || it.hasWindowFocus())
    }

    private fun update() {
        val snapshot = controller.breakout.snapshot()
        val invitation = snapshot.invitation
        val source = snapshot.current
        if (invitation == null || source == null) {
            dismissPopup()
            cancelPreparation()
            return
        }
        if (preparingRevision != invitation.revision || preparingGeneration != controller.callGeneration) {
            cancelPreparation()
        }
        val host = availableActivity()
        if (!sfu.isInRoom(source.channelId, source.clanId) || host == null) {
            dismissPopup()
            cancelPreparation()
            return
        }
        if (snapshot.accepted != null) {
            dismissPopup()
            if (preparation == null && controller.breakoutSocketReady && host.hasWindowFocus()) {
                prepare(invitation.revision, snapshot.accepted)
            }
            return
        }
        if (dialog?.isShowing == true && dialogRevision == invitation.revision && dialogActivity === host) return
        dismissPopup()
        if (!host.hasWindowFocus()) return
        val ids = when (val signal = invitation.signal) {
            is VoiceBreakoutState.Signal.Assigned -> listOf(signal.channelId)
            is VoiceBreakoutState.Signal.Choose -> signal.channelIds
            VoiceBreakoutState.Signal.Return -> listOf(invitation.parent.channelId)
        }
        val popup = VoiceBreakoutDialog(host, invitation.signal, ids.map { it to roomName(it) },
            onJoin = { controller.breakout.accept(invitation.revision, it) },
            onNotNow = { controller.breakout.dismiss(invitation.revision) })
        try {
            popup.show()
            dialog = popup
            dialogActivity = host
            dialogRevision = invitation.revision
        } catch (_: WindowManager.BadTokenException) {
            // Keep monitoring: the same invite can be shown when the window returns.
        } catch (_: WindowManager.InvalidDisplayException) {
            // A PiP/display transition can invalidate the host after the checks above.
        }
    }

    private fun prepare(revision: Long, channelId: Long) {
        val source = controller.breakout.snapshot().current ?: return
        val id = ++preparationId
        preparingRevision = revision
        val generation = controller.callGeneration
        preparingGeneration = generation
        preparation = scope.launch(uiDispatcher, start = CoroutineStart.LAZY) {
            val socketGeneration = controller.breakoutSocketGeneration
            try {
                val prepared = controller.prepareBreakoutMove(revision, channelId) ?: return@launch
                if (id != preparationId || availableActivity(requireWindowFocus = true) == null ||
                    !sfu.isInRoom(source.channelId, source.clanId) || !controller.isBreakoutMoveCurrent(prepared)) return@launch
                if (!move(prepared)) {
                    controller.breakout.reoffer(revision)
                    availableActivity()?.let { Toast.makeText(it, R.string.voice_breakout_unavailable, Toast.LENGTH_SHORT).show() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A reconnect invalidates the request, while accepted intent remains.
                if (id == preparationId && controller.breakoutSocketReady &&
                    socketGeneration == controller.breakoutSocketGeneration && generation == controller.callGeneration &&
                    sfu.isInRoom(source.channelId, source.clanId) && availableActivity(requireWindowFocus = true) != null) {
                    controller.breakout.reoffer(revision)
                    availableActivity()?.let { Toast.makeText(it, R.string.voice_breakout_unavailable, Toast.LENGTH_SHORT).show() }
                }
            } finally {
                if (id == preparationId) preparation = null
            }
        }
        preparation?.start()
    }
}
