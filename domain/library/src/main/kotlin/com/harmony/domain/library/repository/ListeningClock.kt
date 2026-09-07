package com.harmony.domain.library.repository

/** Accepts natural progress only; the player must reset this clock on every seek. */
class ListeningClock {
    private data class Tick(val uri: String, val position: Long, val elapsed: Long, val playing: Boolean, val speed: Float)
    private var last: Tick? = null

    fun reset() { last = null }

    fun sample(uri: String, position: Long, elapsed: Long, playing: Boolean, speed: Float): ListenedRange? {
        val before = last
        last = Tick(uri, position, elapsed, playing, speed)
        if (before == null || before.uri != uri || !before.playing) return null
        val wall = elapsed - before.elapsed
        val delta = position - before.position
        if (wall !in 1..15_000 || delta <= 0 || delta > wall * before.speed.coerceIn(0.1f, 5f) + 400) return null
        return ListenedRange(before.position, position)
    }
}
