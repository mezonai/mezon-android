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
import android.graphics.Matrix
import android.media.ExifInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.Locale

class MezonImageLoader private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val totalCacheSize = maxMemory / 6

    private val largeCache = object : LruCache<String, Bitmap>(totalCacheSize * 4 / 5) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }

    private val smallCache = object : LruCache<String, Bitmap>(totalCacheSize / 5) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }

    private val diskCacheDir: File = File(context.cacheDir, "img_cache").also { it.mkdirs() }
    private val maxDiskCacheBytes = 512L * 1024 * 1024

    private val avatarCacheDir: File = File(context.cacheDir, "avatar_cache").also { it.mkdirs() }
    private val maxAvatarDiskBytes = 256L * 1024 * 1024

    private val inflightUrlCalls = ConcurrentHashMap<String, Call>()
    private val pendingDecodes = ConcurrentHashMap<String, MutableList<PendingDecode>>()
    private val pendingCallbacks = ConcurrentHashMap<String, MutableList<LoadCallback>>()

    private data class LoadCallback(
        val onSuccess: (Any) -> Unit,
        val onError: ((Exception) -> Unit)?
    )

    private data class PendingDecode(
        val memKey: String,
        val reqWidth: Int,
        val reqHeight: Int,
        val animated: Boolean
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
        @Suppress("UNCHECKED_CAST")
        return loadInternal(url, reqWidth, reqHeight, animated = false,
            onSuccess = onSuccess as (Any) -> Unit, onError = onError)
    }

    fun loadDrawable(
        url: String,
        reqWidth: Int,
        reqHeight: Int,
        onSuccess: (Drawable) -> Unit,
        onError: ((Exception) -> Unit)? = null
    ): Cancellable {
        @Suppress("UNCHECKED_CAST")
        return loadInternal(url, reqWidth, reqHeight, animated = true,
            onSuccess = onSuccess as (Any) -> Unit, onError = onError)
    }

    private fun stableUrlForDiskAndMemory(fetchUrl: String): String {
        val marker = "/plain/"
        val p = fetchUrl.indexOf(marker)
        if (p >= 0) {
            val embedded = fetchUrl.substring(p + marker.length).substringBefore('@')
            return stripQueryAndFragment(embedded)
        }
        return when {
            fetchUrl.startsWith("https://cdn.mezon") -> stripQueryAndFragment(fetchUrl)
            fetchUrl.startsWith("https://profile.mezon") -> stripQueryAndFragment(fetchUrl)
            fetchUrl.startsWith("http://cdn.mezon") -> stripQueryAndFragment(fetchUrl)
            fetchUrl.startsWith("http://profile.mezon") -> stripQueryAndFragment(fetchUrl)
            else -> fetchUrl
        }
    }

    private fun stripQueryAndFragment(raw: String): String {
        if (raw.isEmpty()) return raw
        return raw.substringBefore('#').substringBefore('?')
    }

    private fun loadInternal(
        url: String,
        reqWidth: Int,
        reqHeight: Int,
        animated: Boolean,
        onSuccess: (Any) -> Unit,
        onError: ((Exception) -> Unit)?
    ): Cancellable {
        if (url.isEmpty() || !isValidHttpUrl(url)) {
            onError?.invoke(IllegalArgumentException("Invalid URL: $url"))
            return Cancellable.EMPTY
        }

        val logicalUrl = stableUrlForDiskAndMemory(url)
        val memKey = cacheKey(logicalUrl, reqWidth, reqHeight)

        if (!animated) {
            getFromMemory(memKey)?.let { cached ->
                runOnMain { onSuccess(cached) }
                return Cancellable.EMPTY
            }
        }

        val cb = LoadCallback(onSuccess, onError)

        if (addCallback(memKey, cb)) {
            return Cancellable { removePendingCallback(logicalUrl, memKey, cb) }
        }

        val urlFile = diskFileForUrl(logicalUrl)
        if (urlFile.exists() && urlFile.length() > 0L) {
            touchFile(urlFile)
            if (animated) decodeAnimatedInBackground(urlFile, memKey, reqWidth, reqHeight)
            else decodeInBackground(urlFile, memKey, reqWidth, reqHeight)
            return Cancellable { removePendingCallback(logicalUrl, memKey, cb) }
        }

        enqueueDecodeAfterDownload(logicalUrl, memKey, reqWidth, reqHeight, animated)
        ensureNetworkFetch(url, logicalUrl)
        return Cancellable { removePendingCallback(logicalUrl, memKey, cb) }
    }

    private fun enqueueDecodeAfterDownload(
        logicalUrl: String,
        memKey: String,
        reqWidth: Int,
        reqHeight: Int,
        animated: Boolean
    ) {
        val task = PendingDecode(memKey, reqWidth, reqHeight, animated)
        val list = pendingDecodes.getOrPut(logicalUrl) { mutableListOf() }
        synchronized(list) { list.add(task) }
    }

    private fun ensureNetworkFetch(fetchUrl: String, logicalUrl: String, attempt: Int = 0) {
        if (inflightUrlCalls.containsKey(logicalUrl)) return
        val request = Request.Builder().url(fetchUrl).build()
        val call = client.newCall(request)
        val prev = inflightUrlCalls.putIfAbsent(logicalUrl, call)
        if (prev != null) return

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                inflightUrlCalls.remove(logicalUrl, call)
                if (call.isCanceled()) return
                if (attempt < 2) {
                    mainHandler.postDelayed({
                        ensureNetworkFetch(fetchUrl, logicalUrl, attempt + 1)
                    }, 350L shl attempt)
                } else {
                    dispatchAllDecodeError(logicalUrl, e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                inflightUrlCalls.remove(logicalUrl, call)
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    if (attempt < 2 && (code == 408 || code == 429 || code >= 500)) {
                        mainHandler.postDelayed({
                            ensureNetworkFetch(fetchUrl, logicalUrl, attempt + 1)
                        }, 350L shl attempt)
                    } else {
                        dispatchAllDecodeError(logicalUrl, IOException("HTTP $code"))
                    }
                    return
                }
                try {
                    val urlFile = diskFileForUrl(logicalUrl)
                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(urlFile).use { output -> input.copyTo(output) }
                    } ?: throw IOException("Empty body")
                    response.close()
                    trimDiskCache()
                    dispatchAllDecodeSuccess(logicalUrl, urlFile)
                } catch (e: Exception) {
                    val ex = e as? Exception ?: Exception(e)
                    if (attempt < 2 && e is IOException) {
                        try {
                            diskFileForUrl(logicalUrl).delete()
                        } catch (_: Throwable) {
                        }
                        mainHandler.postDelayed({
                            ensureNetworkFetch(fetchUrl, logicalUrl, attempt + 1)
                        }, 350L shl attempt)
                    } else {
                        dispatchAllDecodeError(logicalUrl, ex)
                    }
                }
            }
        })
    }

    private fun dispatchAllDecodeSuccess(logicalUrl: String, file: File) {
        val list = pendingDecodes.remove(logicalUrl) ?: return
        val copy: List<PendingDecode>
        synchronized(list) { copy = ArrayList(list) }
        for (task in copy) {
            if (task.animated) decodeAnimatedInBackground(file, task.memKey, task.reqWidth, task.reqHeight)
            else decodeInBackground(file, task.memKey, task.reqWidth, task.reqHeight)
        }
    }

    private fun dispatchAllDecodeError(logicalUrl: String, error: Exception) {
        val list = pendingDecodes.remove(logicalUrl) ?: return
        val copy: List<PendingDecode>
        synchronized(list) { copy = ArrayList(list) }
        for (task in copy) dispatchError(task.memKey, error)
    }

    private fun removePendingCallback(logicalUrl: String, memKey: String, cb: LoadCallback) {
        val list = pendingCallbacks[memKey] ?: return
        val callbacksEmpty = synchronized(list) {
            list.remove(cb)
            if (list.isEmpty()) {
                pendingCallbacks.remove(memKey, list)
                true
            } else false
        }
        if (!callbacksEmpty) return
        val decodeList = pendingDecodes[logicalUrl] ?: return
        val decodeEmpty = synchronized(decodeList) {
            decodeList.removeAll { it.memKey == memKey }
            if (decodeList.isEmpty()) {
                pendingDecodes.remove(logicalUrl, decodeList)
                true
            } else false
        }
        if (decodeEmpty) {
            inflightUrlCalls.remove(logicalUrl)?.cancel()
        }
    }

    private fun dispatchSuccess(cacheKey: String, result: Any) {
        val callbacks = pendingCallbacks.remove(cacheKey) ?: return
        val copy: List<LoadCallback>
        synchronized(callbacks) { copy = ArrayList(callbacks) }
        runOnMain {
            for (cb in copy) cb.onSuccess(result)
        }
    }

    private fun dispatchError(cacheKey: String, error: Exception) {
        val callbacks = pendingCallbacks.remove(cacheKey) ?: return
        val copy: List<LoadCallback>
        synchronized(callbacks) { copy = ArrayList(callbacks) }
        runOnMain {
            for (cb in copy) cb.onError?.invoke(error)
        }
    }

    private fun decodeInBackground(file: File, cacheKey: String, reqWidth: Int, reqHeight: Int) {
        DECODE_EXECUTOR.execute {
            try {
                val opts = BitmapFactory.Options()
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeFile(file.absolutePath, opts)
                val mimeType = opts.outMimeType?.lowercase(Locale.US).orEmpty()
                opts.inSampleSize = calculateInSampleSize(opts, reqWidth, reqHeight)
                opts.inJustDecodeBounds = false
                val isSmallThumb = reqWidth <= SMALL_IMAGE_THRESHOLD && reqHeight <= SMALL_IMAGE_THRESHOLD
                opts.inPreferredConfig = if (isSmallThumb) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                var bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                if (bmp != null) {
                    bmp = clampBitmap(bmp, reqWidth, reqHeight)
                    bmp = applyExifRotation(file, bmp, mimeType)
                    putToMemory(cacheKey, bmp, reqWidth, reqHeight)
                    dispatchSuccess(cacheKey, bmp)
                } else {
                    try { file.delete() } catch (_: Throwable) {}
                    dispatchError(cacheKey, IOException("Decode failed"))
                }
            } catch (e: Exception) {
                dispatchError(cacheKey, e as? Exception ?: Exception(e))
            }
        }
    }

    private fun decodeAnimatedInBackground(file: File, cacheKey: String, reqWidth: Int = 0, reqHeight: Int = 0) {
        DECODE_EXECUTOR.execute {
            try {
                val targetW = if (reqWidth > 0) reqWidth else 800
                val targetH = if (reqHeight > 0) reqHeight else 800

                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
                val sampleSize = calculateInSampleSize(boundsOpts, targetW, targetH)
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                var firstFrame = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                if (firstFrame != null) {
                    firstFrame = clampBitmap(firstFrame, targetW, targetH)
                    putToMemory(cacheKey, firstFrame, targetW, targetH)
                }

                if (Build.VERSION.SDK_INT >= 28) {
                    val source = ImageDecoder.createSource(file)
                    val drawable = ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        if (reqWidth > 0 && reqHeight > 0) {
                            decoder.setTargetSize(reqWidth, reqHeight)
                        }
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
        return getFromMemory(cacheKey(stableUrlForDiskAndMemory(url), reqWidth, reqHeight))
    }

    fun cancelAll() {
        inflightUrlCalls.values.forEach { it.cancel() }
        inflightUrlCalls.clear()
        pendingDecodes.clear()
        pendingCallbacks.clear()
    }

    fun clearMemoryCache() {
        smallCache.evictAll()
        largeCache.evictAll()
    }

    fun removeFromCache(url: String, reqWidth: Int, reqHeight: Int) {
        val key = cacheKey(stableUrlForDiskAndMemory(url), reqWidth, reqHeight)
        smallCache.remove(key)
        largeCache.remove(key)
    }
    
    fun invalidateCachedLoad(url: String, reqWidth: Int, reqHeight: Int) {
        if (url.isEmpty()) return
        inflightUrlCalls.remove(url)?.cancel()
        removeFromCache(url, reqWidth, reqHeight)
        try {
            diskFileForUrl(url).takeIf { it.exists() }?.delete()
        } catch (_: Throwable) {
        }
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

    private fun diskFileForUrl(url: String): File {
        val dir = if (isAvatarUrl(url)) avatarCacheDir else diskCacheDir
        return File(dir, hashString(url))
    }

    private fun isAvatarUrl(url: String): Boolean {
        for (bucket in AVATAR_BUCKET_MARKERS) {
            if (url.contains(bucket)) return true
        }
        return false
    }

    private fun touchFile(file: File) {
        try { file.setLastModified(System.currentTimeMillis()) } catch (_: Throwable) {}
    }

    private fun hashString(value: String): String {
        val md = MD5_THREAD_LOCAL.get()!!
        md.reset()
        md.update(value.toByteArray())
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

    private fun trimDiskCache() {
        DECODE_EXECUTOR.execute {
            trimDir(diskCacheDir, maxDiskCacheBytes)
            trimDir(avatarCacheDir, maxAvatarDiskBytes)
        }
    }

    private fun trimDir(dir: File, maxBytes: Long) {
        try {
            val files = dir.listFiles() ?: return
            var totalSize = files.sumOf { it.length() }
            if (totalSize <= maxBytes) return
            val largeMin = LARGE_FILE_TRIM_PRIORITY_BYTES.toLong()
            val largeCandidates = files.filter { it.length() >= largeMin }.sortedBy { it.lastModified() }
            val smallCandidates = files.filter { it.length() < largeMin }.sortedBy { it.lastModified() }
            for (f in largeCandidates.asSequence() + smallCandidates.asSequence()) {
                if (totalSize <= maxBytes * 0.8) break
                val len = f.length()
                totalSize -= len
                try { f.delete() } catch (_: Throwable) {}
            }
        } catch (_: Exception) {}
    }

    fun loadFromUri(
        uri: android.net.Uri,
        reqWidth: Int,
        reqHeight: Int,
        onSuccess: (Bitmap) -> Unit,
        onError: ((Exception) -> Unit)? = null
    ): Cancellable {
        val cacheKey = cacheKey(uri.toString(), reqWidth, reqHeight)

        getFromMemory(cacheKey)?.let { cached ->
            runOnMain { onSuccess(cached) }
            return Cancellable.EMPTY
        }

        @Suppress("UNCHECKED_CAST")
        val cb = LoadCallback(onSuccess as (Any) -> Unit, onError)
        if (addCallback(cacheKey, cb)) {
            return Cancellable { removePendingCallback("", cacheKey, cb) }
        }

        DECODE_EXECUTOR.execute {
            try {
                val tmpFile = File(diskCacheDir, "uri_" + cacheKey)
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
                } ?: throw IOException("Cannot open URI: $uri")

                val opts = BitmapFactory.Options()
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeFile(tmpFile.absolutePath, opts)
                val mimeType = opts.outMimeType?.lowercase(Locale.US).orEmpty()
                opts.inSampleSize = calculateInSampleSize(opts, reqWidth, reqHeight)
                opts.inJustDecodeBounds = false
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888
                var bmp = BitmapFactory.decodeFile(tmpFile.absolutePath, opts)
                if (bmp != null) {
                    bmp = clampBitmap(bmp, reqWidth, reqHeight)
                    bmp = applyExifRotation(tmpFile, bmp, mimeType)
                    putToMemory(cacheKey, bmp, reqWidth, reqHeight)
                    dispatchSuccess(cacheKey, bmp)
                } else {
                    dispatchError(cacheKey, IOException("Decode failed for $uri"))
                }
            } catch (e: Exception) {
                dispatchError(cacheKey, e)
            }
        }
        return Cancellable { removePendingCallback("", cacheKey, cb) }
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
        private const val LARGE_FILE_TRIM_PRIORITY_BYTES = 384_000

        private val AVATAR_BUCKET_MARKERS = arrayOf(
            "/rs:fill:64:64:1/",
            "/rs:fill:96:96:1/",
            "/rs:fill:144:144:1/",
            "/rs:fill:192:192:1/",
            "/rs:fill:256:256:1/"
        )

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
                LinkedBlockingQueue(),
                ThreadPoolExecutor.CallerRunsPolicy()
            )
        }

        private fun isValidHttpUrl(url: String): Boolean {
            return url.startsWith("http://") || url.startsWith("https://")
        }

        private fun applyExifRotation(file: File, bmp: Bitmap, mimeType: String = ""): Bitmap {
            if (mimeType == "image/webp") {
                return bmp
            }
            try {
                val exif = ExifInterface(file.absolutePath)
                val orient = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                val matrix = Matrix()
                when (orient) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                        matrix.postRotate(180f); matrix.postScale(-1f, 1f)
                    }
                    ExifInterface.ORIENTATION_TRANSPOSE -> {
                        matrix.postRotate(90f); matrix.postScale(-1f, 1f)
                    }
                    ExifInterface.ORIENTATION_TRANSVERSE -> {
                        matrix.postRotate(270f); matrix.postScale(-1f, 1f)
                    }
                    else -> return bmp
                }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                if (rotated !== bmp) bmp.recycle()
                return rotated
            } catch (_: Throwable) {
                return bmp
            }
        }

        private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val photoW = options.outWidth.toFloat()
            val photoH = options.outHeight.toFloat()
            if (reqWidth <= 0 || reqHeight <= 0 || photoW <= 0f || photoH <= 0f) return 1

            val scaleFactor = if (photoW > photoH) {
                maxOf(photoW / reqWidth, photoH / reqHeight)
            } else {
                minOf(photoW / reqWidth, photoH / reqHeight)
            }
            if (scaleFactor <= 1.2f) return 1
            var sample = 1
            while (sample * 2 < scaleFactor) {
                sample *= 2
            }
            return sample
        }

        private fun clampBitmap(bmp: Bitmap, reqWidth: Int, reqHeight: Int): Bitmap {
            if (reqWidth <= 0 || reqHeight <= 0) return bmp
            if (bmp.width <= reqWidth + 20 && bmp.height <= reqHeight + 20) return bmp
            val scale = minOf(reqWidth.toFloat() / bmp.width, reqHeight.toFloat() / bmp.height)
            val dstW = (bmp.width * scale).toInt().coerceAtLeast(1)
            val dstH = (bmp.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, dstW, dstH, true)
            if (scaled !== bmp) bmp.recycle()
            return scaled
        }
    }
}
