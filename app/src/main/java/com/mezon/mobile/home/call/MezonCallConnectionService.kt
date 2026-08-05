package com.mezon.mobile.home.call

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log

private const val TAG = "MezonCallConnService"

class MezonCallConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val incomingCaller = request?.extras?.getString(CallManager.EXTRA_CALLER_NAME)
        val hasOffer = !request?.extras?.getString(CallManager.EXTRA_OFFER_JSON).isNullOrBlank()
        Log.d(TAG, "onCreateIncomingConnection: caller=$incomingCaller, hasOffer=$hasOffer, controllerState=${CallController.instance?.callState?.let { it::class.simpleName }}")
        val connection = MezonCallConnection(applicationContext)

        var isVideo = false
        request?.extras?.let { extras ->
            val callerName = extras.getString(CallManager.EXTRA_CALLER_NAME, "Unknown")
            val callerId = extras.getString(CallManager.EXTRA_CALLER_ID, "")
            isVideo = extras.getBoolean(CallManager.EXTRA_IS_VIDEO_CALL, false)
            connection.setCallerDisplayName(callerName, TelecomManager.PRESENTATION_ALLOWED)
            connection.setAddress(Uri.parse("tel:$callerId"), TelecomManager.PRESENTATION_ALLOWED)
            connection.putExtras(Bundle(extras))
            connection.incomingExtras = Bundle(extras)
        }

        connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED)
        connection.setConnectionCapabilities(connectionCapabilitiesFor(isVideo))
        connection.setAudioModeIsVoip(true)
        connection.setVideoState(videoStateFor(isVideo))
        connection.setRinging()
        silenceSystemIncomingRingtone(connection)

        CallTelecomBridge.from(applicationContext)?.attach(connection)

        return connection
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val connection = MezonCallConnection(applicationContext)

        val outgoingExtras = request?.extras?.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS)
        var isVideo = false
        outgoingExtras?.let { extras ->
            val peerName = extras.getString(CallManager.EXTRA_CALLER_NAME, "Unknown")
            isVideo = extras.getBoolean(CallManager.EXTRA_IS_VIDEO_CALL, false)
            connection.setCallerDisplayName(peerName, TelecomManager.PRESENTATION_ALLOWED)
        }
        request?.address?.let { connection.setAddress(it, TelecomManager.PRESENTATION_ALLOWED) }

        connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED)
        connection.setConnectionCapabilities(connectionCapabilitiesFor(isVideo))
        connection.setAudioModeIsVoip(true)
        connection.setVideoState(videoStateFor(isVideo))
        connection.setDialing()

        CallTelecomBridge.from(applicationContext)?.attach(connection)

        return connection
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.w(TAG, "onCreateOutgoingConnectionFailed account=$connectionManagerPhoneAccount")
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.w(
            TAG,
            "onCreateIncomingConnectionFailed account=$connectionManagerPhoneAccount keys=${request?.extras?.keySet()}"
        )
        val notifManager = CallNotificationManager(applicationContext)
        val extras = request?.extras
        val offerFromExtras = extras?.getString(CallManager.EXTRA_OFFER_JSON)?.trim()?.takeIf { it.isNotEmpty() }
        val offerFallback = offerFromExtras ?: run {
            try {
                applicationContext.getSharedPreferences(
                    "call_data",
                    android.content.Context.MODE_PRIVATE
                ).getString("incoming_call", null)
            } catch (_: Exception) {
                null
            }
        }
        notifManager.showIncomingCallNotification(
            callerName = extras?.getString(CallManager.EXTRA_CALLER_NAME, "Unknown") ?: "Unknown",
            callerAvatar = null,
            callerId = extras?.getString(CallManager.EXTRA_CALLER_ID, "") ?: "",
            channelId = extras?.getString(CallManager.EXTRA_CHANNEL_ID, "") ?: "",
            offerJson = offerFallback,
            useFullScreenIntent = true
        )
    }

    private fun videoStateFor(isVideo: Boolean): Int =
        if (isVideo) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY

    private fun connectionCapabilitiesFor(isVideo: Boolean): Int {
        var capabilities = Connection.CAPABILITY_MUTE
        if (isVideo) {
            capabilities = capabilities or
                Connection.CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL or
                Connection.CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL
        }
        return capabilities
    }

    private fun silenceSystemIncomingRingtone(connection: Connection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            Connection::class.java
                .getMethod("setSilentRingingRequested", Boolean::class.javaPrimitiveType)
                .invoke(connection, true)
        } catch (_: ReflectiveOperationException) {
        }
    }
}
