package com.mezon.mobile.home.clans

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    private data class CachedValue<T>(val value: T?, val groupId: Long)

    private class PendingLookup(val groupId: Long) {
        var job: Job? = null
    }

    private class Session<T>(scope: CoroutineScope) {
        val job = SupervisorJob(scope.coroutineContext[Job])
        val values = HashMap<Long, CachedValue<T>>()
        val pending = HashMap<Long, PendingLookup>()
        val accessChanged = HashSet<Long>()
        val slots = Semaphore(2)
        val rateLock = Mutex()
        val starts = ArrayDeque<Long>()
    }
    private var session = Session<T>(scope)

    @Synchronized fun get(id: Long): T? = session.values[id]?.value

    @Synchronized fun wasInvalidated(id: Long): Boolean = id in session.accessChanged

    @Synchronized fun invalidate(id: Long) {
        session.accessChanged.add(id)
        session.values.remove(id)
        session.pending.remove(id)?.job?.cancel()
    }

    @Synchronized fun invalidateGroup(groupId: Long) {
        val ids = session.values.filterValues { it.groupId == groupId || it.groupId == 0L }.keys +
            session.pending.filterValues { it.groupId == groupId || it.groupId == 0L }.keys
        ids.forEach(::invalidate)
    }

    @Synchronized fun clear() {
        session.job.cancel()
        session = Session(scope)
    }

    @Synchronized fun request(id: Long, groupId: Long = 0L, lookup: suspend () -> T?, onResolved: () -> Unit) {
        val current = session
        if (current.values.containsKey(id) || current.pending.containsKey(id)) return
        val pending = PendingLookup(groupId)
        current.pending[id] = pending
        pending.job = scope.launch(current.job, start = CoroutineStart.LAZY) {
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
                    if (session === current && current.pending[id] === pending) {
                        current.values[id] = CachedValue(result, groupId)
                        onResolved()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Transport failures may be retried on a later render.
            } finally {
                synchronized(this@ChannelLinkLookupCache) {
                    if (current.pending[id] === pending) current.pending.remove(id)
                }
            }
        }
        pending.job?.start()
    }
}
