package com.mezon.mobile.home.clans

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

internal class ChannelLinkLookupCache<T>(
    private val scope: CoroutineScope,
    private val windowMs: Long = 60_000,
    private val maxStarts: Int = 10,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Session<T>(scope: CoroutineScope) {
        val job = SupervisorJob(scope.coroutineContext[Job])
        val values = HashMap<Long, T?>()
        val pending = HashSet<Long>()
        val slots = Semaphore(2)
        val rateLock = Mutex()
        val starts = ArrayDeque<Long>()
    }
    private var session = Session<T>(scope)

    @Synchronized fun get(id: Long): T? = session.values[id]

    @Synchronized fun clear() {
        session.job.cancel()
        session = Session(scope)
    }

    @Synchronized fun request(id: Long, lookup: suspend () -> T?, onResolved: () -> Unit) {
        val current = session
        if (current.values.containsKey(id) || !current.pending.add(id)) return
        scope.launch(current.job) {
            try {
                val result = current.slots.withPermit {
                    current.rateLock.withLock {
                        while (true) {
                            val time = now()
                            while (current.starts.isNotEmpty() && time - current.starts.first() >= windowMs) {
                                current.starts.removeFirst()
                            }
                            if (current.starts.size < maxStarts) {
                                current.starts.addLast(time)
                                break
                            }
                            delay((current.starts.first() + windowMs - time).coerceAtLeast(1))
                        }
                    }
                    lookup()
                }
                synchronized(this@ChannelLinkLookupCache) {
                    if (session === current) {
                        current.values[id] = result
                        onResolved()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Transport failures may be retried on a later render.
            } finally {
                synchronized(this@ChannelLinkLookupCache) { current.pending.remove(id) }
            }
        }
    }
}
