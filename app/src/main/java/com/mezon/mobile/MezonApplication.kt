package com.mezon.mobile

import android.app.Application
import com.mezon.mobile.home.chat.MezonImageLoader
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MezonApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        MezonImageLoader.getInstance(this)
    }
}
