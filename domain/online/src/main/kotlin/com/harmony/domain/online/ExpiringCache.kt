package com.harmony.domain.online

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Answers kept for a while, and one request per key at a time: callers
 * asking for a key already being loaded wait for that load instead of
 * asking again. Only what [load] returns with a TTL is kept (a failure is
 * returned with ttl 0 and asked again next time).
 */
class ExpiringCache<K : Any, V>(
    private val capacity: Int,
    private val now: () -> Long,
) {
    private class Entry<V>(val value: V, val until: Long)

    private val lock = Mutex()
    private val entries = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?) = size > capacity
    }
    private val inFlight = HashMap<K, CompletableDeferred<V>>()

    /** How many loads ran (not served from memory or shared): for tests and diagnostics. */
    @Volatile var loads: Int = 0
        private set

    suspend fun get(key: K): V? = lock.withLock { fresh(key) }

    /** The kept answer, or [load]'s; [load] returns the value and how long to keep it (0: not kept). */
    suspend fun getOrLoad(key: K, load: suspend () -> Pair<V, Long>): V {
        var mine: CompletableDeferred<V>? = null
        val waiting = lock.withLock {
            fresh(key)?.let { return it }
            inFlight[key] ?: CompletableDeferred<V>().also { mine = it; inFlight[key] = it }
        }
        val owner = mine ?: return waiting.await()
        try {
            loads++
            val (value, ttl) = load()
            lock.withLock {
                if (ttl > 0) entries[key] = Entry(value, now() + ttl)
                inFlight.remove(key)
            }
            owner.complete(value)
            return value
        } catch (t: Throwable) {
            lock.withLock { inFlight.remove(key) }
            owner.completeExceptionally(t)
            throw t
        }
    }

    suspend fun clear() = lock.withLock { entries.clear() }

    private fun fresh(key: K): V? {
        val entry = entries[key] ?: return null
        if (now() >= entry.until) {
            entries.remove(key)
            return null
        }
        return entry.value
    }
}
