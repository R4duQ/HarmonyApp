package com.harmony.feature.downloads

/**
 * How many album tracks may download at once, right now.
 *
 * One, unless all of this holds: the experiment is switched on, the runtime
 * can take the provider from each request (so two downloads do not share a
 * route), and nothing is playing. Playback, on the phone or sent to another
 * device, always comes first: while music plays the album goes back to one
 * track at a time, and a second one starts only when the first is done.
 */
internal object SpotiFlacParallelPolicy {
    /** Never more than this, whatever a runtime or a provider would allow. */
    const val MOST_JOBS = 2

    fun jobs(experimentOn: Boolean, runtimeJobs: Int, playing: Boolean): Int =
        if (!experimentOn || runtimeJobs < 2 || playing) 1 else minOf(MOST_JOBS, runtimeJobs)

    /**
     * How many downloads one provider takes at once: what its package declares
     * (Tidal: 2; the others leave the runtime's 3), never more than [MOST_JOBS].
     */
    fun perProvider(providerId: String): Int = when (providerId) {
        "tidal-web" -> 2
        else -> 3
    }.coerceAtMost(MOST_JOBS)
}
