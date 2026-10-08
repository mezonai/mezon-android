package com.mezon.mobile.home.voice.sfu

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One deadline for an entire join/recovery; transport retries never extend it. */
internal class SfuRecoveryDeadline(private val timeoutMs: Long) {
    private var job: Job? = null

    fun arm(scope: CoroutineScope, onExpired: () -> Unit) {
        if (job != null) return
        job = scope.launch(start = CoroutineStart.LAZY) {
            delay(timeoutMs)
            onExpired()
        }
        job?.start()
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
