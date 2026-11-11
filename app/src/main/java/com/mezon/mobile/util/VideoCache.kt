package com.mezon.mobile.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.mezon.mobile.network.CdnSigningDataSource
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
object VideoCache {
    private const val DIRECTORY = "video_cache"
    private const val MAX_BYTES = 256L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var instance: SimpleCache? = null

    internal fun cacheOrNull(context: Context): SimpleCache? =
        instance ?: synchronized(this) {
            instance ?: runCatching {
                val appContext = context.applicationContext
                SimpleCache(
                    File(appContext.cacheDir, DIRECTORY),
                    LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                    StandaloneDatabaseProvider(appContext),
                )
            }.getOrNull()?.also { instance = it }
        }

    fun dataSourceFactory(context: Context): DataSource.Factory {
        val appContext = context.applicationContext
        val upstream = upstreamDataSourceFactory(appContext)
        return DataSource.Factory {
            val source = upstream.createDataSource()
            val cache = cacheOrNull(appContext)
            if (cache == null) source else CacheDataSource(cache, source, CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }
    }

    internal fun upstreamDataSourceFactory(context: Context): DataSource.Factory =
        CdnSigningDataSource.Factory(DefaultDataSource.Factory(context.applicationContext))

    fun clear(context: Context) {
        val appContext = context.applicationContext
        VideoPrefetcher.reset()
        scope.launch {
            cacheOrNull(appContext)?.let { cache ->
                cache.keys.forEach { key -> runCatching { cache.removeResource(key) } }
            }
            VideoFrameStore.clear(appContext)
            VideoPlaybackDownload.clear(appContext)
        }
    }
}

object VideoFrameStore {
    private const val DIRECTORY = "video_frames"
    private const val MAX_BYTES = 32L * 1024 * 1024
    private const val TRIM_EVERY_WRITES = 16
    private const val JPEG_QUALITY = 85

    private val writesSinceTrim = AtomicInteger(0)

    fun load(context: Context, key: String): Bitmap? {
        val file = fileFor(context, key)
        if (!file.isFile) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
        if (bitmap == null) {
            runCatching { file.delete() }
            return null
        }
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return bitmap
    }

    fun save(context: Context, key: String, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        runCatching {
            val target = fileFor(context, key)
            val directory = target.parentFile ?: return@runCatching
            directory.mkdirs()
            val temp = File.createTempFile(target.name, ".tmp", directory)
            val written = runCatching {
                FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            }.getOrDefault(false)
            if (!written || !temp.renameTo(target)) temp.delete()
        }
        if (writesSinceTrim.incrementAndGet() >= TRIM_EVERY_WRITES) {
            writesSinceTrim.set(0)
            trim(context)
        }
    }

    internal fun clear(context: Context) {
        directory(context).listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun trim(context: Context) {
        runCatching {
            val files = directory(context).listFiles() ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_BYTES) return
            for (file in files.sortedBy { it.lastModified() }) {
                if (total <= MAX_BYTES * 8 / 10) break
                total -= file.length()
                runCatching { file.delete() }
            }
        }
    }

    private fun directory(context: Context): File = File(context.applicationContext.cacheDir, DIRECTORY)

    private fun fileFor(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return File(directory(context), name)
    }
}
