package com.mezon.mobile.home.chat

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class MezonImageLoader private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val totalCacheSize = maxMemory / 6

    private val largeCache = object : LruCache<String, Bitmap>(totalCacheSize * 4 / 5) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }

    private val smallCache = object : LruCache<String, Bitmap>(totalCacheSize / 5) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }

    private val diskCacheDir: File = File(context.cacheDir, "img_cache").also { it.mkdirs() }
    private val maxDiskCacheBytes = 50L * 1024 * 1024

    private val inflightCalls = ConcurrentHashMap<String, Call>()
    private val pendingCallbacks = ConcurrentHashMap<String, MutableList<LoadCallback>>()

    private data class LoadCallback(
        val onSuccess: (Any) -> Unit,
        val onError: ((Exception) -> Unit)?
    )

    init {
        appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                when {
                    level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                        smallCache.evictAll()
                        largeCache.evictAll()
                    }
                    level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                        smallCache.trimToSize(smallCache.maxSize() / 4)
                        largeCache.trimToSize(largeCache.maxSize() / 4)
                    }
                    level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                        smallCache.trimToSize(smallCache.maxSize() / 2)
                        largeCache.trimToSize(largeCache.maxSize() / 2)
                    }
                }
            }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            override fun onLowMemory() {
                smallCache.evictAll()
                largeCache.evictAll()
            }
        })
    }

    private fun putToMemory(key: String, bmp: Bitmap, reqWidth: Int, reqHeight: Int) {
        if (reqWidth <= SMALL_IMAGE_THRESHOLD && reqHeight <= SMALL_IMAGE_THRESHOLD) {
            smallCache.put(key, bmp)
        } else {
            largeCache.put(key, bmp)
        }
    }

    private fun getFromMemory(key: String): Bitmap? {
        return smallCache.get(key) ?: largeCache.get(key)
    }

    private fun addCallback(cacheKey: String, cb: LoadCallback): Boolean {
        val existing = pendingCallbacks[cacheKey]
        if (existing != null) {
            synchronized(existing) { existing.add(cb) }
            return true
        }
        pendingCallbacks[cacheKey] = mutableListOf(cb)
        return false
    }

    fun load(
        url: String,
        reqWidth: Int,
        reqHeight: Int,
        onSuccess: (Bitmap) -> Unit,
        onError: ((Exception) -> Unit)? = null
    ): Cancellable {
        if (url.isEmpty()) {
            onError?.invoke(IllegalArgumentException("Empty URL"))
            return Cancellable.EMPTY
        }

        val cacheKey = cacheKey(url, reqWidth, reqHeight)

        getFromMemory(cacheKey)?.let { cached ->
            mainHandler.post { onSuccess(cached) }
            return Cancellable.EMPTY
        }

        @Suppress("UNCHECKED_CAST")
        val cb = LoadCallback(onSuccess as (Any) -> Unit, onError)

        if (addCallback(cacheKey, cb)) {
            return Cancellable { removePendingCallback(cacheKey, cb) }
        }

        val diskFile = diskFile(cacheKey)
        if (diskFile.exists()) {
            decodeInBackground(diskFile, cacheKey, reqWidth, reqHeight)
            return Cancellable { removePendingCallback(cacheKey, cb) }
        }

        fetchFromNetwork(url, cacheKey, reqWidth, reqHeight)
        return Cancellable { removePendingCallback(cacheKey, cb) }
    }

    fun loadDrawable(
        url: String,
        reqWidth: Int,
        reqHeight: Int,
        onSuccess: (Drawable) -> Unit,
        onError: ((Exception) -> Unit)? = null
    ): Cancellable {
        if (url.isEmpty()) {
            onError?.invoke(IllegalArgumentException("Empty URL"))
            return Cancellable.EMPTY
        }

        val cacheKey = cacheKey(url, reqWidth, reqHeight)

        @Suppress("UNCHECKED_CAST")
        val cb = LoadCallback(onSuccess as (Any) -> Unit, onError)

        if (addCallback(cacheKey, cb)) {
            return Cancellable { removePendingCallback(cacheKey, cb) }
        }

        val diskFile = diskFile(cacheKey)
        if (diskFile.exists()) {
            decodeAnimatedInBackground(diskFile, cacheKey)
            return Cancellable { removePendingCallback(cacheKey, cb) }
        }

        val request = Request.Builder().url(url).build()
        val call = client.newCall(request)
        inflightCalls[cacheKey] = call

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                inflightCalls.remove(cacheKey)
                if (!call.isCanceled()) dispatchError(cacheKey, e)
            }

            override fun onResponse(call: Call, response: Response) {
                inflightCalls.remove(cacheKey)
                if (!response.isSuccessful) {
                    dispatchError(cacheKey, IOException("HTTP ${response.code}"))
                    response.close()
                    return
                }
                try {
                    val bytes = response.body?.bytes() ?: throw IOException("Empty body")
                    response.close()
                    FileOutputStream(diskFile).use { it.write(bytes) }
                    trimDiskCache()
                    decodeAnimatedInBackground(diskFile, cacheKey)
                } catch (e: Exception) {
                    dispatchError(cacheKey, e as? Exception ?: Exception(e))
                }
            }
        })
        return Cancellable { removePendingCallback(cacheKey, cb) }
    }

    private fun removePendingCallback(cacheKey: String, cb: LoadCallback) {
        val list = pendingCallbacks[cacheKey] ?: return
        synchronized(list) { list.remove(cb) }
        if (list.isEmpty()) {
            pendingCallbacks.remove(cacheKey)
            inflightCalls.remove(cacheKey)?.cancel()
        }
    }

    private fun dispatchSuccess(cacheKey: String, result: Any) {
        val callbacks = pendingCallbacks.remove(cacheKey) ?: return
        val copy: List<LoadCallback>
        synchronized(callbacks) { copy = ArrayList(callbacks) }
        mainHandler.post {
            for (cb in copy) cb.onSuccess(result)
        }
    }

    private fun dispatchError(cacheKey: String, error: Exception) {
        val callbacks = pendingCallbacks.remove(cacheKey) ?: return
        val copy: List<LoadCallback>
        synchronized(callbacks) { copy = ArrayList(callbacks) }
        mainHandler.post {
            for (cb in copy) cb.onError?.invoke(error)
        }
    }

    private fun fetchFromNetwork(
        url: String,
        cacheKey: String,
        reqWidth: Int,
        reqHeight: Int
    ) {
        val request = Request.Builder().url(url).build()
        val call = client.newCall(request)
        inflightCalls[cacheKey] = call

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                inflightCalls.remove(cacheKey)
                if (!call.isCanceled()) dispatchError(cacheKey, e)
            }

            override fun onResponse(call: Call, response: Response) {
                inflightCalls.remove(cacheKey)
                if (!response.isSuccessful) {
                    dispatchError(cacheKey, IOException("HTTP ${response.code}"))
                    response.close()
                    return
                }

                try {
                    val bytes = response.body?.bytes() ?: throw IOException("Empty body")
                    response.close()

                    val diskFile = diskFile(cacheKey)
                    FileOutputStream(diskFile).use { it.write(bytes) }
                    trimDiskCache()

                    val opts = BitmapFactory.Options()
                    opts.inJustDecodeBounds = true
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    opts.inSampleSize = calculateInSampleSize(opts, reqWidth, reqHeight)
                    opts.inJustDecodeBounds = false
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888

                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    if (bmp != null) {
                        putToMemory(cacheKey, bmp, reqWidth, reqHeight)
                        dispatchSuccess(cacheKey, bmp)
                    } else {
                        dispatchError(cacheKey, IOException("Decode failed"))
                    }
                } catch (e: Exception) {
                    dispatchError(cacheKey, e as? Exception ?: Exception(e))
                }
            }
        })
    }

    private fun decodeInBackground(file: File, cacheKey: String, reqWidth: Int, reqHeight: Int) {
        DECODE_EXECUTOR.execute {
            try {
                val opts = BitmapFactory.Options()
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeFile(file.absolutePath, opts)
                opts.inSampleSize = calculateInSampleSize(opts, reqWidth, reqHeight)
                opts.inJustDecodeBounds = false
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888
                val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                if (bmp != null) {
                    putToMemory(cacheKey, bmp, reqWidth, reqHeight)
                    dispatchSuccess(cacheKey, bmp)
                } else {
                    file.delete()
                    dispatchError(cacheKey, IOException("Decode failed"))
                }
            } catch (e: Exception) {
                dispatchError(cacheKey, e as? Exception ?: Exception(e))
            }
        }
    }

    private fun decodeAnimatedInBackground(file: File, cacheKey: String) {
        DECODE_EXECUTOR.execute {
            try {
                // Decode first frame as Bitmap and cache it for instant placeholder on rebind
                val firstFrame = BitmapFactory.decodeFile(file.absolutePath)
                if (firstFrame != null) {
                    putToMemory(cacheKey, firstFrame, 800, 800)
                }

                if (Build.VERSION.SDK_INT >= 28) {
                    val source = ImageDecoder.createSource(file)
                    val drawable = ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    dispatchSuccess(cacheKey, drawable)
                } else {
                    if (firstFrame != null) {
                        val drawable = android.graphics.drawable.BitmapDrawable(null, firstFrame)
                        dispatchSuccess(cacheKey, drawable)
                    } else {
                        dispatchError(cacheKey, IOException("Decode failed"))
                    }
                }
            } catch (e: Exception) {
                dispatchError(cacheKey, e as? Exception ?: Exception(e))
            }
        }
    }

    fun getBitmapFromMemory(url: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return getFromMemory(cacheKey(url, reqWidth, reqHeight))
    }

    fun cancelAll() {
        inflightCalls.values.forEach { it.cancel() }
        inflightCalls.clear()
        pendingCallbacks.clear()
    }

    fun clearMemoryCache() {
        smallCache.evictAll()
        largeCache.evictAll()
    }

    private fun cacheKey(url: String, w: Int, h: Int): String {
        val md = MD5_THREAD_LOCAL.get()!!
        md.reset()
        md.update("$url|$w|$h".toByteArray())
        val digest = md.digest()
        val sb = HEX_SB_THREAD_LOCAL.get()!!
        sb.setLength(0)
        for (b in digest) {
            val i = b.toInt() and 0xFF
            sb.append(HEX_CHARS[i ushr 4])
            sb.append(HEX_CHARS[i and 0x0F])
        }
        return sb.toString()
    }

    private fun diskFile(cacheKey: String): File = File(diskCacheDir, cacheKey)

    private fun trimDiskCache() {
        DECODE_EXECUTOR.execute {
            try {
                val files = diskCacheDir.listFiles() ?: return@execute
                var totalSize = files.sumOf { it.length() }
                if (totalSize <= maxDiskCacheBytes) return@execute
                val sorted = files.sortedBy { it.lastModified() }
                for (f in sorted) {
                    if (totalSize <= maxDiskCacheBytes * 0.8) break
                    totalSize -= f.length()
                    f.delete()
                }
            } catch (_: Exception) {}
        }
    }

    fun interface Cancellable {
        fun cancel()
        companion object {
            val EMPTY = Cancellable {}
        }
    }

    companion object {
        @Volatile
        private var instance: MezonImageLoader? = null

        private const val SMALL_IMAGE_THRESHOLD = 100
        private const val MAX_DECODE_QUEUE = 64

        private val HEX_CHARS = "0123456789abcdef".toCharArray()

        private val MD5_THREAD_LOCAL = object : ThreadLocal<MessageDigest>() {
            override fun initialValue(): MessageDigest = MessageDigest.getInstance("MD5")
        }

        private val HEX_SB_THREAD_LOCAL = object : ThreadLocal<StringBuilder>() {
            override fun initialValue(): StringBuilder = StringBuilder(32)
        }

        fun getInstance(context: Context): MezonImageLoader {
            return instance ?: synchronized(this) {
                instance ?: MezonImageLoader(context.applicationContext).also { instance = it }
            }
        }

        private val DECODE_EXECUTOR: ThreadPoolExecutor = run {
            val cores = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(2)
            ThreadPoolExecutor(
                cores, cores,
                60L, TimeUnit.SECONDS,
                LinkedBlockingQueue(MAX_DECODE_QUEUE),
                ThreadPoolExecutor.DiscardOldestPolicy()
            )
        }

        private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val height = options.outHeight
            val width = options.outWidth
            var inSampleSize = 1
            if (reqWidth <= 0 || reqHeight <= 0) return 1
            if (height > reqHeight || width > reqWidth) {
                val halfH = height / 2
                val halfW = width / 2
                while (halfH / inSampleSize >= reqHeight && halfW / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize
        }
    }
}
