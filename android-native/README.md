Implement the Kotlin/Java Bridge

```
package com.mezon

class MezonClient {
    // This stores the raw pointer to mezon_session_t as a Long
    private var sessionPtr: Long = 0

    companion object {
        init {
            // Must match the name in CMakeLists.txt: add_library(mezon_jni ...)
            System.loadLibrary("mezon_jni")
        }
    }

    fun connect(host: String, port: Int) {
        if (sessionPtr == 0L) {
            sessionPtr = nativeConnect(host, port)
        }
    }

    fun send(data: ByteArray): Int {
        if (sessionPtr == 0L) return -1
        return nativeSend(sessionPtr, data)
    }

    fun close() {
        if (sessionPtr != 0L) {
            nativeDestroy(sessionPtr)
            sessionPtr = 0L
        }
    }

    // Native Method Definitions
    private external fun nativeConnect(host: String, port: Int): Long
    private external fun nativeSend(ptr: Long, data: ByteArray): Int
    private external fun nativeDestroy(ptr: Long)
}
```