package com.mezon.mobile.home.chat

import android.content.Context
import java.io.File

object PendingCameraCapture {

    data class Record(
        val filePath: String,
        val channelId: Long,
        val channelName: String,
        val clanId: Long,
        val channelType: Int
    )

    private const val PREFS_NAME = "mezon_pending_camera_capture"
    private const val KEY_FILE_PATH = "file_path"
    private const val KEY_CHANNEL_ID = "channel_id"
    private const val KEY_CHANNEL_NAME = "channel_name"
    private const val KEY_CLAN_ID = "clan_id"
    private const val KEY_CHANNEL_TYPE = "channel_type"
    private const val KEY_STARTED_AT = "started_at"

    private const val MAX_AGE_MS = 30 * 60 * 1000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun remember(
        context: Context,
        file: File,
        channelId: Long,
        channelName: String,
        clanId: Long,
        channelType: Int
    ) {
        prefs(context).edit()
            .putString(KEY_FILE_PATH, file.absolutePath)
            .putLong(KEY_CHANNEL_ID, channelId)
            .putString(KEY_CHANNEL_NAME, channelName)
            .putLong(KEY_CLAN_ID, clanId)
            .putInt(KEY_CHANNEL_TYPE, channelType)
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .commit()
    }

    fun peek(context: Context): Record? {
        val prefs = prefs(context)
        val path = prefs.getString(KEY_FILE_PATH, null) ?: return null
        val age = System.currentTimeMillis() - prefs.getLong(KEY_STARTED_AT, 0L)
        if (age < 0L || age > MAX_AGE_MS) return null
        if (!File(path).isFile) return null
        return Record(
            filePath = path,
            channelId = prefs.getLong(KEY_CHANNEL_ID, 0L),
            channelName = prefs.getString(KEY_CHANNEL_NAME, "").orEmpty(),
            clanId = prefs.getLong(KEY_CLAN_ID, 0L),
            channelType = prefs.getInt(KEY_CHANNEL_TYPE, 0)
        )
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }

    fun sweepOrphans(context: Context) {
        val keepPath = peek(context)?.filePath
        if (keepPath == null) clear(context)
        val files = File(context.cacheDir, CameraPhotoCapture.CACHE_DIRECTORY).listFiles() ?: return
        for (file in files) {
            if (file.absolutePath == keepPath) continue
            file.delete()
        }
    }
}
