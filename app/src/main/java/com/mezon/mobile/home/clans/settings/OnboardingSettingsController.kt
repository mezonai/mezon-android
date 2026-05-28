package com.mezon.mobile.home.clans.settings

import com.mezon.mobile.BuildConfig
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import com.mezon.mezon.api.OnboardingContent
import com.mezon.mezon.api.OnboardingItem
import com.mezon.mezon.api.updateOnboardingRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ThreadLocalRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnboardingSettingsController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val clansController: ClansController,
    private val channelController: ChannelController,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(OnboardingSettingsUiState())
    val state: StateFlow<OnboardingSettingsUiState> = _state.asStateFlow()

    fun reset() {
        _state.value = OnboardingSettingsUiState()
    }

    fun open(clanId: Long) {
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId }
        _state.value = OnboardingSettingsUiState(
            clanId = clanId,
            isOnboardingEnabled = clan?.isOnboarding == true,
        )
        channelController.loadChannelsForClan(clanId, force = false)
        appScope.launch { load(clanId) }
    }

    fun setPage(page: OnboardingPage) {
        _state.update { it.copy(currentPage = page) }
    }

    fun setEnableSetupOpen(open: Boolean) {
        _state.update { it.copy(isEnableSetupOpen = open, showHighlightNeedItem = false) }
    }

    fun addQuestionDraft(draft: QuestionDraft) {
        _state.update { it.copy(draft = it.draft.copy(questions = it.draft.questions + draft)) }
    }

    fun addRuleDraft(draft: RuleDraft) {
        _state.update { it.copy(draft = it.draft.copy(rules = it.draft.rules + draft)) }
    }

    fun addMissionDraft(draft: MissionDraft) {
        _state.update { it.copy(draft = it.draft.copy(tasks = it.draft.tasks + draft)) }
    }

    fun removeDraftQuestion(localId: String) {
        _state.update {
            it.copy(draft = it.draft.copy(questions = it.draft.questions.filter { q -> q.localId != localId }))
        }
    }

    fun removeDraftRule(localId: String) {
        _state.update {
            it.copy(draft = it.draft.copy(rules = it.draft.rules.filter { r -> r.localId != localId }))
        }
    }

    fun removeDraftMission(localId: String) {
        _state.update {
            it.copy(draft = it.draft.copy(tasks = it.draft.tasks.filter { t -> t.localId != localId }))
        }
    }

    fun clearDraft() {
        _state.update { it.copy(draft = OnboardingFormDraft()) }
    }

    suspend fun load(clanId: Long) {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val response = sessionManager.withAutoRefresh { session ->
                withContext(ioDispatcher) {
                    api.listOnboarding(session.apiUrl, session.token, clanId, guideType = 0, limit = 100)
                }
            }
            val grouped = groupOnboardingItems(response.listOnboardingList)
            val clan = clansController.clans.value.firstOrNull { it.clanId == clanId }
            _state.update {
                it.copy(
                    isLoading = false,
                    onboardingByClan = grouped,
                    isOnboardingEnabled = clan?.isOnboarding == true,
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(isLoading = false, errorMessage = e.message) }
        }
    }

    suspend fun confirmEnableAndSave(): Result<Unit> {
        val s = _state.value
        if (!s.hasAtLeastOneItem) {
            _state.update { it.copy(showHighlightNeedItem = true) }
            return Result.failure(NeedAtLeastOneItemException())
        }
        return saveInternal(enableOnboarding = true, closeSetup = true)
    }

    suspend fun saveChanges(): Result<Unit> = saveInternal(enableOnboarding = null, closeSetup = false)

    suspend fun disableOnboarding(): Result<Unit> {
        val clanId = _state.value.clanId
        if (clanId == 0L) return Result.failure(IllegalStateException("no clan"))
        _state.update { it.copy(isSaving = true) }
        return try {
            sessionManager.withAutoRefresh { session ->
                withContext(ioDispatcher) {
                    api.updateClanDesc(
                        session.apiUrl,
                        session.token,
                        clanId,
                        isOnboarding = false,
                    )
                }
            }
            clansController.mergeClanOnboardingFlag(clanId, false)
            _state.update {
                it.copy(
                    isSaving = false,
                    isOnboardingEnabled = false,
                    isEnableSetupOpen = false,
                    currentPage = OnboardingPage.MAIN,
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            _state.update { it.copy(isSaving = false, errorMessage = e.message) }
            Result.failure(e)
        }
    }

    suspend fun deleteServerItem(item: OnboardingItem): Result<Unit> {
        val clanId = _state.value.clanId
        _state.update { it.copy(isSaving = true) }
        return try {
            sessionManager.withAutoRefresh { session ->
                withContext(ioDispatcher) {
                    api.deleteOnboarding(session.apiUrl, session.token, item.id, clanId)
                }
            }
            load(clanId)
            _state.update { it.copy(isSaving = false) }
            Result.success(Unit)
        } catch (e: Exception) {
            _state.update { it.copy(isSaving = false) }
            Result.failure(e)
        }
    }

    suspend fun updateServerQuestion(
        item: OnboardingItem,
        title: String,
        answerDrafts: List<OnboardingAnswerDraft>,
    ): Result<Unit> {
        val targetClanId = _state.value.clanId
        _state.update { it.copy(isSaving = true) }
        return try {
            sessionManager.withAutoRefresh { session ->
                withContext(ioDispatcher) {
                    val req = updateOnboardingRequest {
                        id = item.id
                        this.clanId = targetClanId
                        this.title = title.trim()
                        this.answers.clear()
                        this.answers.addAll(answerDrafts.map { it.toProto() })
                    }
                    api.updateOnboarding(session.apiUrl, session.token, req)
                }
            }
            load(targetClanId)
            _state.update { it.copy(isSaving = false) }
            Result.success(Unit)
        } catch (e: Exception) {
            _state.update { it.copy(isSaving = false) }
            Result.failure(e)
        }
    }

    suspend fun updateServerMission(item: OnboardingItem, title: String, content: String, channelId: Long, taskType: Int): Result<Unit> {
        val targetClanId = _state.value.clanId
        _state.update { it.copy(isSaving = true) }
        return try {
            sessionManager.withAutoRefresh { session ->
                withContext(ioDispatcher) {
                    val req = updateOnboardingRequest {
                        id = item.id
                        this.clanId = targetClanId
                        this.title = title.trim()
                        this.content = content.trim()
                        this.channelId = channelId
                        this.taskType = taskType
                    }
                    api.updateOnboarding(session.apiUrl, session.token, req)
                }
            }
            load(targetClanId)
            _state.update { it.copy(isSaving = false) }
            Result.success(Unit)
        } catch (e: Exception) {
            _state.update { it.copy(isSaving = false) }
            Result.failure(e)
        }
    }

    suspend fun updateServerRule(
        item: OnboardingItem,
        title: String,
        content: String,
        imageUrl: String?,
        localFilePath: String?,
    ): Result<Unit> {
        val targetClanId = _state.value.clanId
        _state.update { it.copy(isSaving = true) }
        return try {
            val resolvedUrl = if (!localFilePath.isNullOrBlank()) {
                uploadRuleImage(localFilePath)
            } else {
                imageUrl.orEmpty()
            }
            sessionManager.withAutoRefresh { session ->
                withContext(ioDispatcher) {
                    val req = updateOnboardingRequest {
                        id = item.id
                        this.clanId = targetClanId
                        this.title = title.trim()
                        this.content = content.trim()
                        if (resolvedUrl.isNotBlank()) this.imageUrl = resolvedUrl
                    }
                    api.updateOnboarding(session.apiUrl, session.token, req)
                }
            }
            load(targetClanId)
            _state.update { it.copy(isSaving = false) }
            Result.success(Unit)
        } catch (e: Exception) {
            _state.update { it.copy(isSaving = false) }
            Result.failure(e)
        }
    }

    fun publicChannelsForClan(clanId: Long) =
        channelController.getChannels(clanId).filter { !it.isPrivate }

    private suspend fun saveInternal(enableOnboarding: Boolean?, closeSetup: Boolean): Result<Unit> {
        val s = _state.value
        if (enableOnboarding == true && !s.hasAtLeastOneItem) {
            _state.update { it.copy(showHighlightNeedItem = true) }
            return Result.failure(NeedAtLeastOneItemException())
        }
        _state.update { it.copy(isSaving = true, showHighlightNeedItem = false) }
        return try {
            if (s.hasDraftData) {
                createBatchFromDraft(s)
            }
            if (enableOnboarding == true) {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.updateClanDesc(
                            session.apiUrl,
                            session.token,
                            s.clanId,
                            isOnboarding = true,
                        )
                    }
                }
                clansController.mergeClanOnboardingFlag(s.clanId, true)
            }
            load(s.clanId)
            _state.update {
                it.copy(
                    isSaving = false,
                    draft = OnboardingFormDraft(),
                    isOnboardingEnabled = enableOnboarding ?: it.isOnboardingEnabled,
                    isEnableSetupOpen = if (closeSetup) false else it.isEnableSetupOpen,
                    currentPage = OnboardingPage.MAIN,
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            _state.update { it.copy(isSaving = false, errorMessage = e.message) }
            Result.failure(e)
        }
    }

    private suspend fun createBatchFromDraft(s: OnboardingSettingsUiState) {
        val contents = mutableListOf<OnboardingContent>()
        for (q in s.draft.questions) {
            contents.add(q.toCreateContent())
        }
        for (rule in s.draft.rules) {
            val imageUrl = if (!rule.localFilePath.isNullOrBlank()) {
                uploadRuleImage(rule.localFilePath)
            } else {
                rule.imageUrl.orEmpty()
            }
            contents.add(rule.toCreateContent(imageUrl))
        }
        for (task in s.draft.tasks) {
            contents.add(task.toCreateContent())
        }
        if (contents.isEmpty()) return
        sessionManager.withAutoRefresh { session ->
            withContext(ioDispatcher) {
                api.createOnboarding(session.apiUrl, session.token, s.clanId, contents)
            }
        }
    }

    private suspend fun uploadRuleImage(localPath: String): String {
        val file = File(localPath)
        val bytes = file.readBytes()
        val id = ThreadLocalRandom.current().nextLong(10_000_000_000_000L, Long.MAX_VALUE / 4)
        val filename = "onboarding/$id.webp"
        return sessionManager.withAutoRefresh { session ->
            val presign = api.uploadAttachmentFile(
                session.apiUrl,
                session.token,
                filename,
                "image/webp",
                bytes.size,
                1,
                1,
            )
            api.putFileToPresignedUrl(presign.url, bytes, "image/webp")
            "${BuildConfig.MEZON_BASE_IMG_URL}/${presign.filename}"
        }
    }

    class NeedAtLeastOneItemException : Exception()
}
