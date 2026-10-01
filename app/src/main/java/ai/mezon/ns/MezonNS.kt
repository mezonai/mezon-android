package ai.mezon.ns

import android.content.Context
import java.nio.ByteBuffer

class MezonNS private constructor(private var handle: Long) : AutoCloseable {
    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_BYTES = 960

        init {
            System.loadLibrary("mezon_ns")
        }

        fun createFromAsset(context: Context): MezonNS? {
            val model = context.assets.open("mezon_ns_asym_babble.onnx").use { it.readBytes() }
            val handle = nativeCreateFromMemory(model, 15.0f)
            if (handle == 0L) return null
            return MezonNS(handle).apply {
                nativeSetNoiseGate(handle, true)
                nativeSetSuppressionIntensity(handle, 1.6f)
                nativeSetModelInputTargetDbfs(handle, -20.0f)
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
