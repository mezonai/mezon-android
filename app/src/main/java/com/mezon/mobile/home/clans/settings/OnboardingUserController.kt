package com.mezon.mobile.home.clans.settings

import android.content.Context
import android.util.Log
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.di.MainDispatcher
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.session.SessionManager
import com.mezon.mezon.api.OnboardingItem
import com.mezon.mezon.api.OnboardingSteps
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class OnboardingUserState(
    val clanId: Long = 0L,
    val isOnboardingEnabled: Boolean = false,
    val onboardingStep: Int = 0,
    val rules: List<OnboardingItem> = emptyList(),
    val questions: List<OnboardingItem> = emptyList(),
    val missions: List<OnboardingItem> = emptyList(),
    val missionDoneIndex: Int = 0,
    val keepAnswers: Map<String, List<Int>> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val totalMissions: Int get() = missions.size
    val isCompleted: Boolean get() = onboardingStep == 3
    val currentMission: OnboardingItem? get() = missions.getOrNull(missionDoneIndex)
}

@Singleton
class OnboardingUserController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val clansController: ClansController,
    private val dispatcher: SocketEventDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val TAG = "OnboardingUserController"
    private val PREFS_NAME = "onboarding_user_prefs"

    private val _uiState = MutableStateFlow(OnboardingUserState())
    val uiState: StateFlow<OnboardingUserState> = _uiState.asStateFlow()

    private val contentCache = mutableMapOf<Long, OnboardingByClan>()
    private val stepCache = mutableMapOf<Long, Int>()

    init {
        observeSocketEvents()
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    suspend fun loadOnboarding(clanId: Long, forceRefresh: Boolean = false) {
        if (clanId == 0L) return
        _uiState.update { it.copy(clanId = clanId, isLoading = true, error = null) }

        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId }
        val isEnabled = clan?.isOnboarding == true

        if (!isEnabled) {
            _uiState.update {
                it.copy(
                    isOnboardingEnabled = false,
                    isLoading = false,
                    onboardingStep = 0,
                    rules = emptyList(),
                    questions = emptyList(),
                    missions = emptyList()
                )
            }
            return
        }

        try {
            val cachedContent = contentCache[clanId]
            val grouped = if (!forceRefresh && cachedContent != null) {
                cachedContent
            } else {
                val response = sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.listOnboarding(session.apiUrl, session.token, clanId, guideType = 0, limit = 100)
                    }
                }
                val g = groupOnboardingItems(response.listOnboardingList)
                contentCache[clanId] = g
                g
            }

            val cachedStep = stepCache[clanId]
            val step = if (!forceRefresh && cachedStep != null) {
                cachedStep
            } else {
                val response = sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.listOnboardingStep(session.apiUrl, session.token, clanId)
                    }
                }
                val s = response.listOnboardingStepList.firstOrNull()?.onboardingStep ?: 0
                stepCache[clanId] = s
                s
            }

            val restoredAnswers = restoreAnswers(clanId, grouped.questions)

            val restoredProgress = getLocalMissionProgress(clanId)

            val finalStep = if (step == 3) 3 else {
                if (grouped.missions.isNotEmpty() && restoredProgress >= grouped.missions.size) {
                    appScope.launch { completeOnboardingStep(clanId) }
                    3
                } else {
                    step
                }
            }

            _uiState.update {
                it.copy(
                    isOnboardingEnabled = true,
                    isLoading = false,
                    onboardingStep = finalStep,
                    rules = grouped.rules,
                    questions = grouped.questions,
                    missions = grouped.missions,
                    missionDoneIndex = restoredProgress,
                    keepAnswers = restoredAnswers
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load onboarding for clan $clanId", e)
            _uiState.update { it.copy(isLoading = false, error = e.message ?: context.getString(com.mezon.mobile.R.string.common_failed_to_load)) }
        }
    }

    private fun getLocalMissionProgress(clanId: Long): Int {
        val userId = com.mezon.mobile.core.StartupCache.userId.toLongOrNull() ?: 0L
        return prefs.getInt("progress_${userId}_$clanId", 0)
    }

    private fun saveLocalMissionProgress(clanId: Long, progress: Int) {
        val userId = com.mezon.mobile.core.StartupCache.userId.toLongOrNull() ?: 0L
        prefs.edit().putInt("progress_${userId}_$clanId", progress).apply()
    }

    private fun restoreAnswers(clanId: Long, questions: List<OnboardingItem>): Map<String, List<Int>> {
        val userId = com.mezon.mobile.core.StartupCache.userId.toLongOrNull() ?: 0L
        val answersMap = mutableMapOf<String, List<Int>>()
        for (q in questions) {
            val key = "ans_${userId}_${clanId}_${q.id}"
            val savedStr = prefs.getString(key, null)
            if (!savedStr.isNullOrBlank()) {
                val list = savedStr.split(",").mapNotNull { it.toIntOrNull() }
                answersMap[q.id.toString()] = list
            }
        }
        return answersMap
    }

    fun selectAnswer(questionId: String, answerIndex: Int) {
        val currentState = _uiState.value
        val clanId = currentState.clanId
        if (clanId == 0L) return

        val currentList = currentState.keepAnswers[questionId]?.toMutableList() ?: mutableListOf()
        if (currentList.contains(answerIndex)) {
            currentList.remove(answerIndex)
        } else {
            currentList.add(answerIndex)
        }

        val updatedMap = currentState.keepAnswers.toMutableMap()
        updatedMap[questionId] = currentList

        _uiState.update { it.copy(keepAnswers = updatedMap) }

        val userId = com.mezon.mobile.core.StartupCache.userId.toLongOrNull() ?: 0L
        val key = "ans_${userId}_${clanId}_$questionId"
        if (currentList.isEmpty()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, currentList.joinToString(",")).apply()
        }
    }

    fun completeMission(clanId: Long, index: Int) {
        val currentState = _uiState.value
        if (clanId == 0L || currentState.clanId != clanId) return
        if (index != currentState.missionDoneIndex) return

        val nextIndex = index + 1
        saveLocalMissionProgress(clanId, nextIndex)

        if (nextIndex >= currentState.totalMissions) {
            _uiState.update { it.copy(onboardingStep = 3, missionDoneIndex = nextIndex) }
            appScope.launch {
                completeOnboardingStep(clanId)
            }
        } else {
            _uiState.update { it.copy(missionDoneIndex = nextIndex) }
        }
    }

    private suspend fun completeOnboardingStep(clanId: Long) {
        try {
            sessionManager.withAutoRefresh { session ->
                withContext(ioDispatcher) {
                    api.updateOnboardingStepByClanId(session.apiUrl, session.token, clanId, onboardingStep = 3)
                }
            }
            stepCache[clanId] = 3
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update onboarding step to completed for clan $clanId", e)
        }
    }

    private fun observeSocketEvents() {
        appScope.launch {
            dispatcher.clanUpdatedEvents.collect { event ->
                val current = _uiState.value
                if (current.clanId == event.clanId) {
                    _uiState.update { it.copy(isOnboardingEnabled = event.isOnboarding) }
                    if (!event.isOnboarding) {
                        contentCache.remove(event.clanId)
                        stepCache.remove(event.clanId)
                    }
                }
            }
        }
    }
}
