package com.mezon.mobile.home.messages

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal object EmbedAnimationHttp {
    @Volatile
    private var appClient: OkHttpClient? = null

    private val fallbackClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun install(client: OkHttpClient) {
        appClient = client
    }

    fun client(): OkHttpClient = appClient ?: fallbackClient
}
