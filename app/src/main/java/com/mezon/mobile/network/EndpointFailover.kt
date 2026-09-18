package com.mezon.mobile.network

import android.os.SystemClock
import android.util.Log
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.session.SessionExpiredException
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.session.StoredSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private data class EndpointRefreshRequest(
    val endpoint: RealtimeEndpoint,
    val reason: HealthyEndpointReason
)

private enum class AskOutcome {
    DONE,
    RETRY
}

@Singleton
class EndpointFailover @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val networkMonitor: NetworkMonitor,
    private val mezonSocketLazy: dagger.Lazy<MezonSocket>,
    @ApplicationScope private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "EndpointFailover"
        private const val RETRY_BASE_MS = 5_000L
        private const val RETRY_CAP_MS = 60_000L
    }

    private val enabled = BuildConfig.MEZON_ENDPOINT_FAILOVER
    private val slowSwitchEnabled = BuildConfig.MEZON_ENDPOINT_FAILOVER_SLOW
    private val health = EndpointHealth()
    private val requests = Channel<EndpointRefreshRequest>(Channel.CONFLATED)

    @Volatile
    private var retryMs = RETRY_BASE_MS

    @Volatile
    private var lastAskAtMs = 0L

    @Volatile
    private var routeMissing = false

    init {
        if (enabled) scope.launch { runLoop() }
    }

    fun onConnected(endpoint: RealtimeEndpoint?) {
        if (!enabled) return
        health.setEndpoint(endpoint)
        if (endpoint == null) return
        health.recordConnected(SystemClock.elapsedRealtime())
        retryMs = RETRY_BASE_MS
        lastAskAtMs = 0L
    }

    fun onDisconnected() {
        if (!enabled) return
        health.recordDisconnected()
    }

    fun onProbeRtt(rttMs: Long) {
        if (!enabled || !slowSwitchEnabled) return
        val endpoint = health.connectedEndpoint() ?: return
        if (!health.recordActiveProbe(rttMs, SystemClock.elapsedRealtime())) return
        Log.i(
            TAG,
            "${endpoint.label()} has been slow for ${EndpointHealth.SLOW_STREAK_REQUIRED} heartbeats, asking the gateway"
        )
        requests.trySend(EndpointRefreshRequest(endpoint, HealthyEndpointReason.HIGH_LATENCY))
    }

    fun onUnreachable(endpoint: RealtimeEndpoint?) {
        if (!enabled) return
        val target = endpoint ?: health.connectedEndpoint() ?: return
        health.recordDisconnected()
        Log.w(TAG, "${target.label()} is not answering, asking the gateway for a node")
        requests.trySend(EndpointRefreshRequest(target, HealthyEndpointReason.UNREACHABLE))
    }

    fun reset() {
        if (!enabled) return
        health.setEndpoint(null)
        while (requests.tryReceive().isSuccess) Unit
        retryMs = RETRY_BASE_MS
        lastAskAtMs = 0L
    }

    private fun socket(): MezonSocket = mezonSocketLazy.get()

    private fun stillAimedAt(endpoint: RealtimeEndpoint): Boolean =
        socket().targetEndpoint()?.isSameNode(endpoint) == true

    private fun recoveredOnItsOwn(request: EndpointRefreshRequest): Boolean =
        request.reason == HealthyEndpointReason.UNREACHABLE &&
            socket().connectionState.value == ConnectionState.CONNECTED

    private suspend fun runLoop() {
        var pending: EndpointRefreshRequest? = null
        while (true) {
            pending = try {
                runStep(pending)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Endpoint failover loop recovered from an error", e)
                null
            }
        }
    }

    private suspend fun runStep(carried: EndpointRefreshRequest?): EndpointRefreshRequest? {
        val request = carried ?: requests.receive()

        if (routeMissing) return null
        if (!stillAimedAt(request.endpoint)) return null

        if (!networkMonitor.isOnline.value) {
            networkMonitor.isOnline.first { it }
        }

        val waitMs = if (lastAskAtMs == 0L) {
            RETRY_BASE_MS
        } else {
            retryMs - (SystemClock.elapsedRealtime() - lastAskAtMs)
        }
        if (waitMs > 0) delay(waitMs)

        if (!stillAimedAt(request.endpoint)) return null
        if (recoveredOnItsOwn(request)) {
            Log.i(TAG, "${request.endpoint.label()} answered again before we asked, dropping the report")
            return null
        }

        lastAskAtMs = SystemClock.elapsedRealtime()
        val outcome = ask(request)
        retryMs = nextRetryMs()
        return if (outcome == AskOutcome.RETRY) request else null
    }

    private fun nextRetryMs(): Long = doubledBackoffMs(retryMs, RETRY_CAP_MS)

    private suspend fun ask(request: EndpointRefreshRequest): AskOutcome {
        val session = try {
            sessionManager.requireValidSession()
        } catch (e: SessionExpiredException) {
            Log.w(TAG, "No live session to ask the gateway with", e)
            return AskOutcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the session before asking the gateway", e)
            return AskOutcome.RETRY
        }

        val response = try {
            api.getHealthyEndpoint(session.token, request.endpoint.id, request.reason.code)
        } catch (e: HealthyEndpointStatusException) {
            when (e.code) {
                404 -> {
                    routeMissing = true
                    Log.i(TAG, "Gateway has no healthy endpoint route, failover stays off")
                    return AskOutcome.DONE
                }
                401, 403 -> null
                else -> {
                    Log.w(TAG, "Healthy endpoint request failed: ${e.message}")
                    return AskOutcome.RETRY
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Healthy endpoint request failed", e)
            return AskOutcome.RETRY
        }

        if (response == null) return askWithFreshToken(request)
        return adopt(request, session, response)
    }

    private suspend fun askWithFreshToken(request: EndpointRefreshRequest): AskOutcome {
        if (!sessionManager.mayRefresh()) {
            Log.w(TAG, "Gateway refused the token and SessionRefresh is on cooldown")
            return AskOutcome.RETRY
        }
        val refreshed = try {
            sessionManager.refresh()
        } catch (e: SessionExpiredException) {
            Log.w(TAG, "Gateway refused the token and the session is gone", e)
            return AskOutcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not renew the token for the gateway", e)
            return AskOutcome.RETRY
        }
        val response = try {
            api.getHealthyEndpoint(refreshed.token, request.endpoint.id, request.reason.code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Healthy endpoint request failed with a renewed token", e)
            return AskOutcome.RETRY
        }
        return adopt(request, refreshed, response)
    }

    private suspend fun adopt(
        request: EndpointRefreshRequest,
        current: StoredSession,
        response: HealthyEndpoint
    ): AskOutcome {
        val nextApiUrl = response.apiUrl.ifBlank { current.apiUrl }
        val nextWsUrl = response.wsUrl.ifBlank { current.wsUrl }
        val nextTcpUrl = response.tcpUrl.ifBlank { current.tcpUrl }
        val next = realtimeEndpointOf(nextTcpUrl, nextWsUrl) ?: return AskOutcome.RETRY

        if (!stillAimedAt(request.endpoint)) return AskOutcome.DONE

        if (next.isSameNode(request.endpoint)) {
            health.setEndpoint(next)
            if (request.reason == HealthyEndpointReason.HIGH_LATENCY) {
                health.disableSlowReports()
                Log.w(
                    TAG,
                    "Gateway kept us on ${request.endpoint.label()} after a slow report, staying put"
                )
            } else {
                Log.i(TAG, "Gateway kept us on ${request.endpoint.label()}, waiting out the backoff")
            }
            return AskOutcome.DONE
        }

        Log.i(TAG, "Gateway moved us off ${request.endpoint.label()} to ${next.label()}, reconnecting")
        val persisted = try {
            sessionManager.updateEndpoints(nextApiUrl, nextWsUrl, nextTcpUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist the node the gateway gave us", e)
            return AskOutcome.RETRY
        }
        if (!persisted) {
            Log.i(TAG, "Session was cleared while the gateway answered, dropping the move")
            return AskOutcome.DONE
        }
        health.setEndpoint(next)
        socket().reconnectForEndpointChange("gateway moved us off ${request.endpoint.label()}")
        return AskOutcome.DONE
    }
}
