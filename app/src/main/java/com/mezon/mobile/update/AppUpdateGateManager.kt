package com.mezon.mobile.update

import android.app.Activity
import android.util.Log
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.core.ThemeColors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.di.MainDispatcher

@Singleton
class AppUpdateGateManager @Inject constructor(
    private val playStoreVersionChecker: PlayStoreVersionChecker,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher
) {

    fun checkAndShowIfNeeded(activity: Activity, playStoreUrl: String) {
        if (isFinishingOrDestroyed(activity)) return
        if (hasPromptedInProcess) return
        applicationScope.launch(ioDispatcher) {
            val result = try {
                playStoreVersionChecker.check(playStoreUrl)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "check", e)
                }
                null
            }
            withContext(mainDispatcher) {
                if (isFinishingOrDestroyed(activity)) return@withContext
                if (result == null) return@withContext
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "local=${result.local} remote=${result.remote} needsUpdate=${result.needsForceUpdate}")
                }
                if (!result.needsForceUpdate) return@withContext
                if (hasPromptedInProcess) return@withContext
                hasPromptedInProcess = true
                val themeColors = ThemeColors.instance
                AppUpdateGateBottomSheet(
                    activity,
                    themeColors,
                    result.remote,
                    playStoreUrl
                ).show()
            }
        }
    }

    private fun isFinishingOrDestroyed(activity: Activity): Boolean {
        if (activity.isFinishing) return true
        return if (android.os.Build.VERSION.SDK_INT >= 17) {
            activity.isDestroyed
        } else {
            false
        }
    }

    companion object {
        private const val TAG = "AppUpdateGate"
        @Volatile
        private var hasPromptedInProcess = false
    }
}
