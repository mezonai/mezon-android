package ai.mezon.ns

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

class MezonNS private constructor(private var handle: Long) : AutoCloseable {
    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_BYTES = 960
        private const val MODEL_URL = "https://cdn.komu.vn/ns/mezon_ns_asym.onnx"
        private const val MODEL_FILE = "mezon_ns_asym.onnx"
        private val cacheLock = Any()
        private var refreshing = false
        private val refreshExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "mezon-ns-model").apply { isDaemon = true }
        }
        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
        private class CachedModel(val bytes: ByteArray, val etag: String?)

        init {
            System.loadLibrary("mezon_ns")
        }

        // Called on the model worker, never on the microphone callback.
        fun createFromCache(context: Context): MezonNS? {
            val directory = File(context.cacheDir, "mezon/ns")
            val cached = synchronized(cacheLock) { readCachedModel(directory) }
            val model = if (cached != null) {
                refreshModel(directory, cached)
                cached.bytes
            } else {
                downloadModel(directory, null).bytes
            }
            val handle = nativeCreateFromMemory(model, 15.0f)
            if (handle == 0L) return null
            return MezonNS(handle).apply {
                nativeSetNoiseGate(handle, false)
                nativeSetSuppressionIntensity(handle, 1.0f)
                nativeSetModelInputTargetDbfs(handle, -20.0f)
            }
        }

        private fun readCachedModel(directory: File): CachedModel? = runCatching {
            val metadata = Properties().apply {
                File(directory, "$MODEL_FILE.properties").inputStream().use { load(it) }
            }
            val bytes = File(directory, MODEL_FILE).readBytes()
            check(metadata.getProperty("sha256") == sha256(bytes))
            CachedModel(bytes, metadata.getProperty("etag"))
        }.getOrNull()

        private fun refreshModel(directory: File, cached: CachedModel) {
            synchronized(cacheLock) {
                if (refreshing) return
                refreshing = true
            }
            refreshExecutor.execute {
                try {
                    downloadModel(directory, cached)
                } catch (error: Exception) {
                    Log.w("MezonNS", "Model refresh failed; keeping cached model", error)
                } finally {
                    synchronized(cacheLock) { refreshing = false }
                }
            }
        }

        private fun downloadModel(directory: File, cached: CachedModel?): CachedModel {
            val request = Request.Builder().url(MODEL_URL).apply {
                cached?.etag?.let { header("If-None-Match", it) }
            }.build()
            return client.newCall(request).execute().use { response ->
                if (response.code == 304) return cached ?: error("Model cache is missing")
                check(response.isSuccessful) { "Model download failed (${response.code})" }
                val bytes = response.body?.bytes() ?: error("Model download is empty")
                check(validateModel(bytes)) { "Downloaded noise model is invalid" }
                val fresh = CachedModel(bytes, response.header("ETag"))
                val metadata = Properties().apply {
                    setProperty("sha256", sha256(bytes))
                    fresh.etag?.let { setProperty("etag", it) }
                }
                runCatching {
                    synchronized(cacheLock) {
                        check(directory.isDirectory || directory.mkdirs()) { "Cannot create model cache" }
                        writeAtomically(File(directory, MODEL_FILE), bytes)
                        val output = java.io.ByteArrayOutputStream()
                        metadata.store(output, null)
                        writeAtomically(File(directory, "$MODEL_FILE.properties"), output.toByteArray())
                    }
                }.onFailure { Log.w("MezonNS", "Cannot persist noise model cache", it) }
                fresh
            }
        }

        private fun validateModel(bytes: ByteArray): Boolean {
            val probe = nativeCreateFromMemory(bytes, 15.0f)
            if (probe == 0L) return false
            return try {
                nativeProcessDirect(probe, ByteBuffer.allocateDirect(FRAME_BYTES), FRAME_BYTES) == 0
            } finally {
                nativeDestroy(probe)
            }
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun writeAtomically(destination: File, bytes: ByteArray) {
            val temporary = File.createTempFile("mezon-ns-", ".part", destination.parentFile)
            try {
                temporary.outputStream().use { it.write(bytes); it.fd.sync() }
                check(temporary.renameTo(destination)) { "Cannot save model cache" }
            } finally {
                temporary.delete()
            }
        }

        @JvmStatic private external fun nativeCreateFromMemory(model: ByteArray, attenuationLimitDb: Float): Long
        @JvmStatic private external fun nativeProcessDirect(handle: Long, buffer: ByteBuffer, bytes: Int): Int
        @JvmStatic private external fun nativeSetNoiseGate(handle: Long, enabled: Boolean)
        @JvmStatic private external fun nativeSetSuppressionIntensity(handle: Long, gamma: Float)
        @JvmStatic private external fun nativeSetModelInputTargetDbfs(handle: Long, targetDbfs: Float)
        @JvmStatic private external fun nativeReset(handle: Long)
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }

    fun process(buffer: ByteBuffer, bytes: Int): Boolean =
        handle != 0L && nativeProcessDirect(handle, buffer, bytes) == 0

    fun reset() {
        if (handle != 0L) nativeReset(handle)
    }

    override fun close() {
        val old = handle
        handle = 0L
        if (old != 0L) nativeDestroy(old)
    }
}
