package com.mezon.mobile.home.call

import org.webrtc.EglBase

object EglBaseProvider {

    private var eglBase: EglBase? = null
    private var refCount = 0

    @Synchronized
    fun acquire(): EglBase.Context {
        if (eglBase == null) {
            eglBase = EglBase.create()
        }
        refCount++
        return eglBase!!.eglBaseContext
    }

    @Synchronized
    fun release() {
        refCount--
        if (refCount <= 0) {
            eglBase?.release()
            eglBase = null
            refCount = 0
        }
    }
}
