package com.harmony.feature.downloads

/**
 * Answers of a slow lookup service (song.link), remembered.
 *
 * - An answer is reused for [ttlMs]; the last [capacity] are kept.
 * - Callers asking for the same key at the same time share one request.
 * - After the service says it is asked too often, nobody asks for a while
 *   ([pauseFor]); callers get null and go on with what they have.
 */
internal class LookupMemory<V : Any>(
    private val capacity: Int,
    private val ttlMs: Long,
    private val now: () -> Long,
) {
    private class Entry<V>(val value: V, val at: Long)

    private val answers = object : LinkedHashMap<String, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<V>>?) = size > capacity
    }
    private val inFlight = HashMap<String, Any>()
    @Volatile private var pausedUntil = Long.MIN_VALUE

    fun get(key: String): V? = synchronized(answers) {
        val entry = answers[key] ?: return null
        if (now() - entry.at >= ttlMs) {
            answers.remove(key)
            null
        } else entry.value
    }

    fun put(key: String, value: V) = synchronized(answers) { answers[key] = Entry(value, now()) }

    /** True while the service asked not to be called. */
    val paused: Boolean get() = now() < pausedUntil

    fun pauseFor(ms: Long) {
        pausedUntil = now() + ms
    }

    /**
     * The remembered answer for [key], or [ask]'s (remembered when not null).
     * While one caller asks, others wanting the same key wait for its answer
     * rather than asking too. Null while [paused] with nothing remembered.
     */
    fun getOrAsk(key: String, ask: () -> V?): V? {
        get(key)?.let { return it }
        val gate = synchronized(inFlight) { inFlight.getOrPut(key) { Any() } }
        try {
            synchronized(gate) {
                get(key)?.let { return it }
                if (paused) return null
                return ask()?.also { put(key, it) }
            }
        } finally {
            synchronized(inFlight) { inFlight.remove(key) }
        }
    }
}

/** Whether a track can be had from a provider, as far as can be told without downloading it. */
internal enum class SpotiFlacAvailabilityState {
    /** The provider confirmed it has the recording in a lossless quality. */
    AVAILABLE,
    /** The provider confirmed it does not. */
    UNAVAILABLE,
    /**
     * Nothing confirmed it either way. The runtimes Harmony ships (SpotiFLAC
     * 4.9.6, Mornye 5.3.1) only check availability as part of a download, so
     * this is the answer until one of them offers the check on its own.
     */
    UNKNOWN,
}

/** What is known about one provider for one track before any transfer. */
internal data class SpotiFlacAvailability(
    val provider: String,
    /** The provider's own id for this exact recording is known (the match is confirmed). */
    val matched: Boolean,
    val state: SpotiFlacAvailabilityState,
    val reason: String,
)

/** Which provider ids are already exact, so nothing needs to be asked before trying that provider. */
internal object SpotiFlacProviderMatch {
    /** True when [providerId] can be tried with the ids the track already carries. */
    fun hasExactId(providerId: String, deezerId: String?, tidalId: String?, qobuzId: String?): Boolean = when (providerId) {
        "deezer" -> !deezerId.isNullOrBlank()
        "tidal-web" -> !tidalId.isNullOrBlank()
        "qobuz-web" -> !qobuzId.isNullOrBlank()
        // Amazon is found through the links song.link returns; there is no id to carry.
        else -> false
    }
}
