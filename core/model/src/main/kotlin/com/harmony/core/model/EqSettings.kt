package com.harmony.core.model

/**
 * 10-band graphic EQ state. Band centers are fixed ISO octave frequencies
 * (31.25 Hz .. 16 kHz); gains in dB, clamped to +/-12 by the UI and again by
 * the audio processor (defense in depth — a corrupt persisted value must not
 * be able to blow out the output stage).
 */
data class EqSettings(
    val enabled: Boolean = false,
    val bandGainsDb: List<Float> = List(BAND_COUNT) { 0f },
    val bassBoostDb: Float = 0f,
    val trebleBoostDb: Float = 0f,
) {
    companion object {
        const val BAND_COUNT = 10
        const val MAX_GAIN_DB = 12f
        val BAND_CENTERS_HZ = listOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

        val PRESETS: Map<String, List<Float>> = mapOf(
            "Flat" to List(10) { 0f },
            "Bass Boost" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Treble Boost" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 2f, 4f, 5f, 6f),
            "V-Shape" to listOf(5f, 4f, 2f, 0f, -2f, -2f, 0f, 2f, 4f, 5f),
            "Vocal" to listOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f),
            "Acoustic" to listOf(3f, 3f, 2f, 1f, 1f, 1f, 2f, 3f, 3f, 2f),
        )
    }
}
