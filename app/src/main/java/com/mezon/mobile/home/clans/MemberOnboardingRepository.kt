package com.mezon.mobile.home.clans

import android.content.Context
import android.util.Log
import com.mezon.mezon.api.OnboardingItem
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemberOnboardingRepository @Inject constructor(
    private val mezonApi: MezonApi,
    private val sessionManager: SessionManager,
    private val notificationCenter: NotificationCenter,
    @ApplicationContext private val context: Context,
    @com.mezon.mobile.di.ApplicationScope private val appScope: CoroutineScope
) {
    // Cache for missions per clanId
    private val missionsCache = ConcurrentHashMap<Long, List<OnboardingItem>>()

    private val prefs by lazy {
        context.getSharedPreferences("member_onboarding_prefs", Context.MODE_PRIVATE)
    }

    private fun getDoneMissionKey(userId: String, clanId: Long) =
        "mezon.memberOnboarding.doneMission.$userId.$clanId"

    private fun getFullyDoneKey(userId: String, clanId: Long) =
        "mezon.memberOnboarding.fullyDone.$userId.$clanId"

    fun getDoneMissionsCount(clanId: Long): Int {
        val userId = StartupCache.userId
        if (userId.isEmpty()) return 0
        return prefs.getInt(getDoneMissionKey(userId, clanId), 0)
    }

    private fun setDoneMissionsCount(clanId: Long, userId: String, count: Int) {
        prefs.edit().putInt(getDoneMissionKey(userId, clanId), count).apply()
    }

    fun isFullyDone(clanId: Long): Boolean {
        val userId = StartupCache.userId
        if (userId.isEmpty()) return false
        return prefs.getBoolean(getFullyDoneKey(userId, clanId), false)
    }

    private fun setFullyDone(clanId: Long, userId: String, value: Boolean) {
        prefs.edit().putBoolean(getFullyDoneKey(userId, clanId), value).apply()
    }

    fun getMissions(clanId: Long): List<OnboardingItem> {
        return missionsCache[clanId] ?: emptyList()
    }

    fun isEligible(clanId: Long, creatorId: Long, isOnboarding: Boolean): Boolean {
        if (clanId == 0L) return false
        val currentUserId = StartupCache.userId.toLongOrNull() ?: 0L
        if (currentUserId == 0L || currentUserId == creatorId) return false // Owner not eligible for member flow
        if (!isOnboarding) return false
        if (isFullyDone(clanId)) return false
        val missions = getMissions(clanId)
        if (missions.isEmpty()) return false
        val completedSteps = getDoneMissionsCount(clanId)
        return completedSteps < missions.size
    }

    fun clearCache() {
        missionsCache.clear()
    }

    suspend fun fetchOnboardingData(clanId: Long) {
        if (clanId == 0L) return
        try {
            val session = sessionManager.requireValidSession()
            val userId = session.userId

            // 1. Fetch onboarding items
            val onboardingResponse = mezonApi.listOnboarding(session.apiUrl, session.token, clanId, limit = 100)
            val allMissions = onboardingResponse.listOnboardingList
                .filter { it.guideType == 2 || it.guideType == 3 }

            missionsCache[clanId] = allMissions

            // 2. Fetch server step progress
            val stepsResponse = mezonApi.listOnboardingStep(session.apiUrl, session.token, clanId)
            val currentUserId = userId.toLongOrNull() ?: 0L
            val maxServerStep = stepsResponse.listOnboardingStepList
                .filter { it.userId == currentUserId && it.clanId == clanId }
                .maxOfOrNull { it.onboardingStep } ?: 0

            // 3. Merge progress
            mergeProgress(clanId, userId, maxServerStep, allMissions.size)

            notificationCenter.postNotificationOnMainThread(NotificationCenter.memberOnboardingStateChanged)
        } catch (e: Exception) {
            Log.e("MemberOnboarding", "fetchOnboardingData failed for clan $clanId", e)
        }
    }

    private fun mergeProgress(clanId: Long, userId: String, serverStep: Int, missionCount: Int) {
        if (serverStep >= 3) {
            setFullyDone(clanId, userId, true)
            setDoneMissionsCount(clanId, userId, missionCount)
        } else {
            val localCount = prefs.getInt(getDoneMissionKey(userId, clanId), 0)
            val effectiveCount = java.lang.Math.max(localCount, serverStep)
            val doneCount = java.lang.Math.min(effectiveCount, missionCount)
            setDoneMissionsCount(clanId, userId, doneCount)

            // Sync back to server if local is ahead
            if (localCount > serverStep && localCount < missionCount) {
                syncStepToServer(clanId, localCount)
            }
            if (prefs.getBoolean(getFullyDoneKey(userId, clanId), false) && serverStep < 3) {
                syncStepToServer(clanId, 3)
            }
        }
    }

    fun completeTask(clanId: Long, index: Int) {
        val userId = StartupCache.userId
        if (userId.isEmpty()) return
        val missions = getMissions(clanId)
        if (index >= missions.size) return
        val currentDone = getDoneMissionsCount(clanId)
        if (index != currentDone) return // sequential enforcement

        val newCompleted = currentDone + 1
        setDoneMissionsCount(clanId, userId, newCompleted)

        val isDoneAll = newCompleted >= missions.size
        if (isDoneAll) {
            setFullyDone(clanId, userId, true)
        }

        val stepToSync = if (isDoneAll) 3 else newCompleted
        syncStepToServer(clanId, stepToSync)

        notificationCenter.postNotificationOnMainThread(NotificationCenter.memberOnboardingStateChanged)
    }

    fun completeVisitTask(clanId: Long, channelId: Long) {
        val missions = getMissions(clanId)
        val completedSteps = getDoneMissionsCount(clanId)
        if (completedSteps >= missions.size) return
        val currentMission = missions[completedSteps]
        if (currentMission.taskType == 2 && currentMission.channelId == channelId) {
            completeTask(clanId, completedSteps)
        }
    }

    fun completeMessageTask(clanId: Long, channelId: Long) {
        val missions = getMissions(clanId)
        val completedSteps = getDoneMissionsCount(clanId)
        if (completedSteps >= missions.size) return
        val currentMission = missions[completedSteps]
        if (currentMission.taskType == 1 && currentMission.channelId == channelId) {
            completeTask(clanId, completedSteps)
        }
    }

    private fun syncStepToServer(clanId: Long, step: Int) {
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    mezonApi.updateOnboardingStep(session.apiUrl, session.token, clanId, step)
                }
            } catch (e: Exception) {
                Log.e("MemberOnboarding", "syncStepToServer failed for clan $clanId step $step", e)
            }
        }
    }
}
