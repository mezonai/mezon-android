package com.mezon.mobile.home.call

import android.net.Uri
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

private const val TAG = "MezonCallConnService"

class MezonCallConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val incomingCaller = request?.extras?.getString(CallManager.EXTRA_CALLER_NAME)
        val hasOffer = request?.extras?.getString(CallManager.EXTRA_OFFER_JSON) != null
        Log.d(TAG, "onCreateIncomingConnection: caller=$incomingCaller, hasOffer=$hasOffer, controllerState=${CallController.instance?.callState?.let { it::class.simpleName }}")
        val connection = MezonCallConnection(applicationContext)

        request?.extras?.let { extras ->
            val callerName = extras.getString(CallManager.EXTRA_CALLER_NAME, "Unknown")
            val callerId = extras.getString(CallManager.EXTRA_CALLER_ID, "")
            connection.setCallerDisplayName(callerName, TelecomManager.PRESENTATION_ALLOWED)
            connection.setAddress(Uri.parse("tel:$callerId"), TelecomManager.PRESENTATION_ALLOWED)
            connection.extras = extras
            connection.incomingExtras = android.os.Bundle(extras)
        }

        connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED)
        connection.setConnectionCapabilities(
            Connection.CAPABILITY_SUPPORT_HOLD or
                Connection.CAPABILITY_HOLD or
                Connection.CAPABILITY_MUTE
        )
        connection.setAudioModeIsVoip(true)
        connection.setRinging()

        return connection
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val connection = MezonCallConnection(applicationContext)
        connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED)
        connection.setAudioModeIsVoip(true)
        connection.setDialing()
        return connection
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        val notifManager = CallNotificationManager(applicationContext)
        val extras = request?.extras
        notifManager.showIncomingCallNotification(
            callerName = extras?.getString(CallManager.EXTRA_CALLER_NAME, "Unknown") ?: "Unknown",
            callerAvatar = null,
            callerId = extras?.getString(CallManager.EXTRA_CALLER_ID, "") ?: "",
            channelId = extras?.getString(CallManager.EXTRA_CHANNEL_ID, "") ?: "",
            offerJson = extras?.getString(CallManager.EXTRA_OFFER_JSON),
            useFullScreenIntent = true
        )
    }
}
