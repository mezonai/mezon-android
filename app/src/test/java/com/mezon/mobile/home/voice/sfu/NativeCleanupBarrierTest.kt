package com.mezon.mobile.home.voice.sfu

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeCleanupBarrierTest {
    private fun withBarrier(check: (NativeCleanupBarrier) -> Unit) {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try { check(NativeCleanupBarrier(scope, dispatcher)) }
        finally { scope.cancel(); dispatcher.close() }
    }

    @Test fun emptyCleanupAllowsStartup() = withBarrier { barrier ->
        runBlocking { assertTrue(barrier.awaitCompletion(1_000)) }
    }

    @Test fun waitsForAllResourcesInOrder() = withBarrier { barrier ->
        val released = mutableListOf<String>()
        barrier.enqueue { released.add("peer") }
        barrier.enqueue { released.add("media") }
        barrier.enqueue { released.add("factory") }
        runBlocking { assertTrue(barrier.awaitCompletion(1_000)) }
        assertEquals(listOf("peer", "media", "factory"), released)
    }

    @Test fun timeoutPreventsReplacementUntilCleanupActuallyCompletes() = withBarrier { barrier ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val job = barrier.enqueue { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            runBlocking {
                assertFalse(barrier.awaitCompletion(20))
                assertTrue(job.isActive)
                assertFalse(barrier.awaitCompletion(20))
                release.countDown()
                assertTrue(barrier.awaitCompletion(1_000))
            }
        } finally { release.countDown() }
    }

    @Test fun cancelingJoinDoesNotCancelCleanup() = withBarrier { barrier ->
        val release = CountDownLatch(1)
        val job = barrier.enqueue { check(release.await(5, TimeUnit.SECONDS)) }
        try {
            runBlocking {
                val waiter = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    barrier.awaitCompletion(10_000)
                    fail("Canceled join must not start a replacement")
                }
                waiter.cancelAndJoin()
                assertTrue(job.isActive)
                release.countDown()
                assertTrue(barrier.awaitCompletion(1_000))
            }
        } finally { release.countDown() }
    }

    @Test fun failedNativeDisposalBlocksReplacementButStillCleansOtherResources() = withBarrier { barrier ->
        var factoryReleased = false
        barrier.enqueue { error("native disposal failed") }
        barrier.enqueue { factoryReleased = true }
        runBlocking { assertFalse(barrier.awaitCompletion(1_000)) }
        assertTrue(factoryReleased)
        runBlocking { assertFalse(barrier.awaitCompletion(1_000)) }
    }
}
