package com.mezon.mobile.home.clans

import android.graphics.Color
import android.util.Log
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RoleController"

@Singleton
class RoleController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val _rolesByClan = MutableStateFlow<Map<Long, List<ClanRole>>>(emptyMap())
    val rolesByClan: StateFlow<Map<Long, List<ClanRole>>> = _rolesByClan.asStateFlow()

    private val loadingClans = ConcurrentHashMap<Long, Boolean>()

    fun getRoles(clanId: Long): List<ClanRole> =
        _rolesByClan.value[clanId] ?: emptyList()

    fun cleanup() {
        _rolesByClan.value = emptyMap()
        loadingClans.clear()
    }

    fun loadRolesForClan(clanId: Long, force: Boolean = false) {
        if (clanId <= 0) return
        if (!force && loadingClans[clanId] == true) return
        if (!force && !_rolesByClan.value[clanId].isNullOrEmpty()) return
        loadingClans[clanId] = true
        appScope.launch(ioDispatcher) {
            try {
                val response = sessionManager.withAutoRefresh { session ->
                    api.listRoles(session.apiUrl, session.token, clanId)
                }
                val everyoneSlug = "everyone-$clanId"
                val roles = response.roles.rolesList
                    .asSequence()
                    .filter { it.slug != everyoneSlug }
                    .map { proto ->
                        ClanRole(
                            roleId = proto.id,
                            clanId = clanId,
                            title = proto.title,
                            color = parseHexColor(proto.color),
                            iconUrl = proto.roleIcon,
                            slug = proto.slug
                        )
                    }
                    .toList()
                val updated = _rolesByClan.value.toMutableMap().apply {
                    put(clanId, roles)
                }
                _rolesByClan.value = updated
                Log.d(TAG, "Loaded ${roles.size} roles for clan $clanId")
            } catch (e: Exception) {
                Log.e(TAG, "loadRolesForClan failed for clan $clanId", e)
            } finally {
                loadingClans[clanId] = false
            }
        }
    }

    private fun parseHexColor(raw: String): Int {
        if (raw.isBlank()) return 0
        val hex = if (raw.startsWith("#")) raw else "#$raw"
        return try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            0
        }
    }
}
