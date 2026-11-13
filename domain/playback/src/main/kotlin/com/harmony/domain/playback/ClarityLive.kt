package com.harmony.domain.playback

/**
 * Clarity Live's view of the music, from the playing engine: per band (24,
 * EqClarity.CENTERS_HZ) the response it applies, the music's ear-weighted
 * level and the masking threshold, dB, into the arrays given (any may be
 * null). Returns the level match in dB, NaN while Clarity is off and flat.
 */
fun interface ClarityTelemetrySource {
    fun poll(response: FloatArray?, levels: FloatArray?, masks: FloatArray?): Float
}

/**
 * Shared like [AudioLevels]: the playback service sets [source], the
 * Equalizer screen polls it once per frame into arrays it allocated once.
 * The engine publishes these through atomics, so any thread may poll.
 */
object ClarityLive {
    @Volatile
    var source: ClarityTelemetrySource? = null

    /** NaN when nothing plays or Clarity is off and flat. */
    fun poll(response: FloatArray?, levels: FloatArray?, masks: FloatArray?): Float {
        val telemetry = source ?: return Float.NaN
        return runCatching { telemetry.poll(response, levels, masks) }.getOrDefault(Float.NaN)
    }
}
