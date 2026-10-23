package com.harmony.core.model

/**
 * The equalizer, as the native engine (core:eq-engine) runs it: ten bands
 * from 31 Hz to 16 kHz, -12..+12 dB each, a preamp, and Auto.
 *
 * Gains are clamped again by the engine (defense in depth: a corrupt persisted
 * value must not be able to blow out the output stage).
 */
data class EqSettings(
    /** The sliders apply. Off is flat; the engine's protection stages stay on either way. */
    val enabled: Boolean = false,
    /** One gain per [BAND_CENTERS_HZ] band, dB. */
    val bandGainsDb: List<Float> = FLAT,
    /** A preamp on top of the engine's automatic headroom, -12..+12 dB. */
    val preampDb: Float = 0f,
    /**
     * Winamp's AUTO: every song gets its own curve (see [EqAuto]), morphed in
     * over 400 ms as it starts, and the sub-bass boost backs off on heavy kicks.
     */
    val auto: Boolean = false,
    /**
     * Android 14+ with a USB DAC: hand the DAC the file's PCM untouched instead
     * of equalizing it. Ignored everywhere else.
     */
    val bitPerfectUsb: Boolean = false,
) {
    companion object {
        const val BAND_COUNT = 10
        const val MAX_GAIN_DB = 12f
        const val MAX_PREAMP_DB = 12f
        val BAND_CENTERS_HZ = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        val BAND_LABELS = listOf("31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K")
        val FLAT: List<Float> = List(BAND_COUNT) { 0f }

        /** [gains] made safe: ten finite values within ±[MAX_GAIN_DB]. */
        fun sanitize(gains: List<Float>?): List<Float> =
            List(BAND_COUNT) { i ->
                gains?.getOrNull(i)?.takeIf { it.isFinite() }?.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB) ?: 0f
            }
    }
}

/** The presets, as Winamp named them (its curves moved onto these bands), and a few more. */
object EqPresets {
    val ALL: List<Pair<String, List<Float>>> = listOf(
        "Flat" to EqSettings.FLAT,
        "Acoustic" to listOf(3f, 3f, 2f, 1f, 1f, 1f, 2f, 3f, 3f, 2f),
        "Bass Boost" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Classical" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, -2f, -5.5f, -7f),
        "Club" to listOf(0f, 0f, 0f, 4f, 4.5f, 4f, 4f, 3.5f, 1.5f, 0f),
        "Dance" to listOf(7f, 7f, 6f, 3f, 0.5f, 0f, -2.5f, -4.5f, -5.5f, 0f),
        "Full Bass" to listOf(7f, 7f, 6f, 4f, 1.5f, -1f, -3f, -5f, -6f, -6f),
        "Full Bass & Treble" to listOf(5.5f, 5.5f, 4.5f, 1.5f, -4f, -3.5f, -0.5f, 3f, 7f, 9f),
        "Full Treble" to listOf(-7f, -7f, -7f, -7f, -4f, 2f, 6f, 10f, 12f, 12f),
        "Headphones" to listOf(3.5f, 4f, 7f, 5.5f, -0.5f, -2f, 0f, 2f, 5f, 11f),
        "Hip-Hop" to listOf(6f, 5.5f, 3f, 1f, -1f, 0f, 1f, 1.5f, 2f, 2.5f),
        "Jazz" to listOf(3f, 2.5f, 1f, 1.5f, -1f, -1f, 0f, 1.5f, 3f, 3.5f),
        "Large Hall" to listOf(8f, 8f, 8f, 5.5f, 4f, 0f, -2.5f, -3.5f, -3.5f, 0f),
        "Live" to listOf(-3.5f, -3.5f, -1f, 2f, 4f, 4f, 4f, 3.5f, 2.5f, 2f),
        "Party" to listOf(5.5f, 5.5f, 5.5f, 2f, 0f, 0f, 0f, 0f, 0f, 5.5f),
        "Pop" to listOf(-1f, -1f, 2f, 5f, 6f, 4f, 1.5f, -0.5f, -2f, -1f),
        "Reggae" to listOf(0f, 0f, 0f, 0f, -3f, 0f, 3f, 5f, 3f, 0f),
        "Rock" to listOf(6f, 6f, 4.5f, -1.5f, -5.5f, -2.5f, 1f, 4.5f, 7.5f, 8.5f),
        "Ska" to listOf(-2f, -2f, -3f, -3f, -1f, 3f, 4f, 5f, 7f, 7f),
        "Soft" to listOf(3.5f, 3.5f, 2f, 0.5f, -1.5f, 0f, 2f, 4f, 6.5f, 9f),
        "Soft Rock" to listOf(3f, 3f, 3f, 2f, 0.5f, -3f, -4f, -3.5f, -1.5f, 6.5f),
        "Techno" to listOf(6f, 6f, 4.5f, 1.5f, -3f, -3.5f, -1.5f, 2.5f, 6.5f, 6.5f),
        "Treble Boost" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 2f, 4f, 5f, 6f),
        "Vocal" to listOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f),
    )

    fun named(name: String): List<Float>? = ALL.firstOrNull { it.first == name }?.second

    /** The preset [gains] match, if any. */
    fun matching(gains: List<Float>): String? = ALL.firstOrNull { (_, preset) ->
        preset.indices.all { kotlin.math.abs(preset[it] - gains.getOrElse(it) { 0f }) < 0.05f }
    }?.first
}

/** Where Auto took a song's curve from. */
enum class EqAutoSource {
    /** The listener shaped the curve while this song played with Auto on. */
    SONG,

    /** The preset for the song's genre. */
    GENRE,

    /** Nothing known about the song: the listener's own curve. */
    MANUAL,
}

/** The curve Auto plays a song with, and why. */
data class EqAutoCurve(
    val gainsDb: List<Float>,
    val source: EqAutoSource,
    /** The preset's name for [EqAutoSource.GENRE]. */
    val preset: String? = null,
)

/**
 * Winamp's AUTO button loaded the preset saved for each file. Harmony's does
 * the same, and when nothing was saved, picks the preset for the song's genre:
 *
 *  1. the curve saved for the song (moving a slider while Auto plays it saves one);
 *  2. the genre's preset;
 *  3. the listener's own curve.
 */
object EqAuto {
    /** How many songs keep a curve of their own; the oldest go first. */
    const val MAX_SONG_CURVES = 500

    fun curveFor(
        songId: Long?,
        genre: String?,
        songCurves: Map<Long, List<Float>>,
        manual: List<Float>,
    ): EqAutoCurve {
        songId?.let(songCurves::get)?.let { return EqAutoCurve(EqSettings.sanitize(it), EqAutoSource.SONG) }
        presetForGenre(genre)?.let { name ->
            EqPresets.named(name)?.let { return EqAutoCurve(it, EqAutoSource.GENRE, name) }
        }
        return EqAutoCurve(EqSettings.sanitize(manual), EqAutoSource.MANUAL)
    }

    /** The preset for a genre tag ("Alternative Rock", "Hip Hop/Rap", "Electronic; House"...), or null. */
    fun presetForGenre(genre: String?): String? {
        val g = genre?.lowercase()?.trim().orEmpty()
        if (g.isEmpty()) return null
        return GENRES.firstOrNull { (words, _) -> words.any { it in g } }?.second
    }

    // Checked in order: the more specific words first ("drum and bass" before "bass",
    // "hip hop" before "pop", "soft rock" before "rock").
    private val GENRES: List<Pair<List<String>, String>> = listOf(
        listOf("spoken", "podcast", "audiobook", "speech", "comedy", "a cappella", "acapella") to "Vocal",
        listOf("hip hop", "hip-hop", "hiphop", "rap", "trap", "r&b", "rnb", "grime", "drill") to "Hip-Hop",
        listOf("classical", "orchestra", "symphon", "opera", "baroque", "chamber", "soundtrack", "score") to "Classical",
        listOf("techno", "trance", "electronic", "electro", "dubstep", "drum and bass", "drum & bass", "dnb", "idm", "synthwave") to "Techno",
        listOf("reggae", "dub", "dancehall") to "Reggae",
        listOf("house", "dance", "disco", "edm", "eurodance") to "Dance",
        listOf("soft rock", "yacht") to "Soft Rock",
        listOf("rock", "metal", "punk", "grunge", "indie", "alternative", "emo", "hardcore") to "Rock",
        listOf("ska") to "Ska",
        listOf("jazz", "blues", "soul", "funk", "swing", "bossa") to "Jazz",
        listOf("acoustic", "folk", "country", "singer-songwriter", "bluegrass") to "Acoustic",
        listOf("ambient", "chill", "lounge", "new age", "lo-fi", "lofi", "easy listening") to "Soft",
        listOf("latin", "reggaeton", "salsa", "party", "manele") to "Party",
        listOf("live") to "Live",
        listOf("vocal", "chanson", "gospel", "choir") to "Vocal",
        listOf("pop") to "Pop",
    )

    /**
     * Turns the old Winamp equalizer's sliders (60 Hz … 16 kHz, ±20 dB) into
     * these bands, so the sound a listener chose before survives the update.
     */
    fun fromWinampBands(winampDb: List<Float>): List<Float> {
        if (winampDb.size != WINAMP_HZ.size) return EqSettings.FLAT
        return EqSettings.BAND_CENTERS_HZ.map { f ->
            val v = when {
                f <= WINAMP_HZ.first() -> winampDb.first()
                f >= WINAMP_HZ.last() -> winampDb.last()
                else -> {
                    val i = (0 until WINAMP_HZ.size - 1).first { f >= WINAMP_HZ[it] && f <= WINAMP_HZ[it + 1] }
                    val t = (kotlin.math.ln(f / WINAMP_HZ[i]) / kotlin.math.ln(WINAMP_HZ[i + 1] / WINAMP_HZ[i]))
                    winampDb[i] + (winampDb[i + 1] - winampDb[i]) * t
                }
            }
            (kotlin.math.round(v * 2f) / 2f).coerceIn(-EqSettings.MAX_GAIN_DB, EqSettings.MAX_GAIN_DB)
        }
    }

    private val WINAMP_HZ = listOf(60f, 170f, 310f, 600f, 1_000f, 3_000f, 6_000f, 12_000f, 14_000f, 16_000f)
}
