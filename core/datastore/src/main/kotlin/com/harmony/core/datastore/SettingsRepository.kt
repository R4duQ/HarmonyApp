package com.harmony.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.harmony.core.model.AutoEqSettings
import com.harmony.core.model.EqSettings
import com.harmony.core.model.EqStyle
import com.harmony.core.model.OutputForm
import com.harmony.core.model.OutputForms
import com.harmony.core.model.ReplayGainMode
import com.harmony.core.model.RoomCorrection
import com.harmony.core.model.WinampEqDesign
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What to restore into the (empty, freshly-started) session at next launch. */
data class LastPlaybackState(
    val songIds: List<Long>,
    val index: Int,
    val positionMs: Long,
)

/** Everything the settings screens read/write, as one typed snapshot. */
data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val amoledDark: Boolean = false,
    val dynamicColors: Boolean = true,
    val chargingOnlyAnalysis: Boolean = false,
    val crossfadeSeconds: Int = 0,
    val replayGainMode: ReplayGainMode = ReplayGainMode.OFF,
    val eq: EqSettings = EqSettings(),
    val energySliderValue: Float? = null,
    val smartShuffleStyle: String = "BALANCED",
    val smartShuffleFamiliarity: Float = 0.55f,
    val smartShuffleDiscovery: Float = 0.45f,
    val smartShuffleVariety: Float = 0.45f,
    val userEqPresets: Map<String, List<Float>> = emptyMap(),
    /** Name of a SoulseekFormatPreference entry; kept as a String so :core:datastore stays feature-agnostic. */
    val soulseekFormatPreference: String = "FLAC_ONLY",
    val autoEq: AutoEqSettings = AutoEqSettings(),
    /** Speaker/room corrections measured with the microphone, keyed by AutoEqDesign.outputKey. */
    val roomCorrections: Map<String, RoomCorrection> = emptyMap(),
)

/**
 * Preferences DataStore over one file. EQ band gains are packed into a CSV
 * string — a full serialization framework for one 10-float list is not worth
 * the dependency weight, and the format is versioned by key name.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val Context.store by preferencesDataStore(name = "harmony_settings")

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val AMOLED = booleanPreferencesKey("amoled_dark")
        val DYNAMIC = booleanPreferencesKey("dynamic_colors")
        val CHARGING_ONLY = booleanPreferencesKey("charging_only_analysis")
        val CROSSFADE = intPreferencesKey("crossfade_seconds")
        val REPLAYGAIN = stringPreferencesKey("replaygain_mode")
        val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        val EQ_BANDS = stringPreferencesKey("eq_bands_csv_v1")
        val EQ_BASS = floatPreferencesKey("eq_bass")
        val EQ_TREBLE = floatPreferencesKey("eq_treble")
        val EQ_STYLE = stringPreferencesKey("eq_style")
        val EQ_WINAMP_BANDS = stringPreferencesKey("eq_winamp_bands_csv_v1")
        val EQ_WINAMP_PREAMP = floatPreferencesKey("eq_winamp_preamp")
        val ENERGY = floatPreferencesKey("energy_slider") // -1 sentinel = off
        val SOULSEEK_FORMAT = stringPreferencesKey("soulseek_format_preference")
        val SMART_SHUFFLE_STYLE = stringPreferencesKey("smart_shuffle_style_v2")
        val SMART_SHUFFLE_FAMILIARITY = floatPreferencesKey("smart_shuffle_familiarity_v2")
        val SMART_SHUFFLE_DISCOVERY = floatPreferencesKey("smart_shuffle_discovery_v2")
        val SMART_SHUFFLE_VARIETY = floatPreferencesKey("smart_shuffle_variety_v2")
        val EQ_USER_PRESETS = stringPreferencesKey("eq_user_presets_v1") // "name=csv;name=csv"
        val OUTPUT_FORMS = stringPreferencesKey("output_forms_v1") // one "FORM<tab>device name" per line
        val AUTO_EQ_TONE = booleanPreferencesKey("auto_eq_tone")
        val AUTO_EQ_ROOM = booleanPreferencesKey("auto_eq_room")
        val AUTO_EQ_NOISE = booleanPreferencesKey("auto_eq_noise")
        val ROOM_CORRECTIONS = stringPreferencesKey("room_corrections_v1") // one "key<tab>label<tab>millis<tab>csv" per line
        val LAST_QUEUE_IDS = stringPreferencesKey("last_queue_song_ids") // csv of Longs
        val LAST_QUEUE_INDEX = intPreferencesKey("last_queue_index")
        val LAST_POSITION_MS = longPreferencesKey("last_position_ms")
    }

    val settings: Flow<UserSettings> = context.store.data.map { p ->
        UserSettings(
            themeMode = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            amoledDark = p[Keys.AMOLED] ?: false,
            dynamicColors = p[Keys.DYNAMIC] ?: true,
            chargingOnlyAnalysis = p[Keys.CHARGING_ONLY] ?: false,
            crossfadeSeconds = (p[Keys.CROSSFADE] ?: 0).coerceIn(0, 12),
            replayGainMode = p[Keys.REPLAYGAIN]
                ?.let { runCatching { ReplayGainMode.valueOf(it) }.getOrNull() }
                ?: ReplayGainMode.OFF,
            eq = readEq(p),
            energySliderValue = p[Keys.ENERGY]?.takeIf { it >= 0f },
            soulseekFormatPreference = p[Keys.SOULSEEK_FORMAT] ?: "FLAC_ONLY",
            smartShuffleStyle = p[Keys.SMART_SHUFFLE_STYLE] ?: "BALANCED",
            smartShuffleFamiliarity = (p[Keys.SMART_SHUFFLE_FAMILIARITY] ?: 0.55f).coerceIn(0f, 1f),
            smartShuffleDiscovery = (p[Keys.SMART_SHUFFLE_DISCOVERY] ?: 0.45f).coerceIn(0f, 1f),
            smartShuffleVariety = (p[Keys.SMART_SHUFFLE_VARIETY] ?: 0.45f).coerceIn(0f, 1f),
            userEqPresets = decodePresets(p[Keys.EQ_USER_PRESETS]),
            autoEq = AutoEqSettings(
                tone = p[Keys.AUTO_EQ_TONE] ?: false,
                room = p[Keys.AUTO_EQ_ROOM] ?: false,
                noise = p[Keys.AUTO_EQ_NOISE] ?: false,
            ),
            roomCorrections = decodeRoomCorrections(p[Keys.ROOM_CORRECTIONS]),
        )
    }

    /**
     * The Winamp equalizer is the only manual one now. A listener who had set
     * the old Simple/Advanced bands and never touched Winamp gets those bands
     * carried over (WinampEqDesign.fromOctaveBands), so the sound they chose
     * stays; the first Winamp change saves Winamp's own values from then on.
     */
    private fun readEq(p: Preferences): EqSettings {
        val octave = p[Keys.EQ_BANDS]?.split(',')
            ?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == EqSettings.BAND_COUNT }
            ?: List(EqSettings.BAND_COUNT) { 0f }
        val winamp = p[Keys.EQ_WINAMP_BANDS]?.split(',')
            ?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == EqSettings.BAND_COUNT }
        val oldStyle = p[Keys.EQ_STYLE]?.let { name -> EqStyle.entries.firstOrNull { it.name == name } } ?: EqStyle.HARMONY
        val carried = winamp == null && oldStyle == EqStyle.HARMONY && octave.any { it != 0f }
        return EqSettings(
            enabled = p[Keys.EQ_ENABLED] ?: false,
            bandGainsDb = octave,
            style = EqStyle.WINAMP,
            winampGainsDb = winamp ?: if (carried) WinampEqDesign.fromOctaveBands(octave) else List(EqSettings.BAND_COUNT) { 0f },
            winampPreampDb = p[Keys.EQ_WINAMP_PREAMP] ?: 0f,
        )
    }

    suspend fun setAutoEq(auto: AutoEqSettings) = edit {
        it[Keys.AUTO_EQ_TONE] = auto.tone
        it[Keys.AUTO_EQ_ROOM] = auto.room
        it[Keys.AUTO_EQ_NOISE] = auto.noise
    }

    /** Files [correction] under [key] (AutoEqDesign.outputKey), replacing an older measurement. */
    suspend fun saveRoomCorrection(key: String, correction: RoomCorrection) = edit { p ->
        val all = decodeRoomCorrections(p[Keys.ROOM_CORRECTIONS]).toMutableMap()
        all[key] = correction
        p[Keys.ROOM_CORRECTIONS] = encodeRoomCorrections(all)
    }

    suspend fun deleteRoomCorrection(key: String) = edit { p ->
        val all = decodeRoomCorrections(p[Keys.ROOM_CORRECTIONS]) - key
        p[Keys.ROOM_CORRECTIONS] = encodeRoomCorrections(all)
    }

    private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ')

    private fun encodeRoomCorrections(all: Map<String, RoomCorrection>): String =
        all.entries.joinToString("\n") { (key, c) ->
            listOf(clean(key), clean(c.label), c.measuredAtMs.toString(), c.gainsDb.joinToString(",")).joinToString("\t")
        }

    private fun decodeRoomCorrections(raw: String?): Map<String, RoomCorrection> =
        raw?.lines()?.mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 4) return@mapNotNull null
            val gains = parts[3].split(',').mapNotNull { it.toFloatOrNull() }
            if (gains.size != EqSettings.BAND_COUNT) return@mapNotNull null
            parts[0] to RoomCorrection(gains, parts[2].toLongOrNull() ?: 0L, parts[1])
        }?.toMap() ?: emptyMap()

    /** The kind of device the listener picked for each output, keyed by [OutputForms.key]. */
    val outputForms: Flow<Map<String, OutputForm>> = context.store.data.map { decodeForms(it[Keys.OUTPUT_FORMS]) }

    /** Saves what kind of device [name] is; null goes back to Harmony's own guess. */
    suspend fun setOutputForm(name: String, form: OutputForm?) = edit { p ->
        val key = OutputForms.key(name).replace('\t', ' ').replace('\n', ' ')
        if (key.isBlank()) return@edit
        val forms = decodeForms(p[Keys.OUTPUT_FORMS]).toMutableMap()
        if (form == null) forms.remove(key) else forms[key] = form
        p[Keys.OUTPUT_FORMS] = forms.entries.joinToString("\n") { (device, kind) -> "${kind.name}\t$device" }
    }

    private fun decodeForms(raw: String?): Map<String, OutputForm> =
        raw?.lines()?.mapNotNull { line ->
            val kind = runCatching { OutputForm.valueOf(line.substringBefore('\t')) }.getOrNull() ?: return@mapNotNull null
            line.substringAfter('\t', "").takeIf { it.isNotBlank() }?.let { it to kind }
        }?.toMap() ?: emptyMap()

    private fun decodePresets(raw: String?): Map<String, List<Float>> =
        raw?.split(';')?.mapNotNull { entry ->
            val (name, csv) = entry.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            val gains = csv.split(',').mapNotNull { it.toFloatOrNull() }
            if (gains.size == EqSettings.BAND_COUNT) name to gains else null
        }?.toMap() ?: emptyMap()

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }
    suspend fun setAmoledDark(on: Boolean) = edit { it[Keys.AMOLED] = on }
    suspend fun setDynamicColors(on: Boolean) = edit { it[Keys.DYNAMIC] = on }
    suspend fun setChargingOnlyAnalysis(on: Boolean) = edit { it[Keys.CHARGING_ONLY] = on }
    suspend fun setCrossfadeSeconds(seconds: Int) = edit { it[Keys.CROSSFADE] = seconds.coerceIn(0, 12) }
    suspend fun setReplayGainMode(mode: ReplayGainMode) = edit { it[Keys.REPLAYGAIN] = mode.name }
    suspend fun setEnergySlider(value: Float?) = edit { it[Keys.ENERGY] = value ?: -1f }
    suspend fun setSoulseekFormatPreference(name: String) = edit { it[Keys.SOULSEEK_FORMAT] = name }
    suspend fun setSmartShuffleStyle(style: String) = edit { it[Keys.SMART_SHUFFLE_STYLE] = style }
    suspend fun setSmartShuffleFamiliarity(value: Float) = edit {
        it[Keys.SMART_SHUFFLE_FAMILIARITY] = value.coerceIn(0f, 1f)
    }
    suspend fun setSmartShuffleDiscovery(value: Float) = edit {
        it[Keys.SMART_SHUFFLE_DISCOVERY] = value.coerceIn(0f, 1f)
    }
    suspend fun setSmartShuffleVariety(value: Float) = edit {
        it[Keys.SMART_SHUFFLE_VARIETY] = value.coerceIn(0f, 1f)
    }
    suspend fun setSmartShuffleProfile(
        style: String,
        familiarity: Float,
        discovery: Float,
        variety: Float,
    ) = edit {
        it[Keys.SMART_SHUFFLE_STYLE] = style
        it[Keys.SMART_SHUFFLE_FAMILIARITY] = familiarity.coerceIn(0f, 1f)
        it[Keys.SMART_SHUFFLE_DISCOVERY] = discovery.coerceIn(0f, 1f)
        it[Keys.SMART_SHUFFLE_VARIETY] = variety.coerceIn(0f, 1f)
    }

    suspend fun saveUserEqPreset(name: String, gains: List<Float>) = edit { p ->
        val safe = name.replace('=', ' ').replace(';', ' ').trim().take(24)
        if (safe.isBlank() || gains.size != EqSettings.BAND_COUNT) return@edit
        val current = decodePresets(p[Keys.EQ_USER_PRESETS]).toMutableMap()
        current[safe] = gains
        p[Keys.EQ_USER_PRESETS] = current.entries
            .joinToString(";") { entry -> entry.key + "=" + entry.value.joinToString(",") }
    }

    suspend fun deleteUserEqPreset(name: String) = edit { p ->
        val current = decodePresets(p[Keys.EQ_USER_PRESETS]).toMutableMap()
        current.remove(name)
        p[Keys.EQ_USER_PRESETS] = current.entries
            .joinToString(";") { entry -> entry.key + "=" + entry.value.joinToString(",") }
    }

    /**
     * Persisted once, right when the app is closed (see PlaybackService.
     * onTaskRemoved), and read once at the next cold start — this is what
     * lets Harmony behave like a podcast app rather than a continuous
     * background player: closing stops playback, reopening restores exactly
     * where you were without auto-resuming.
     */
    suspend fun saveLastPlaybackState(songIds: List<Long>, index: Int, positionMs: Long) = edit {
        it[Keys.LAST_QUEUE_IDS] = songIds.joinToString(",")
        it[Keys.LAST_QUEUE_INDEX] = index
        it[Keys.LAST_POSITION_MS] = positionMs
    }

    suspend fun getLastPlaybackState(): LastPlaybackState? {
        val prefs = context.store.data.first()
        val ids = prefs[Keys.LAST_QUEUE_IDS]?.split(",")?.mapNotNull { it.toLongOrNull() }
            ?: return null
        if (ids.isEmpty()) return null
        return LastPlaybackState(
            songIds = ids,
            index = prefs[Keys.LAST_QUEUE_INDEX] ?: 0,
            positionMs = prefs[Keys.LAST_POSITION_MS] ?: 0L,
        )
    }

    suspend fun setEq(eq: EqSettings) = edit {
        it[Keys.EQ_ENABLED] = eq.enabled
        it[Keys.EQ_BANDS] = eq.bandGainsDb.joinToString(",")
        it[Keys.EQ_BASS] = eq.bassBoostDb
        it[Keys.EQ_TREBLE] = eq.trebleBoostDb
        it[Keys.EQ_STYLE] = eq.style.name
        it[Keys.EQ_WINAMP_BANDS] = eq.winampGainsDb.joinToString(",")
        it[Keys.EQ_WINAMP_PREAMP] = eq.winampPreampDb
    }

    private suspend inline fun edit(
        crossinline block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ) {
        context.store.edit { block(it) }
    }
}
