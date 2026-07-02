package com.mezon.mobile.home.clans.settings

import com.mezon.mobile.BuildConfig
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.util.AttachmentUploader
import com.mezon.mezon.rtapi.ClanUpdatedEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommunitySettingsController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val clansController: ClansController,
    private val cacheTracker: ApiCacheTracker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(CommunityUiState())
    val uiState: StateFlow<CommunityUiState> = _uiState.asStateFlow()

    private val serverStateByClan = HashMap<Long, CommunityClanState>()

    private fun communityCacheKey(clanId: Long) = apiCacheKey("communityClanDesc", clanId)

    private fun invalidateServerCache(clanId: Long) {
        synchronized(this) { serverStateByClan.remove(clanId) }
        cacheTracker.invalidate(communityCacheKey(clanId))
    }

    fun load(clanId: Long) {
        appScope.launch {
            _uiState.update { it.copy(mode = CommunityScreenMode.LOADING) }
            try {
                val cached = synchronized(this@CommunitySettingsController) { serverStateByClan[clanId] }
                val server = if (cached != null &&
                    cacheTracker.shouldCall(communityCacheKey(clanId)) == ApiCacheTracker.ShouldCall.SKIP
                ) {
                    cached
                } else {
                    val fetched = sessionManager.withAutoRefresh { session ->
                        withContext(ioDispatcher) {
                            val list = api.listClanDescs(session.apiUrl, session.token)
                            val desc = list.clandescList.firstOrNull { it.clanId == clanId }
                                ?: error("Clan not found in ListClanDescs")
                            CommunityClanState(
                                clanId = desc.clanId,
                                isCommunityEnabled = desc.isCommunity,
                                communityBannerUrl = desc.communityBanner,
                                about = desc.about,
                                description = desc.description,
                                shortUrl = desc.shortUrl,
                            )
                        }
                    }
                    synchronized(this@CommunitySettingsController) { serverStateByClan[clanId] = fetched }
                    cacheTracker.markCalled(communityCacheKey(clanId))
                    fetched
                }
                val draft = CommunityFormDraft(
                    about = server.about,
                    description = server.description,
                    shortUrl = server.shortUrl,
                    bannerPreviewUrl = server.communityBannerUrl.takeIf { it.isNotEmpty() },
                )
                _uiState.update {
                    it.copy(
                        mode = if (server.isCommunityEnabled)
                            CommunityScreenMode.ENABLED_EDITOR
                        else
                            CommunityScreenMode.LANDING,
                        server = server.copy(isLoading = false),
                        draft = draft,
                        initial = draft,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        mode = CommunityScreenMode.LANDING,
                        server = it.server.copy(isLoading = false, errorMessage = e.message),
                    )
                }
            }
        }
    }

    fun onTapEnableCommunity() {
        _uiState.update { it.copy(mode = CommunityScreenMode.ENABLE_FORM) }
    }

    fun onCancelEnableForm() {
        _uiState.update {
            it.copy(
                mode = CommunityScreenMode.LANDING,
                fieldErrors = CommunityFieldErrors(),
            )
        }
    }

    fun onAboutChanged(v: String) = updateDraft { copy(about = v) }
    fun onDescriptionChanged(v: String) = updateDraft { copy(description = v) }
    fun onShortUrlChanged(raw: String) = updateDraft { copy(shortUrl = sanitizeVanity(raw)) }

    fun onBannerPicked(bytes: ByteArray, mime: String, ext: String, previewUrl: String?) {
        updateDraft {
            copy(
                pendingBannerBytes = bytes,
                pendingBannerMime = mime,
                pendingBannerExt = ext,
                bannerPreviewUrl = previewUrl,
            )
        }
    }

    private fun updateDraft(block: CommunityFormDraft.() -> CommunityFormDraft) {
        _uiState.update { state ->
            val newDraft = state.draft.block()
            val e = state.fieldErrors
            val bannerOk = newDraft.pendingBannerBytes != null || !newDraft.bannerPreviewUrl.isNullOrBlank()
            val newErrors = e.copy(
                about = if (e.about && newDraft.about.trim().isNotEmpty()) false else e.about,
                description = if (e.description && newDraft.description.trim().isNotEmpty()) false else e.description,
                shortUrl = if (e.shortUrl && newDraft.shortUrl.trim().isNotEmpty()) false else e.shortUrl,
                banner = if (e.banner && bannerOk) false else e.banner,
            )
            state.copy(
                draft = newDraft,
                fieldErrors = newErrors,
                showSaveBar = state.server.isCommunityEnabled &&
                    newDraft.hasChangesComparedTo(state.initial),
            )
        }
    }

    private fun validateForSubmit(draft: CommunityFormDraft): CommunityFieldErrors {
        val bannerOk = draft.pendingBannerBytes != null || !draft.bannerPreviewUrl.isNullOrBlank()
        return CommunityFieldErrors(
            banner = !bannerOk,
            about = draft.about.trim().isEmpty(),
            description = draft.description.trim().isEmpty(),
            shortUrl = draft.shortUrl.trim().isEmpty(),
        )
    }

    fun confirmEnableAndSave(clanId: Long, onDone: (success: Boolean, message: String?) -> Unit) {
        val state = _uiState.value
        val errors = validateForSubmit(state.draft)
        if (errors != CommunityFieldErrors()) {
            _uiState.update { it.copy(fieldErrors = errors) }
            appScope.launch(Dispatchers.Main.immediate) { onDone(false, null) }
            return
        }
        appScope.launch {
            _uiState.update { it.copy(server = it.server.copy(isSaving = true)) }
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.updateClanDesc(session.apiUrl, session.token, clanId, isCommunity = true)
                        var bannerUrl = state.draft.bannerPreviewUrl.orEmpty()
                        val bytes = state.draft.pendingBannerBytes
                        if (bytes != null) {
                            bannerUrl = uploadCommunityBanner(
                                api, session.apiUrl, session.token, clanId, bytes,
                                state.draft.pendingBannerMime ?: "image/jpeg",
                                state.draft.pendingBannerExt,
                            )
                            api.updateClanDesc(session.apiUrl, session.token, clanId, communityBanner = bannerUrl)
                        }
                        val about = state.draft.about.trim()
                        val desc = state.draft.description.trim()
                        val slug = state.draft.shortUrl.trim()
                        if (about.isNotEmpty()) {
                            api.updateClanDesc(session.apiUrl, session.token, clanId, about = about)
                        }
                        if (desc.isNotEmpty()) {
                            api.updateClanDesc(session.apiUrl, session.token, clanId, description = desc)
                        }
                        if (slug.isNotEmpty()) {
                            api.updateClanDesc(session.apiUrl, session.token, clanId, shortUrl = slug, isCommunity = true)
                        }
                    }
                }
                invalidateServerCache(clanId)
                clansController.mergeCommunityFlag(clanId, enabled = true)
                val newDraft = state.draft.copy(pendingBannerBytes = null)
                _uiState.update {
                    it.copy(
                        mode = CommunityScreenMode.ENABLED_EDITOR,
                        server = it.server.copy(isCommunityEnabled = true, isSaving = false),
                        draft = newDraft,
                        initial = newDraft,
                        showSaveBar = false,
                        fieldErrors = CommunityFieldErrors(),
                    )
                }
                withContext(Dispatchers.Main.immediate) { onDone(true, null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(server = it.server.copy(isSaving = false)) }
                withContext(Dispatchers.Main.immediate) { onDone(false, e.message) }
            }
        }
    }

    fun saveChanges(clanId: Long, onDone: (Boolean, String?) -> Unit) {
        val state = _uiState.value
        val errors = validateForSubmit(state.draft)
        if (errors != CommunityFieldErrors()) {
            _uiState.update { it.copy(fieldErrors = errors) }
            appScope.launch(Dispatchers.Main.immediate) { onDone(false, null) }
            return
        }
        appScope.launch {
            _uiState.update { it.copy(server = it.server.copy(isSaving = true)) }
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        val d = state.draft
                        val i = state.initial
                        if (d.pendingBannerBytes != null) {
                            val bannerUrl = uploadCommunityBanner(
                                api, session.apiUrl, session.token, clanId,
                                d.pendingBannerBytes,
                                d.pendingBannerMime ?: "image/jpeg",
                                d.pendingBannerExt,
                            )
                            api.updateClanDesc(session.apiUrl, session.token, clanId, communityBanner = bannerUrl)
                        }
                        if (d.about.trim() != i.about.trim()) {
                            api.updateClanDesc(session.apiUrl, session.token, clanId, about = d.about.trim())
                        }
                        if (d.description.trim() != i.description.trim()) {
                            api.updateClanDesc(session.apiUrl, session.token, clanId, description = d.description.trim())
                        }
                        if (d.shortUrl.trim() != i.shortUrl.trim()) {
                            api.updateClanDesc(session.apiUrl, session.token, clanId, shortUrl = d.shortUrl.trim(), isCommunity = true)
                        }
                    }
                }
                invalidateServerCache(clanId)
                val newDraft = state.draft.copy(pendingBannerBytes = null)
                _uiState.update {
                    it.copy(
                        server = it.server.copy(isSaving = false),
                        draft = newDraft,
                        initial = newDraft,
                        showSaveBar = false,
                        fieldErrors = CommunityFieldErrors(),
                    )
                }
                withContext(Dispatchers.Main.immediate) { onDone(true, null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(server = it.server.copy(isSaving = false)) }
                withContext(Dispatchers.Main.immediate) { onDone(false, e.message) }
            }
        }
    }

    fun disableCommunity(clanId: Long, onDone: (Boolean, String?) -> Unit) {
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.updateClanDesc(session.apiUrl, session.token, clanId, isCommunity = false)
                    }
                }
                invalidateServerCache(clanId)
                clansController.mergeCommunityFlag(clanId, enabled = false)
                _uiState.update {
                    CommunityUiState(
                        mode = CommunityScreenMode.LANDING,
                        server = CommunityClanState(clanId = clanId),
                    )
                }
                withContext(Dispatchers.Main.immediate) { onDone(true, null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main.immediate) { onDone(false, e.message) }
            }
        }
    }

    fun saveAboutOnBlur(clanId: Long) {
        val state = _uiState.value
        if (!state.server.isCommunityEnabled) return
        val about = state.draft.about.trim()
        if (about == state.initial.about.trim()) return
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.updateClanDesc(session.apiUrl, session.token, clanId, about = about)
                    }
                }
                invalidateServerCache(clanId)
                _uiState.update {
                    val newInitial = it.initial.copy(about = it.draft.about)
                    it.copy(initial = newInitial, showSaveBar = it.draft.hasChangesComparedTo(newInitial))
                }
            } catch (_: Exception) { }
        }
    }

    fun saveDescriptionOnBlur(clanId: Long) {
        val state = _uiState.value
        if (!state.server.isCommunityEnabled) return
        val desc = state.draft.description.trim()
        if (desc == state.initial.description.trim()) return
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.updateClanDesc(session.apiUrl, session.token, clanId, description = desc)
                    }
                }
                invalidateServerCache(clanId)
                _uiState.update {
                    val newInitial = it.initial.copy(description = it.draft.description)
                    it.copy(initial = newInitial, showSaveBar = it.draft.hasChangesComparedTo(newInitial))
                }
            } catch (_: Exception) { }
        }
    }

    fun saveShortUrlOnBlur(clanId: Long) {
        val state = _uiState.value
        if (!state.server.isCommunityEnabled) return
        val slug = state.draft.shortUrl.trim()
        if (slug == state.initial.shortUrl.trim()) return
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.updateClanDesc(session.apiUrl, session.token, clanId, shortUrl = slug, isCommunity = true)
                    }
                }
                invalidateServerCache(clanId)
                _uiState.update {
                    val newInitial = it.initial.copy(shortUrl = it.draft.shortUrl)
                    it.copy(initial = newInitial, showSaveBar = it.draft.hasChangesComparedTo(newInitial))
                }
            } catch (_: Exception) { }
        }
    }

    fun removeBanner(clanId: Long) {
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.updateClanDesc(session.apiUrl, session.token, clanId, clearCommunityBanner = true)
                    }
                }
                invalidateServerCache(clanId)
                _uiState.update {
                    val newDraft = it.draft.copy(bannerPreviewUrl = null, pendingBannerBytes = null)
                    val newInitial = it.initial.copy(bannerPreviewUrl = null)
                    it.copy(draft = newDraft, initial = newInitial, showSaveBar = newDraft.hasChangesComparedTo(newInitial))
                }
            } catch (_: Exception) { }
        }
    }

    fun resetDraft() {
        _uiState.update { it.copy(draft = it.initial, showSaveBar = false) }
    }

    fun applyClanUpdatedEvent(event: ClanUpdatedEvent) {
        invalidateServerCache(event.clanId)
        if (event.clanId != _uiState.value.server.clanId) return
        _uiState.update { state ->
            val d = state.draft.copy(
                bannerPreviewUrl = event.communityBanner.ifEmpty { state.draft.bannerPreviewUrl },
                about = event.about.ifEmpty { state.draft.about },
                description = event.description.ifEmpty { state.draft.description },
            )
            state.copy(
                server = state.server.copy(isCommunityEnabled = event.isCommunity),
                draft = d,
            )
        }
    }
}

private suspend fun uploadCommunityBanner(
    api: MezonApi,
    apiUrl: String,
    token: String,
    clanId: Long,
    bytes: ByteArray,
    mimeType: String,
    ext: String,
): String {
    require(bytes.isNotEmpty())
    val filename = "community-banner/$clanId.$ext"
    return AttachmentUploader.uploadAttachmentBytes(
        api, apiUrl, token, filename, mimeType, bytes,
        cdnBaseUrl = BuildConfig.MEZON_BASE_IMG_URL,
    ).cdnUrl
}
