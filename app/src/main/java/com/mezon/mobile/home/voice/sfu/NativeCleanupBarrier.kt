package com.mezon.mobile.home.voice.sfu

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Cleanup outlives its caller. A timeout/cancel stops waiting, never the native cleanup. */
internal class NativeCleanupBarrier(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val pending = ConcurrentHashMap.newKeySet<Job>()
    @Volatile private var failed = false

    fun enqueue(cleanup: () -> Unit): Job {
        val job = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            try {
                cleanup()
            } catch (error: Exception) {
                failed = true
                onFailure(error)
            }
        }
        pending.add(job)
        job.invokeOnCompletion { error ->
            if (error != null) failed = true
            pending.remove(job)
        }
        job.start()
        return job
    }

    suspend fun awaitCompletion(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val jobs = pending.toList()
            if (jobs.isEmpty()) return@withTimeoutOrNull !failed
            jobs.joinAll()
        }
        @Suppress("UNREACHABLE_CODE")
        false
    } ?: false
}
