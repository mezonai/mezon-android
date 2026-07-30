package com.mezon.mobile.home.call

import android.Manifest
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ToastOverlay

class CallFragment : BaseFragment() {

    companion object {
        private const val REQUEST_CALL_ENABLE_VIDEO_CAMERA = 9101
        private const val REQUEST_CALL_UNMUTE_MIC = 9102
    }

    private var pendingEnableVideoAfterCameraGrant = false
    private var pendingUnmuteMicAfterAudioGrant = false

    private lateinit var callController: CallController
    private lateinit var userController: UserController

    private var avatarView: CallAvatarView? = null
    private var videoView: CallVideoView? = null
    private var localPipView: LocalCallVideoPip? = null
    private var controlBar: CallControlBar? = null
    private var durationView: CallDurationView? = null
    private var contentContainer: FrameLayout? = null
    private var dmHeader: DmCallHeaderView? = null
    private var lastConnectedMainVideoMode: Boolean? = null
    private var showingBusyEnd = false
    private val finishAfterBusyRunnable = Runnable { finishFragment() }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        callController = entryPoint.callController()
        userController = entryPoint.userController()
    }

    override fun createView(context: Context): View {
        hasOwnBackground = true

        val statusBarDp = AndroidUtilities.statusBarHeight / AndroidUtilities.density
        val headerHp = 56f
        val topContentDp = statusBarDp + headerHp

        val gradientBg = GradientDrawable(
            GradientDrawable.Orientation.RIGHT_LEFT,
            intArrayOf(themeColors.serverRailBg, themeColors.serverRailBg)
        )

        val root = FrameLayout(context).apply {
            background = gradientBg
        }

        val statusBarSpacer = View(context)
        root.addView(statusBarSpacer, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT.toFloat(),
            statusBarDp,
            Gravity.TOP
        ))

        dmHeader = DmCallHeaderView(context, themeColors).apply {
            onMinimizeClick = {
                finishFragment()
            }
            onSwitchCameraClick = {
                callController.switchCamera()
            }
            onVideoToggleClick = vid@{
                val act = getParentActivity() ?: return@vid
                if (CallPermissionUi.startCameraRequestIfNeededToEnableVideo(
                        act,
                        callController.isLocalVideoEnabled,
                        REQUEST_CALL_ENABLE_VIDEO_CAMERA
                    ) {
                        pendingEnableVideoAfterCameraGrant = true
                    }
                ) {
                    return@vid
                }
                pendingEnableVideoAfterCameraGrant = false
                callController.toggleVideo()
            }
        }
        root.addView(dmHeader, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, headerHp.toInt(),
            Gravity.TOP, 0f, statusBarDp, 0f, 0f
        ))

        contentContainer = FrameLayout(context)
        root.addView(contentContainer, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
            Gravity.TOP, 10f, topContentDp + 10f, 10f, 88f
        ))

        durationView = CallDurationView(context, themeColors)
        durationView!!.visibility = View.GONE
        root.addView(durationView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, 28,
            Gravity.TOP, 0f, topContentDp, 0f, 0f
        ))

        controlBar = CallControlBar(context, themeColors).apply {
            delegate = object : CallControlBar.Delegate {
                override fun onSpeakerClicked() {
                    callController.toggleSpeaker()
                }
                override fun onEndCallClicked() {
                    callController.hangup()
                    finishFragment()
                }
                override fun onMicClicked() {
                    val act = getParentActivity() ?: return
                    if (CallPermissionUi.startMicRequestIfNeededToUnmute(
                            act,
                            callController.isLocalAudioEnabled,
                            REQUEST_CALL_UNMUTE_MIC
                        ) {
                            pendingUnmuteMicAfterAudioGrant = true
                        }
                    ) {
                        return
                    }
                    pendingUnmuteMicAfterAudioGrant = false
                    callController.toggleMic()
                }
            }
        }
        root.addView(controlBar, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            Gravity.BOTTOM,
            0f, 0f, 0f, 40f
        ))

        updateUI()
        fragmentView = root
        getParentActivity()?.volumeControlStream = AudioManager.STREAM_VOICE_CALL
        return root
    }

    override fun onFragmentCreate(): Boolean {
        observe(NotificationCenter.callStateChanged) { _, _, _ ->
            updateUI()
        }
        observe(NotificationCenter.callMediaChanged) { _, _, _ ->
            updateMediaUI()
        }
        observe(NotificationCenter.callEnded) { _, _, args ->
            val reason = args.firstOrNull() as? CallEndReason
            val callInfo = args.getOrNull(1) as? CallInfo
            if (reason == CallEndReason.BUSY) {
                showBusyAndFinish(callInfo)
            } else {
                finishFragment()
            }
        }
        return super.onFragmentCreate()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQUEST_CALL_ENABLE_VIDEO_CAMERA -> {
                val act = getParentActivity() ?: return
                CallPermissionUi.completePendingToggleAfterSinglePermissionResult(
                    wasPending = pendingEnableVideoAfterCameraGrant,
                    clearPending = { pendingEnableVideoAfterCameraGrant = false },
                    permissions,
                    grantResults,
                    Manifest.permission.CAMERA,
                    act,
                    R.string.permission_no_camera,
                    onDeniedToast = { msg ->
                        MezonToast.show(this, ToastOverlay.ToastType.ERROR, msg)
                    },
                    onGranted = { callController.toggleVideo() }
                )
            }
            REQUEST_CALL_UNMUTE_MIC -> {
                val act = getParentActivity() ?: return
                CallPermissionUi.completePendingToggleAfterSinglePermissionResult(
                    wasPending = pendingUnmuteMicAfterAudioGrant,
                    clearPending = { pendingUnmuteMicAfterAudioGrant = false },
                    permissions,
                    grantResults,
                    Manifest.permission.RECORD_AUDIO,
                    act,
                    R.string.permission_no_audio,
                    onDeniedToast = { msg ->
                        MezonToast.show(this, ToastOverlay.ToastType.ERROR, msg)
                    },
                    onGranted = { callController.toggleMic() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::callController.isInitialized) {
            callController.restartCamera()
        }
    }

    override fun onFragmentDestroy() {
        getParentActivity()?.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
        fragmentView?.removeCallbacks(finishAfterBusyRunnable)
        showingBusyEnd = false
        durationView?.stopTimer()
        releaseLocalPipOverlay()
        releaseVideoView()
        lastConnectedMainVideoMode = null
        super.onFragmentDestroy()
    }

    private fun shouldShowRemoteVideo(): Boolean {
        return callController.shouldShowRemoteVideoForUi()
    }

    private fun applyLocalVideoPreviewInMainLayout() {
        val v = videoView ?: return
        if (!shouldShowRemoteVideo()) return
        val on = callController.isLocalVideoEnabled
        val pc = callController.getPeerConnection()
        if (on) {
            pc?.attachLocalRenderer(v.localRenderer)
            v.setLocalPreviewVisible(true)
        } else {
            pc?.detachLocalRenderer(v.localRenderer)
            v.setLocalPreviewVisible(false)
        }
    }

    private fun detachAndReleaseVideoView(v: CallVideoView) {
        callController.getPeerConnection()?.detachRemoteRenderer(v.remoteRenderer)
        callController.getPeerConnection()?.detachLocalRenderer(v.localRenderer)
        v.release()
    }

    private fun hideLocalPipOverlay() {
        val pip = localPipView ?: return
        callController.getPeerConnection()?.detachLocalRenderer(pip.renderer)
        pip.visibility = View.GONE
    }

    private fun releaseLocalPipOverlay() {
        val pip = localPipView ?: return
        callController.getPeerConnection()?.detachLocalRenderer(pip.renderer)
        pip.safeRelease()
        contentContainer?.removeView(pip)
        localPipView = null
    }

    private fun bindLocalPipOverlay() {
        if (callController.callState !is CallState.Connected ||
            !callController.isLocalVideoEnabled ||
            callController.getPeerConnection() == null) {
            hideLocalPipOverlay()
            return
        }
        if (localPipView == null) {
            val pip = LocalCallVideoPip(requireContext())
            pip.ensureInitialized()
            localPipView = pip
            contentContainer?.addView(
                pip,
                LayoutHelper.createFrame(
                    120,
                    160,
                    Gravity.TOP or Gravity.END,
                    0f,
                    10f,
                    10f,
                    0f
                )
            )
        }
        localPipView?.visibility = View.VISIBLE
        callController.getPeerConnection()?.attachLocalRenderer(localPipView!!.renderer)
        localPipView?.let { contentContainer?.bringChildToFront(it) }
    }

    private fun hideVideoView() {
        val v = videoView ?: return
        callController.getPeerConnection()?.detachRemoteRenderer(v.remoteRenderer)
        callController.getPeerConnection()?.detachLocalRenderer(v.localRenderer)
        v.visibility = View.GONE
    }

    private fun releaseVideoView() {
        videoView?.let {
            detachAndReleaseVideoView(it)
            contentContainer?.removeView(it)
            videoView = null
        }
    }

    private fun applyConnectedMainLayout(callInfo: CallInfo?) {
        if (shouldShowRemoteVideo()) {
            hideLocalPipOverlay()
            showVideoView()
            updateDurationPosition(true)
        } else {
            hideVideoView()
            showAvatarView(callInfo)
            bindLocalPipOverlay()
            updateDurationPosition(false)
        }
    }

    private fun updateUI() {
        if (showingBusyEnd) return
        val state = callController.callState
        val callInfo = callController.currentCallInfo()

        dmHeader?.setPeerName(callInfo?.peerName ?: "")
        controlBar?.isSpeakerActive = callController.isSpeakerOn
        controlBar?.isMicActive = callController.isLocalAudioEnabled
        updateHeaderControls()

        when (state) {
            is CallState.Idle -> {
                lastConnectedMainVideoMode = null
                finishFragment()
            }
            is CallState.Outgoing -> {
                lastConnectedMainVideoMode = null
                hideLocalPipOverlay()
                hideVideoView()
                showAvatarView(callInfo)
                avatarView?.setConnectingLoading(false)
                avatarView?.setStatus("Ringing")
                avatarView?.setConnected(false)
                avatarView?.startRingAnimation()
                durationView?.visibility = View.GONE
            }
            is CallState.Incoming -> {
                lastConnectedMainVideoMode = null
                hideLocalPipOverlay()
                hideVideoView()
                showAvatarView(callInfo)
                avatarView?.setConnectingLoading(false)
                avatarView?.setStatus("Incoming Call")
                avatarView?.setConnected(false)
                durationView?.visibility = View.GONE
            }
            is CallState.Connecting -> {
                lastConnectedMainVideoMode = null
                hideLocalPipOverlay()
                hideVideoView()
                showAvatarView(callInfo)
                avatarView?.setConnectingLoading(true)
                avatarView?.setStatus("")
                avatarView?.setConnected(false)
                avatarView?.stopRingAnimation()
                durationView?.visibility = View.GONE
            }
            is CallState.Connected -> {
                applyConnectedMainLayout(callInfo)
                avatarView?.setConnected(true)
                avatarView?.setStatus("")
                durationView?.visibility = View.VISIBLE
                durationView?.startTimer(state.connectedTime)
                lastConnectedMainVideoMode = shouldShowRemoteVideo()
            }
        }
    }

    private fun showBusyAndFinish(callInfo: CallInfo?) {
        showingBusyEnd = true
        lastConnectedMainVideoMode = null
        hideLocalPipOverlay()
        hideVideoView()
        showAvatarView(callInfo)
        avatarView?.setConnectingLoading(false)
        avatarView?.setConnected(false)
        avatarView?.stopRingAnimation()
        avatarView?.setStatus(getString(R.string.call_busy_status))
        val peerName = callInfo?.peerName?.takeIf { it.isNotBlank() }
        val toastText = if (peerName != null) {
            getString(R.string.call_busy_peer_status, peerName)
        } else {
            getString(R.string.call_busy_status)
        }
        MezonToast.show(this, ToastOverlay.ToastType.ERROR, toastText)
        durationView?.visibility = View.GONE
        fragmentView?.removeCallbacks(finishAfterBusyRunnable)
        fragmentView?.postDelayed(finishAfterBusyRunnable, 2000L)
    }

    private fun updateMediaUI() {
        controlBar?.isSpeakerActive = callController.isSpeakerOn
        controlBar?.isMicActive = callController.isLocalAudioEnabled
        updateHeaderControls()

        val callInfo = callController.currentCallInfo()
        if (callController.callState is CallState.Connected && callInfo != null) {
            val wantVideo = shouldShowRemoteVideo()
            if (lastConnectedMainVideoMode != wantVideo) {
                lastConnectedMainVideoMode = wantVideo
                applyConnectedMainLayout(callInfo)
            } else if (wantVideo) {
                applyLocalVideoPreviewInMainLayout()
            } else {
                bindLocalPipOverlay()
            }
        }
    }

    private fun updateHeaderControls() {
        dmHeader?.setLocalVideoEnabled(callController.isLocalVideoEnabled)
        dmHeader?.setSwitchCameraVisible(callController.isLocalVideoEnabled)
    }

    private fun updateDurationPosition(onVideo: Boolean) {
        val view = durationView ?: return
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val statusBarDp = AndroidUtilities.statusBarHeight / AndroidUtilities.density
        lp.topMargin = if (onVideo) {
            LayoutHelper.dp(statusBarDp)
        } else {
            LayoutHelper.dp(statusBarDp + 56f)
        }
        view.layoutParams = lp
    }

    private fun showAvatarView(callInfo: CallInfo?) {
        if (avatarView == null) {
            avatarView = CallAvatarView(requireContext(), themeColors)
        }
        if (avatarView?.parent == null) {
            contentContainer?.addView(avatarView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }
        avatarView?.visibility = View.VISIBLE
        callInfo?.let {
            avatarView?.setData(it.peerName, it.peerUsername, it.peerAvatar)
        }
        avatarView?.let { contentContainer?.bringChildToFront(it) }
    }

    private fun showVideoView() {
        hideLocalPipOverlay()
        avatarView?.let {
            it.stopRingAnimation()
            it.visibility = View.GONE
        }
        if (videoView == null) {
            videoView = CallVideoView(requireContext())
            videoView!!.initialize()
        }
        if (videoView?.parent == null) {
            contentContainer?.addView(videoView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT
            ))
        }
        videoView?.visibility = View.VISIBLE
        videoView?.let { v ->
            callController.getPeerConnection()?.attachRemoteRenderer(v.remoteRenderer)
        }
        applyLocalVideoPreviewInMainLayout()
        videoView?.let { contentContainer?.bringChildToFront(it) }
    }

}
