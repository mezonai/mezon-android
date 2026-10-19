package com.mezon.mobile.home.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R

class VoiceChannelForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val label = intent.getStringExtra(EXTRA_CHANNEL_LABEL).orEmpty()
        val screenShareReady = pendingScreenShareReady
        pendingScreenShareReady = null
        try {
            ensureChannel()
            startForegroundWithType(buildNotification(label))
            screenShareReady?.invoke(true)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            if (screenSharing) {
                screenSharing = false
                screenShareReady?.invoke(false)
                try {
                    startForegroundWithType(buildNotification(label))
                    return START_NOT_STICKY
                } catch (retry: Exception) {
                    Log.e(TAG, "startForeground without screen share failed", retry)
                }
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, serviceTypeMask())
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun serviceTypeMask(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return if (screenSharing) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
        }
        var mask = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (hasPermission(Manifest.permission.CAMERA)) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (screenSharing) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        return mask
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.voice_channel_ongoing_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.voice_channel_ongoing_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(label: String): Notification {
        val text = if (label.isBlank()) {
            getString(R.string.voice_channel_ongoing_text_generic)
        } else {
            getString(R.string.voice_channel_ongoing_text, label)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.voice_channel_ongoing_title))
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "VoiceChannelFgs"
        private const val CHANNEL_ID = "ongoing_voice_channel"
        private const val NOTIFICATION_ID = 9003
        private const val EXTRA_CHANNEL_LABEL = "voice_channel_label"

        private var channelLabel = ""
        private var screenSharing = false
        private var pendingScreenShareReady: ((Boolean) -> Unit)? = null

        fun start(context: Context, channelLabel: String) {
            this.channelLabel = channelLabel
            startService(context)
        }

        fun setScreenSharing(context: Context, sharing: Boolean, onReady: ((Boolean) -> Unit)? = null) {
            if (!sharing && !screenSharing) return
            screenSharing = sharing
            pendingScreenShareReady = onReady
            if (!startService(context)) {
                screenSharing = false
                pendingScreenShareReady = null
                onReady?.invoke(false)
            }
        }

        private fun startService(context: Context): Boolean =
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, VoiceChannelForegroundService::class.java).apply {
                        putExtra(EXTRA_CHANNEL_LABEL, channelLabel)
                    }
                )
                true
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService rejected", e)
                false
            }

        fun stop(context: Context) {
            screenSharing = false
            pendingScreenShareReady = null
            try {
                context.stopService(Intent(context, VoiceChannelForegroundService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
