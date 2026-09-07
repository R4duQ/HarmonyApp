package com.harmony.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.harmony.core.model.EqSettings
import com.harmony.core.model.ReplayGainMode
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
        val ENERGY = floatPreferencesKey("energy_slider") // -1 sentinel = off
        val SOULSEEK_FORMAT = stringPreferencesKey("soulseek_format_preference")
        val SMART_SHUFFLE_STYLE = stringPreferencesKey("smart_shuffle_style_v2")
        val SMART_SHUFFLE_FAMILIARITY = floatPreferencesKey("smart_shuffle_familiarity_v2")
        val SMART_SHUFFLE_DISCOVERY = floatPreferencesKey("smart_shuffle_discovery_v2")
        val SMART_SHUFFLE_VARIETY = floatPreferencesKey("smart_shuffle_variety_v2")
        val EQ_USER_PRESETS = stringPreferencesKey("eq_user_presets_v1") // "name=csv;name=csv"
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
            eq = EqSettings(
                enabled = p[Keys.EQ_ENABLED] ?: false,
                bandGainsDb = p[Keys.EQ_BANDS]?.split(',')
                    ?.mapNotNull { it.toFloatOrNull() }
                    ?.takeIf { it.size == EqSettings.BAND_COUNT }
                    ?: List(EqSettings.BAND_COUNT) { 0f },
                bassBoostDb = p[Keys.EQ_BASS] ?: 0f,
                trebleBoostDb = p[Keys.EQ_TREBLE] ?: 0f,
            ),
            energySliderValue = p[Keys.ENERGY]?.takeIf { it >= 0f },
            soulseekFormatPreference = p[Keys.SOULSEEK_FORMAT] ?: "FLAC_ONLY",
            smartShuffleStyle = p[Keys.SMART_SHUFFLE_STYLE] ?: "BALANCED",
            smartShuffleFamiliarity = (p[Keys.SMART_SHUFFLE_FAMILIARITY] ?: 0.55f).coerceIn(0f, 1f),
            smartShuffleDiscovery = (p[Keys.SMART_SHUFFLE_DISCOVERY] ?: 0.45f).coerceIn(0f, 1f),
            smartShuffleVariety = (p[Keys.SMART_SHUFFLE_VARIETY] ?: 0.45f).coerceIn(0f, 1f),
            userEqPresets = decodePresets(p[Keys.EQ_USER_PRESETS]),
        )
    }

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
    }

    private suspend inline fun edit(
        crossinline block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ) {
        context.store.edit { block(it) }
    }
}
