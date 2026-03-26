package com.mezon.mobile.home.chat

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object ThumbnailCache {

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cache = object : LruCache<Long, Bitmap>(maxMemory / 8) {
        override fun sizeOf(key: Long, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = ThreadPoolExecutor(
        2, 4, 30, TimeUnit.SECONDS,
        LinkedBlockingQueue(128)
    ).also { it.allowCoreThreadTimeOut(true) }

    interface Callback {
        fun onThumbnailLoaded(id: Long, bitmap: Bitmap)
    }

    fun get(id: Long): Bitmap? = cache.get(id)

    fun load(
        resolver: ContentResolver,
        item: AttachmentPickerItem,
        callback: Callback
    ): Runnable? {
        val cached = cache.get(item.id)
        if (cached != null) {
            callback.onThumbnailLoaded(item.id, cached)
            return null
        }

        val task = Runnable {
            val bmp = decodeThumbnail(resolver, item) ?: return@Runnable
            cache.put(item.id, bmp)
            mainHandler.post { callback.onThumbnailLoaded(item.id, bmp) }
        }
        executor.execute(task)
        return task
    }

    fun cancel(task: Runnable?) {
        if (task != null) executor.remove(task)
    }

    private fun decodeThumbnail(resolver: ContentResolver, item: AttachmentPickerItem): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.loadThumbnail(item.uri, Size(THUMB_SIZE, THUMB_SIZE), null)
            } else {
                decodeLegacy(resolver, item)
            }
        } catch (_: Exception) {
            decodeFallback(item)
        }
    }

    @Suppress("DEPRECATION")
    private fun decodeLegacy(resolver: ContentResolver, item: AttachmentPickerItem): Bitmap? {
        return if (item.isVideo) {
            MediaStore.Video.Thumbnails.getThumbnail(
                resolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null
            )
        } else {
            MediaStore.Images.Thumbnails.getThumbnail(
                resolver, item.id, MediaStore.Images.Thumbnails.MINI_KIND, null
            )
        }
    }

    private fun decodeFallback(item: AttachmentPickerItem): Bitmap? {
        if (item.path.isEmpty()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(item.path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        var sample = 1
        val w = opts.outWidth
        val h = opts.outHeight
        while (w / (sample * 2) >= THUMB_SIZE && h / (sample * 2) >= THUMB_SIZE) {
            sample *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(item.path, decodeOpts)
    }

    fun trimMemory(level: Int) {
        when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> cache.evictAll()
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> cache.trimToSize(cache.maxSize() / 4)
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> cache.trimToSize(cache.maxSize() / 2)
        }
    }

    fun evictAll() {
        cache.evictAll()
    }

    private const val THUMB_SIZE = 256
}
