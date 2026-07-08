package com.mezon.mobile.home.call

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.DisconnectCause
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.voice.VoiceChrome
import com.mezon.mobile.home.voice.VoiceStyleCircleButton
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon
import dagger.hilt.android.EntryPointAccessors
import org.json.JSONObject

private const val TAG = "IncomingCallActivity"

class IncomingCallActivity : Activity(), NotificationCenter.NotificationCenterDelegate {

    private val handler = Handler(Looper.getMainLooper())
    private var callAudioManager: CallAudioManager? = null
    private var offerJson: String? = null
    private var callerName: String = "Unknown"
    private var callerUsername: String = ""
    private var callerAvatar: String? = null
    private var dismissed = false
    private var connecting = false
    private var observersAttached = false

    private var rootView: FrameLayout? = null
    private var contentLayout: LinearLayout? = null
    private var titleView: TextView? = null
    private var statusView: TextView? = null
    private var nameView: TextView? = null
    private var ringingAvatarView: AvatarView? = null
    private var actionsContainer: LinearLayout? = null
    private var fullScreenIntentHint: TextView? = null
    private var connectingContainer: LinearLayout? = null

    private var connectedRoot: FrameLayout? = null
    private var connectedHeader: DmCallHeaderView? = null
    private var connectedContentContainer: FrameLayout? = null
    private var connectedAvatarView: CallAvatarView? = null
    private var connectedVideoView: CallVideoView? = null
    private var connectedLocalPip: LocalCallVideoPip? = null
    private var connectedControlBar: CallControlBar? = null
    private var connectedDurationView: CallDurationView? = null
    private var lastConnectedMainVideoMode: Boolean? = null

    private var pendingConnectedVideoAfterCameraGrant = false
    private var pendingConnectedUnmuteMicAfterAudioGrant = false

    private val autoDeclineRunnable = Runnable {
        if (!dismissed && !connecting) {
            declineCall()
        }
    }

    private val acceptTimeoutRunnable = Runnable {
        if (!dismissed && connecting) {
            Log.w(TAG, "acceptTimeoutRunnable: connecting timed out, ending")
            CallController.instance?.endCall(CallEndReason.TIMEOUT)
            finishCallActivity()
        }
    }

    private val deferredIncomingRingtoneRunnable = Runnable {
        applyIncomingRingtoneIfStillValid()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadIncomingData(intent)

        val controller = ensureCallController()
        val callInfo = controller?.currentCallInfo()
        val state = controller?.callState

        val hasRealCall = callInfo != null ||
            state is CallState.Incoming ||
            state is CallState.Connecting ||
            state is CallState.Connected ||
            offerJson != null

        if (!hasRealCall) {
            Log.d(TAG, "onCreate: no real incoming call, finishing (stale TelecomManager connection)")
            MezonCallConnection.activeConnection?.let {
                it.setCallDisconnected(DisconnectCause.CANCELED)
            }
            finish()
            return
        }

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        volumeControlStream = AudioManager.STREAM_VOICE_CALL

        if (callInfo != null) {
            callerName = callInfo.peerName
            callerUsername = callInfo.peerUsername
            callerAvatar = callInfo.peerAvatar
        }

        AndroidUtilities.checkDisplaySize(this)

        requestCallPermissionsEagerly()

        buildUI()
        bindUiData()

        attachObservers()

        refreshFullScreenIntentHint()

        when (state) {
            is CallState.Connecting -> {
                connecting = true
                cancelDeferredIncomingRingtone()
                callAudioManager?.stopTone()
                showConnectingUi()
            }
            is CallState.Connected -> {
                connecting = true
                cancelDeferredIncomingRingtone()
                callAudioManager?.stopTone()
                showConnectedUi(state.connectedTime)
            }
            else -> {
                scheduleIncomingRingtoneFromActivity()
                handler.postDelayed(autoDeclineRunnable, 30_000)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AndroidUtilities.checkDisplaySize(this, newConfig)
    }

    override fun onResume() {
        super.onResume()
        setIncomingCallUiForeground(true)
        refreshFullScreenIntentHint()
    }

    override fun onPause() {
        setIncomingCallUiForeground(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        loadIncomingData(intent)
        bindUiData()
        refreshFullScreenIntentHint()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CALL_PERMISSIONS -> {
                val msgRes = CallPermissionUi.permissionResultMessageForCall(
                    permissions,
                    grantResults,
                    incomingCallIsVideo()
                )
                if (msgRes != null) {
                    Toast.makeText(this, getString(msgRes), Toast.LENGTH_LONG).show()
                    val act = this
                    if (!CallPermissionUi.permissionGrantedInResult(
                            permissions,
                            grantResults,
                            Manifest.permission.RECORD_AUDIO
                        )
                    ) {
                        CallPermissionUi.showMicOrCameraDeniedFeedback(
                            act,
                            Manifest.permission.RECORD_AUDIO,
                            R.string.permission_no_audio
                        )
                    } else if (incomingCallIsVideo() &&
                        !CallPermissionUi.permissionGrantedInResult(
                            permissions,
                            grantResults,
                            Manifest.permission.CAMERA
                        )
                    ) {
                        CallPermissionUi.showMicOrCameraDeniedFeedback(
                            act,
                            Manifest.permission.CAMERA,
                            R.string.permission_no_camera
                        )
                    }
                }
            }
            REQUEST_CONNECTED_VIDEO_CAMERA -> {
                CallPermissionUi.completePendingToggleAfterSinglePermissionResult(
                    wasPending = pendingConnectedVideoAfterCameraGrant,
                    clearPending = { pendingConnectedVideoAfterCameraGrant = false },
                    permissions,
                    grantResults,
                    Manifest.permission.CAMERA,
                    this,
                    R.string.permission_no_camera,
                    onDeniedToast = { msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    },
                    onGranted = { CallController.instance?.toggleVideo() }
                )
            }
            REQUEST_CONNECTED_UNMUTE_MIC -> {
                CallPermissionUi.completePendingToggleAfterSinglePermissionResult(
                    wasPending = pendingConnectedUnmuteMicAfterAudioGrant,
                    clearPending = { pendingConnectedUnmuteMicAfterAudioGrant = false },
                    permissions,
                    grantResults,
                    Manifest.permission.RECORD_AUDIO,
                    this,
                    R.string.permission_no_audio,
                    onDeniedToast = { msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    },
                    onGranted = { CallController.instance?.toggleMic() }
                )
            }
        }
    }

    private fun attachObservers() {
        if (observersAttached) return
        observersAttached = true
        val nc = NotificationCenter.getInstance(0)
        nc.addObserver(this, NotificationCenter.callStateChanged)
        nc.addObserver(this, NotificationCenter.callMediaChanged)
        nc.addObserver(this, NotificationCenter.callEnded)
    }

    private fun detachObservers() {
        if (!observersAttached) return
        observersAttached = false
        val nc = NotificationCenter.getInstance(0)
        nc.removeObserver(this, NotificationCenter.callStateChanged)
        nc.removeObserver(this, NotificationCenter.callMediaChanged)
        nc.removeObserver(this, NotificationCenter.callEnded)
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        when (id) {
            NotificationCenter.callStateChanged -> handleStateChanged()
            NotificationCenter.callMediaChanged -> updateConnectedMediaUi()
            NotificationCenter.callEnded -> finishCallActivity()
        }
    }

    private fun handleStateChanged() {
        val controller = CallController.instance ?: return
        val state = controller.callState
        Log.d(TAG, "handleStateChanged: state=${state::class.simpleName}")
        when (state) {
            is CallState.Idle -> finishCallActivity()
            is CallState.Incoming -> {
                if (!connecting && !dismissed && !isFinishing) {
                    scheduleIncomingRingtoneFromActivity()
                }
                refreshFullScreenIntentHint()
            }
            is CallState.Connecting -> {
                connecting = true
                cancelDeferredIncomingRingtone()
                callAudioManager?.stopTone()
                handler.removeCallbacks(autoDeclineRunnable)
                showConnectingUi()
            }
            is CallState.Connected -> {
                connecting = true
                cancelDeferredIncomingRingtone()
                callAudioManager?.stopTone()
                handler.removeCallbacks(autoDeclineRunnable)
                handler.removeCallbacks(acceptTimeoutRunnable)
                showConnectedUi(state.connectedTime)
            }
            else -> {}
        }
    }

    private fun requestCallPermissionsEagerly() {
        val needed = mutableListOf<String>()
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            needed.add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (incomingCallIsVideo() &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            needed.add(android.Manifest.permission.CAMERA)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQUEST_CALL_PERMISSIONS)
        }
    }

    private fun incomingCallIsVideo(): Boolean {
        ensureCallController()?.currentCallInfo()?.let { return it.isVideo }
        val raw = offerJson ?: return false
        val parsed = rootJsonForIncomingOffer(raw) ?: return false
        return try {
            when {
                parsed.has("isVideoCall") -> parsed.getBoolean("isVideoCall")
                parsed.has("is_video_call") -> parsed.getBoolean("is_video_call")
                parsed.has("isVideo") -> parsed.getBoolean("isVideo")
                else -> SdpCompressor.sdpPlainTextFromNegotiationJson(parsed)?.contains("m=video") == true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun buildUI() {
        val dp = { value: Int -> LayoutHelper.dp(value) }
        val tc = ThemeColors.instance

        val headlineColor = tc.colorText
        val bodyMutedColor = tc.onSurfaceVariant
        val statusMutedColor = tc.textDisabled

        val rootBg = GradientDrawable(
            GradientDrawable.Orientation.RIGHT_LEFT,
            intArrayOf(tc.serverRailBg, tc.serverRailBg)
        )
        val root = FrameLayout(this).apply {
            background = rootBg
        }
        rootView = root

        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        titleView = TextView(this).apply {
            text = "Incoming Call"
            setTextColor(headlineColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            setPadding(0, dp(72), 0, dp(10))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        contentLayout!!.addView(titleView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        ringingAvatarView = AvatarView(this).apply {
            setSizeDp(150)
            setRoundRadius(75f)
        }
        contentLayout!!.addView(ringingAvatarView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(24)
        })

        nameView = TextView(this).apply {
            text = callerName
            setTextColor(bodyMutedColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        contentLayout!!.addView(nameView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        statusView = TextView(this).apply {
            text = "Incoming voice call..."
            setTextColor(statusMutedColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        contentLayout!!.addView(statusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(contentLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ))

        actionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER

            val pillBg = GradientDrawable().apply {
                cornerRadius = dp(80).toFloat()
                setColor(tc.channelPanelBg)
            }
            val pillRow = LinearLayout(this@IncomingCallActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = pillBg
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            val btnSize = dp(64)
            val declineBtn = VoiceStyleCircleButton(
                this@IncomingCallActivity,
                MezonIcon.callCancelIcon,
                VoiceChrome.RED_STRONG,
                0,
                0xFFFFFFFF.toInt(),
                VoiceStyleCircleButton.endCallIconSizePx(this@IncomingCallActivity)
            ).apply {
                setOnClickListener { declineCall() }
            }
            pillRow.addView(declineBtn, LinearLayout.LayoutParams(btnSize, btnSize))

            val acceptBtn = VoiceStyleCircleButton(
                this@IncomingCallActivity,
                MezonIcon.phoneCallIcon,
                tc.onlineGreen,
                0,
                0xFFFFFFFF.toInt(),
                VoiceStyleCircleButton.endCallIconSizePx(this@IncomingCallActivity)
            ).apply {
                setOnClickListener { acceptCall() }
            }
            pillRow.addView(acceptBtn, LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginStart = dp(18)
            })

            addView(pillRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        root.addView(actionsContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(70) })

        fullScreenIntentHint = TextView(this).apply {
            visibility = View.GONE
            text = getString(R.string.call_full_screen_intent_incoming_hint)
            setTextColor(tc.textLink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            setOnClickListener {
                callManager()?.launchFullScreenIntentSettings(this@IncomingCallActivity)
            }
        }
        root.addView(fullScreenIntentHint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(138) })

        connectingContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            addView(ProgressBar(this@IncomingCallActivity).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(headlineColor)
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
        }
        root.addView(connectingContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(110) })

        setContentView(root)
    }

    private fun bindUiData() {
        nameView?.text = callerName
        statusView?.text = "Incoming voice call..."
        titleView?.text = "Incoming Call"
        ringingAvatarView?.setInfo(0L, callerUsername.ifEmpty { callerName })
        ringingAvatarView?.setImageUrl(callerAvatar)
    }

    private fun scheduleIncomingRingtoneFromActivity() {
        handler.removeCallbacks(deferredIncomingRingtoneRunnable)
        ensureCallController()?.stopIncomingCallRingtone()
        handler.post(deferredIncomingRingtoneRunnable)
    }

    private fun cancelDeferredIncomingRingtone() {
        handler.removeCallbacks(deferredIncomingRingtoneRunnable)
    }

    private fun applyIncomingRingtoneIfStillValid() {
        if (dismissed || connecting || isFinishing) return
        val c = ensureCallController() ?: return
        if (c.callState !is CallState.Incoming) return
        try {
            callAudioManager?.stop()
            callAudioManager = null
            callAudioManager = CallAudioManager(this).also {
                it.startForIncomingRing()
                it.playRingtone()
            }
        } catch (_: Exception) {
        }
    }

    private fun loadIncomingData(intent: Intent?) {
        val prefs = getSharedPreferences("call_data", MODE_PRIVATE)
        val fromPrefsOffer = prefs.getString("incoming_call", null)
        val fromIntentOffer = intent?.getStringExtra(CallManager.EXTRA_OFFER_JSON)?.trim()?.takeIf { it.isNotEmpty() }

        offerJson = fromPrefsOffer
        if (!fromIntentOffer.isNullOrBlank()) {
            val intentLooksLikeOfferJson = fromIntentOffer.trimStart().startsWith("{")
            if (intentLooksLikeOfferJson) {
                try {
                    JSONObject(fromIntentOffer)
                    offerJson = fromIntentOffer
                    prefs.edit().putString("incoming_call", fromIntentOffer).apply()
                } catch (_: Exception) {
                    offerJson = fromPrefsOffer ?: fromIntentOffer
                }
            } else {
                offerJson = fromPrefsOffer ?: fromIntentOffer
            }
        }

        val intentName = intent?.getStringExtra(CallManager.EXTRA_CALLER_NAME)
        val intentAvatar = intent?.getStringExtra(CallManager.EXTRA_CALLER_AVATAR)
        if (!intentName.isNullOrBlank()) {
            callerName = intentName
        }
        if (!intentAvatar.isNullOrBlank()) {
            callerAvatar = intentAvatar
        }

        val json = offerJson
        if (!json.isNullOrBlank()) {
            rootJsonForIncomingOffer(json)?.let { parsed ->
                callerName = parsed.optString("callerName", callerName)
                callerUsername = parsed.optString("callerUsername", "").ifEmpty {
                    parsed.optString("username", callerUsername)
                }
                val parsedAvatar = parsed.optString("callerAvatar", "")
                if (parsedAvatar.isNotBlank()) {
                    callerAvatar = parsedAvatar
                }
            }
        }
    }

    private fun rootJsonForIncomingOffer(raw: String): JSONObject? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        return try {
            if (t.startsWith("{")) {
                JSONObject(t)
            } else {
                JSONObject(SdpCompressor.decompress(t))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun ensureCallController(): CallController? {
        CallController.instance?.let { return it }
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                FragmentEntryPoint::class.java
            )
            entryPoint.callController()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get CallController from Hilt", e)
            null
        }
    }

    private fun callManager(): CallManager? {
        return try {
            EntryPointAccessors.fromApplication(
                applicationContext,
                FragmentEntryPoint::class.java
            ).callManager()
        } catch (_: Exception) {
            null
        }
    }

    private fun refreshFullScreenIntentHint() {
        val hint = fullScreenIntentHint ?: return
        val show = !connecting && !dismissed && callManager()?.needsFullScreenIntentSettings() == true
        hint.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun acceptCall() {
        if (dismissed || connecting) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, getString(R.string.permission_no_audio), Toast.LENGTH_LONG).show()
            CallPermissionUi.showMicOrCameraDeniedFeedback(
                this,
                Manifest.permission.RECORD_AUDIO,
                R.string.permission_no_audio
            )
            requestCallPermissionsEagerly()
            return
        }

        connecting = true

        cancelDeferredIncomingRingtone()
        callAudioManager?.stopTone()
        handler.removeCallbacks(autoDeclineRunnable)
        handler.postDelayed(acceptTimeoutRunnable, 60_000)

        showConnectingUi()

        val controller = ensureCallController()
        val currentState = controller?.callState
        val json = offerJson

        Log.d(TAG, "acceptCall: instance=${controller != null}, state=${currentState?.let { it::class.simpleName }}, offerJson=${json != null}")

        when {
            currentState is CallState.Incoming -> {
                Log.d(TAG, "acceptCall: using WebSocket path (state=Incoming)")
                controller?.acceptCall()
            }
            currentState is CallState.Connecting || currentState is CallState.Connected -> {
                Log.d(TAG, "acceptCall: already connecting/connected, no-op")
            }
            json != null && controller != null -> {
                Log.d(TAG, "acceptCall: using FCM path (offerJson from SharedPreferences)")
                controller.acceptCallFromFcm(json)
            }
            else -> {
                Log.w(TAG, "acceptCall: no valid path — controller=${controller != null}, state=$currentState, json=$json")
                finishCallActivity()
            }
        }
    }

    private fun showConnectingUi() {
        lastConnectedMainVideoMode = null
        actionsContainer?.visibility = View.GONE
        connectingContainer?.visibility = View.VISIBLE
        statusView?.visibility = View.INVISIBLE
        connectedRoot?.visibility = View.GONE
        fullScreenIntentHint?.visibility = View.GONE
    }

    private fun showConnectedUi(connectedTime: Long) {
        contentLayout?.visibility = View.GONE
        actionsContainer?.visibility = View.GONE
        connectingContainer?.visibility = View.GONE
        fullScreenIntentHint?.visibility = View.GONE

        val controller = ensureCallController() ?: return
        val callInfo = controller.currentCallInfo() ?: return

        if (connectedRoot == null) {
            buildConnectedUi()
        }
        connectedRoot?.visibility = View.VISIBLE
        connectedHeader?.setPeerName(callInfo.peerName)
        connectedDurationView?.startTimer(connectedTime)
        applyConnectedMainLayout(callInfo)
        lastConnectedMainVideoMode = shouldShowRemoteVideo()
        updateConnectedMediaUi()
    }

    private fun buildConnectedUi() {
        val tc = ThemeColors.instance
        val statusBarDp = AndroidUtilities.statusBarHeight / AndroidUtilities.density
        val headerHp = 56f
        val topContentDp = statusBarDp + headerHp

        val gradientBg = GradientDrawable(
            GradientDrawable.Orientation.RIGHT_LEFT,
            intArrayOf(tc.serverRailBg, tc.serverRailBg)
        )

        val root = FrameLayout(this).apply { background = gradientBg }
        connectedRoot = root

        val statusBarSpacer = View(this)
        root.addView(statusBarSpacer, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT.toFloat(),
            statusBarDp,
            Gravity.TOP
        ))

        val controller = CallController.instance
        connectedHeader = DmCallHeaderView(this, tc).apply {
            onCloseClick = {
                controller?.hangup()
                finishCallActivity()
            }
            onSwitchCameraClick = {
                controller?.switchCamera()
            }
            onVideoToggleClick = vid@{
                val ctl = controller ?: return@vid
                if (CallPermissionUi.startCameraRequestIfNeededToEnableVideo(
                        this@IncomingCallActivity,
                        ctl.isLocalVideoEnabled,
                        REQUEST_CONNECTED_VIDEO_CAMERA
                    ) {
                        pendingConnectedVideoAfterCameraGrant = true
                    }
                ) {
                    return@vid
                }
                pendingConnectedVideoAfterCameraGrant = false
                ctl.toggleVideo()
            }
        }
        root.addView(connectedHeader, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, headerHp.toInt(),
            Gravity.TOP, 0f, statusBarDp, 0f, 0f
        ))

        connectedContentContainer = FrameLayout(this)
        root.addView(connectedContentContainer, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
            Gravity.TOP, 10f, topContentDp + 10f, 10f, 88f
        ))

        connectedDurationView = CallDurationView(this, tc)
        connectedDurationView!!.visibility = View.VISIBLE
        root.addView(connectedDurationView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, 28,
            Gravity.TOP, 0f, topContentDp, 0f, 0f
        ))

        connectedControlBar = CallControlBar(this, tc).apply {
            delegate = object : CallControlBar.Delegate {
                override fun onSpeakerClicked() {
                    controller?.toggleSpeaker()
                }
                override fun onEndCallClicked() {
                    controller?.hangup()
                    finishCallActivity()
                }
                override fun onMicClicked() {
                    val ctl = controller ?: return
                    if (CallPermissionUi.startMicRequestIfNeededToUnmute(
                            this@IncomingCallActivity,
                            ctl.isLocalAudioEnabled,
                            REQUEST_CONNECTED_UNMUTE_MIC
                        ) {
                            pendingConnectedUnmuteMicAfterAudioGrant = true
                        }
                    ) {
                        return
                    }
                    pendingConnectedUnmuteMicAfterAudioGrant = false
                    ctl.toggleMic()
                }
            }
        }
        root.addView(connectedControlBar, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            Gravity.BOTTOM,
            0f, 0f, 0f, 40f
        ))

        rootView?.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun shouldShowRemoteVideo(): Boolean {
        val controller = CallController.instance ?: return false
        return controller.shouldShowRemoteVideoForUi()
    }

    private fun applyConnectedMainLayout(callInfo: CallInfo) {
        if (shouldShowRemoteVideo()) {
            hideConnectedLocalPip()
            showConnectedVideoView()
            updateConnectedDurationPosition(true)
        } else {
            hideConnectedVideoView()
            showConnectedAvatarView(callInfo)
            bindConnectedLocalPip()
            updateConnectedDurationPosition(false)
        }
    }

    private fun updateConnectedMediaUi() {
        val controller = CallController.instance ?: return
        connectedControlBar?.isSpeakerActive = controller.isSpeakerOn
        connectedControlBar?.isMicActive = controller.isLocalAudioEnabled
        connectedHeader?.setLocalVideoEnabled(controller.isLocalVideoEnabled)
        connectedHeader?.setSwitchCameraVisible(controller.isLocalVideoEnabled)

        val callInfo = controller.currentCallInfo()
        if (controller.callState is CallState.Connected && callInfo != null) {
            val wantVideo = shouldShowRemoteVideo()
            if (lastConnectedMainVideoMode != wantVideo) {
                lastConnectedMainVideoMode = wantVideo
                applyConnectedMainLayout(callInfo)
            } else if (wantVideo) {
                applyConnectedLocalVideoPreviewInMainLayout()
            } else {
                bindConnectedLocalPip()
            }
        }
    }

    private fun showConnectedAvatarView(callInfo: CallInfo) {
        val tc = ThemeColors.instance
        if (connectedAvatarView == null) {
            connectedAvatarView = CallAvatarView(this, tc)
        }
        if (connectedAvatarView?.parent == null) {
            connectedContentContainer?.addView(connectedAvatarView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }
        connectedAvatarView?.visibility = View.VISIBLE
        connectedAvatarView?.setData(callInfo.peerName, callInfo.peerUsername, callInfo.peerAvatar)
        connectedAvatarView?.setConnected(true)
        connectedAvatarView?.setStatus("")
        connectedAvatarView?.stopRingAnimation()
        connectedAvatarView?.let { connectedContentContainer?.bringChildToFront(it) }
    }

    private fun showConnectedVideoView() {
        hideConnectedLocalPip()
        connectedAvatarView?.let {
            it.stopRingAnimation()
            it.visibility = View.GONE
        }
        if (connectedVideoView == null) {
            connectedVideoView = CallVideoView(this)
            connectedVideoView!!.initialize()
        }
        if (connectedVideoView?.parent == null) {
            connectedContentContainer?.addView(connectedVideoView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT
            ))
        }
        connectedVideoView?.visibility = View.VISIBLE
        connectedVideoView?.let { v ->
            CallController.instance?.getPeerConnection()?.attachRemoteRenderer(v.remoteRenderer)
        }
        applyConnectedLocalVideoPreviewInMainLayout()
        connectedVideoView?.let { connectedContentContainer?.bringChildToFront(it) }
    }

    private fun applyConnectedLocalVideoPreviewInMainLayout() {
        val v = connectedVideoView ?: return
        if (!shouldShowRemoteVideo()) return
        val controller = CallController.instance ?: return
        val on = controller.isLocalVideoEnabled
        val pc = controller.getPeerConnection()
        if (on) {
            pc?.attachLocalRenderer(v.localRenderer)
            v.setLocalPreviewVisible(true)
        } else {
            pc?.detachLocalRenderer(v.localRenderer)
            v.setLocalPreviewVisible(false)
        }
    }

    private fun hideConnectedVideoView() {
        val v = connectedVideoView ?: return
        val pc = CallController.instance?.getPeerConnection()
        pc?.detachRemoteRenderer(v.remoteRenderer)
        pc?.detachLocalRenderer(v.localRenderer)
        v.visibility = View.GONE
    }

    private fun releaseConnectedVideoView() {
        connectedVideoView?.let {
            val pc = CallController.instance?.getPeerConnection()
            pc?.detachRemoteRenderer(it.remoteRenderer)
            pc?.detachLocalRenderer(it.localRenderer)
            it.release()
            connectedContentContainer?.removeView(it)
            connectedVideoView = null
        }
    }

    private fun hideConnectedLocalPip() {
        val pip = connectedLocalPip ?: return
        CallController.instance?.getPeerConnection()?.detachLocalRenderer(pip.renderer)
        pip.visibility = View.GONE
    }

    private fun bindConnectedLocalPip() {
        val controller = CallController.instance ?: return
        if (controller.callState !is CallState.Connected ||
            !controller.isLocalVideoEnabled ||
            controller.getPeerConnection() == null) {
            hideConnectedLocalPip()
            return
        }
        if (connectedLocalPip == null) {
            val pip = LocalCallVideoPip(this)
            pip.ensureInitialized()
            connectedLocalPip = pip
            connectedContentContainer?.addView(
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
        connectedLocalPip?.visibility = View.VISIBLE
        controller.getPeerConnection()?.attachLocalRenderer(connectedLocalPip!!.renderer)
        connectedLocalPip?.let { connectedContentContainer?.bringChildToFront(it) }
    }

    private fun releaseConnectedLocalPip() {
        val pip = connectedLocalPip ?: return
        CallController.instance?.getPeerConnection()?.detachLocalRenderer(pip.renderer)
        pip.safeRelease()
        connectedContentContainer?.removeView(pip)
        connectedLocalPip = null
    }

    private fun updateConnectedDurationPosition(onVideo: Boolean) {
        val view = connectedDurationView ?: return
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val statusBarDp = AndroidUtilities.statusBarHeight / AndroidUtilities.density
        lp.topMargin = if (onVideo) {
            LayoutHelper.dp(statusBarDp)
        } else {
            LayoutHelper.dp(statusBarDp + 56f)
        }
        view.layoutParams = lp
    }

    private fun declineCall() {
        if (dismissed) return
        dismissed = true

        statusView?.text = "Call ended"
        cancelDeferredIncomingRingtone()
        callAudioManager?.stopTone()
        handler.removeCallbacks(autoDeclineRunnable)
        handler.removeCallbacks(acceptTimeoutRunnable)

        ensureCallController()?.rejectCallFromIncomingCallUi(offerJson)

        MezonCallConnection.activeConnection?.let {
            it.setCallDisconnected(DisconnectCause.REJECTED)
        }

        CallNotificationManager(this).dismissIncomingNotification()
        finishCallActivity()
    }

    private fun finishCallActivity() {
        if (isFinishing) return
        dismissed = true
        cancelDeferredIncomingRingtone()
        try {
            CallNotificationManager(this).dismissIncomingNotification()
        } catch (_: Exception) {}
        finish()
    }

    override fun onDestroy() {
        lastConnectedMainVideoMode = null
        pendingConnectedVideoAfterCameraGrant = false
        pendingConnectedUnmuteMicAfterAudioGrant = false
        cancelDeferredIncomingRingtone()
        handler.removeCallbacks(autoDeclineRunnable)
        handler.removeCallbacks(acceptTimeoutRunnable)
        callAudioManager?.stopTone()
        callAudioManager?.stop()
        detachObservers()
        connectedDurationView?.stopTimer()
        releaseConnectedLocalPip()
        releaseConnectedVideoView()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CALL_PERMISSIONS = 9001
        private const val REQUEST_CONNECTED_VIDEO_CAMERA = 9105
        private const val REQUEST_CONNECTED_UNMUTE_MIC = 9106

        @Volatile
        private var incomingCallUiForeground = false

        @JvmStatic
        fun shouldSuppressMainTabsIncomingOverlay(): Boolean = incomingCallUiForeground

        internal fun setIncomingCallUiForeground(foreground: Boolean) {
            incomingCallUiForeground = foreground
        }
    }
}
