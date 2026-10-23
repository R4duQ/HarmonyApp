package com.harmony.feature.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.harmony.core.datastore.SettingsRepository
import com.harmony.core.model.EqSettings
import com.harmony.core.model.EqAutoCurve
import com.harmony.core.model.EqAutoSource
import com.harmony.core.model.EqPresets
import com.harmony.core.model.EqResponse
import com.harmony.core.ui.component.EditorialCircleButton
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialSwitch
import com.harmony.core.ui.component.LocalFloatingChromeHeight
import com.harmony.core.ui.component.bluePalette
import com.harmony.domain.playback.EqLive
import com.harmony.domain.playback.EqStatus
import com.harmony.domain.playback.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

/**
 * All writes go to the DataStore only; SettingsApplier pushes the change into
 * the live audio chain (and the playback service reads Auto's song curves
 * itself). The screen therefore reflects reality even if the change was made
 * elsewhere, and EQ state survives process death for free.
 *
 * With Auto on, the sliders show and edit the song playing: whatever is set
 * then is remembered for that song, the way Winamp's AUTO kept a preset per file.
 */
@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    val eq: StateFlow<EqSettings> = settings.settings.map { it.eq }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EqSettings())

    /** What the engine is doing: the song's Auto curve, the output, bit-perfect. */
    val status: StateFlow<EqStatus> = EqLive.status

    /** What Winamp's main window shows: the song, its format, whether it's playing, and where. */
    val nowPlaying: StateFlow<NowPlayingInfo?> = playback.playerState
        .map { s ->
            s.currentSong?.let { song ->
                NowPlayingInfo(
                    title = song.title,
                    artist = song.artist,
                    durationMs = song.durationMs,
                    kbps = song.bitrateKbps,
                    sampleRateHz = song.sampleRateHz,
                    channels = song.channels,
                    playing = s.isPlaying,
                    output = s.audioOutput.label,
                )
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setEnabled(on: Boolean) = update { it.copy(enabled = on) }

    /** The ON button. */
    fun toggle() = update { it.copy(enabled = !it.enabled) }

    /** The AUTO button; switching it on switches the equalizer on too. */
    fun toggleAuto() = update { it.copy(auto = !it.auto, enabled = it.enabled || !it.auto) }

    fun setBand(index: Int, gainDb: Float) = edit { gains ->
        gains.toMutableList().also { it[index] = gainDb.coerceIn(-EqSettings.MAX_GAIN_DB, EqSettings.MAX_GAIN_DB) }
    }

    fun setPreamp(gainDb: Float) = update {
        it.copy(enabled = true, preampDb = gainDb.coerceIn(-EqSettings.MAX_PREAMP_DB, EqSettings.MAX_PREAMP_DB))
    }

    fun applyPreset(name: String) {
        val preset = EqPresets.named(name) ?: return
        edit { preset }
    }

    /** RESET: flat; with Auto on, the song goes back to what Auto would pick for it. */
    fun reset() {
        val song = autoSong()
        if (song != null) {
            viewModelScope.launch { settings.setEqSongCurve(song, null) }
        } else {
            update { it.copy(bandGainsDb = EqSettings.FLAT, preampDb = 0f) }
        }
    }

    fun forgetSongCurve() {
        val song = status.value.songId ?: return
        viewModelScope.launch { settings.setEqSongCurve(song, null) }
    }

    fun setBitPerfect(on: Boolean) = update { it.copy(bitPerfectUsb = on) }

    /** The song whose own curve the sliders edit, or null when they edit the listener's curve. */
    private fun autoSong(): Long? {
        val e = eq.value
        val s = status.value
        return s.songId.takeIf { e.enabled && e.auto && s.autoCurve != null }
    }

    /** Changes the curve the sliders show: the song's with Auto on, the listener's otherwise. */
    private fun edit(transform: (List<Float>) -> List<Float>) {
        val song = autoSong()
        if (song != null) {
            val base = status.value.autoCurve?.gainsDb ?: eq.value.bandGainsDb
            viewModelScope.launch { settings.setEqSongCurve(song, transform(base)) }
        } else {
            update { it.copy(enabled = true, bandGainsDb = EqSettings.sanitize(transform(it.bandGainsDb))) }
        }
    }

    private fun update(transform: (EqSettings) -> EqSettings) {
        viewModelScope.launch { settings.setEq(transform(eq.value)) }
    }
}

/** The curve the sliders show, and what Auto is doing with it. */
private fun shownGains(eq: EqSettings, status: EqStatus): List<Float> =
    status.autoCurve?.takeIf { eq.enabled && eq.auto }?.gainsDb ?: eq.bandGainsDb

private fun autoNote(eq: EqSettings, curve: EqAutoCurve?): String? = when {
    !(eq.enabled && eq.auto) -> null
    curve == null -> "AUTO · waiting for a song"
    curve.source == EqAutoSource.SONG -> "AUTO · this song's own curve"
    curve.source == EqAutoSource.GENRE -> "AUTO · ${curve.preset} (from the genre)"
    else -> "AUTO · your curve (no genre to go by)"
}

// =====================================================================
//  Screen
// =====================================================================

/**
 * The equalizer: Harmony's native engine behind a Winamp face.
 *
 * A soft glow of the section colour sits behind the header; below it the
 * two Winamp windows, Auto, the presets, and what the engine does to keep
 * the sound clean on the device playing.
 */
@Composable
fun EqualizerScreen(
    /**
     * Supplied once Equalizer stopped being a bottom-navigation destination.
     * It is now pushed on top of Settings or Now Playing, so it needs a way
     * back that isn't the system gesture alone. Null keeps the old
     * chrome-less header for any caller that still shows it as a tab.
     */
    onBack: (() -> Unit)? = null,
    viewModel: EqualizerViewModel = hiltViewModel(),
) {
    val eq by viewModel.eq.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val palette = bluePalette()
    val gains = shownGains(eq, status)
    val actions = remember(viewModel) {
        WinampEqActions(
            onToggle = viewModel::toggle,
            onAuto = viewModel::toggleAuto,
            onBand = viewModel::setBand,
            onPreamp = viewModel::setPreamp,
            onPreset = viewModel::applyPreset,
            onReset = viewModel::reset,
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.field)
            .drawBehind {
                // The header's glow: the section accent and a warm Winamp gold, fading into the page.
                drawCircle(
                    Brush.radialGradient(
                        listOf(palette.accent.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.15f, 0f),
                        radius = size.width * 0.9f,
                    ),
                    radius = size.width * 0.9f,
                    center = Offset(size.width * 0.15f, 0f),
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(WinampGold.copy(alpha = 0.14f), Color.Transparent),
                        center = Offset(size.width * 0.95f, size.width * 0.15f),
                        radius = size.width * 0.7f,
                    ),
                    radius = size.width * 0.7f,
                    center = Offset(size.width * 0.95f, size.width * 0.15f),
                )
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 20.dp, top = 14.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                EditorialCircleButton(
                    onClick = onBack,
                    contentDescription = "Back",
                    palette = palette,
                    modifier = Modifier.padding(end = 14.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Equalizer",
                    fontSize = 40.sp,
                    lineHeight = 44.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp,
                    color = palette.ink,
                )
                Text(
                    when {
                        status.bitPerfect -> "Bit-perfect — the DAC gets the file untouched"
                        !eq.enabled -> "Off — flat, with clean-output protection"
                        eq.auto -> "On — Auto picks each song's curve"
                        else -> "On — your curve"
                    },
                    fontSize = 12.sp,
                    color = palette.muted,
                )
            }
            EditorialSwitch(eq.enabled, viewModel::setEnabled, palette)
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalFloatingChromeHeight.current)
                .padding(horizontal = 16.dp),
        ) {
            WinampEqPanel(
                gains = gains,
                preampDb = eq.preampDb,
                on = eq.enabled && !status.bitPerfect,
                autoOn = eq.enabled && eq.auto,
                autoNote = autoNote(eq, status.autoCurve),
                nowPlaying = nowPlaying,
                actions = actions,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            AutoCard(eq, status, viewModel, palette)
            Spacer(Modifier.height(18.dp))
            SectionTitle("Presets", if (eq.enabled && eq.auto) "applied to this song" else "${EqPresets.ALL.size} curves", palette)
            Spacer(Modifier.height(10.dp))
            PresetGallery(gains, actions.onPreset)
            Spacer(Modifier.height(18.dp))
            SectionTitle("Clean output", status.output.label, palette)
            Spacer(Modifier.height(10.dp))
            SignalTiles(eq, gains, status, nowPlaying, palette)
            if (status.bitPerfectAvailable) {
                Spacer(Modifier.height(10.dp))
                BitPerfectRow(eq.bitPerfectUsb, status.bitPerfect, viewModel::setBitPerfect, palette)
            }
            Text(
                "Ten bands from 31 Hz to 16 kHz, ±12 dB each, run by Harmony's own engine in 32-bit float at the " +
                    "file's sample rate. It makes its own headroom as you boost, filters out what the output can't " +
                    "play (below 90 Hz on the phone speaker), and a soft saturator and a look-ahead limiter keep " +
                    "the sound from ever clipping. Tap a slider to jump, drag it, double-tap to centre.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = palette.muted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
            )
        }
    }
}

private val WinampGold = Color(0xFFE2B451)

/** What AUTO does and where this song's curve came from. */
@Composable
private fun AutoCard(eq: EqSettings, status: EqStatus, viewModel: EqualizerViewModel, palette: EditorialPalette) {
    val on = eq.enabled && eq.auto
    val curve = status.autoCurve
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.accent.copy(alpha = if (on) 0.12f else 0.06f))
            .border(1.dp, if (on) palette.accent.copy(alpha = 0.5f) else palette.line, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto", fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, color = palette.ink)
                Text(
                    when {
                        !on -> "Off — every song plays with your curve"
                        curve == null -> "On — starts with the next song"
                        curve.source == EqAutoSource.SONG -> "This song has its own curve"
                        curve.source == EqAutoSource.GENRE -> "${curve.preset}, picked from the song's genre"
                        else -> "No genre to go by: your curve"
                    },
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = palette.muted,
                )
            }
            EditorialSwitch(on, { viewModel.toggleAuto() }, palette)
        }
        Text(
            "Each song gets its own curve as it starts, blended in over 400 ms: the one you shaped for it, or " +
                "the preset for its genre. Move a slider while Auto plays a song and that song keeps it. " +
                "Heavy kicks pull the deepest bass boost back by up to 4 dB so it never turns muddy.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = palette.muted,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (on && curve?.source == EqAutoSource.SONG) {
            Text(
                "Forget this song's curve",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.accent,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClick = viewModel::forgetSongCurve)
                    .padding(vertical = 6.dp),
            )
        }
    }
}

/** Android 14+ with a USB DAC: the choice between the EQ and an untouched signal. */
@Composable
private fun BitPerfectRow(wanted: Boolean, active: Boolean, onChange: (Boolean) -> Unit, palette: EditorialPalette) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.accent.copy(alpha = 0.07f))
            .border(1.dp, palette.line, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Bit-perfect USB", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.ink)
            Text(
                when {
                    active -> "On: the DAC gets the file as it is; the EQ steps aside"
                    wanted -> "This DAC or Android build doesn't offer it"
                    else -> "Send the file to the DAC untouched instead of equalizing it"
                },
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = palette.muted,
            )
        }
        EditorialSwitch(wanted, onChange, palette)
    }
}

@Composable
private fun SectionTitle(title: String, detail: String, palette: EditorialPalette) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, color = palette.ink, modifier = Modifier.weight(1f))
        Text(detail, fontSize = 12.sp, lineHeight = 16.sp, color = palette.muted)
    }
}

/**
 * Every preset as a little card with its own curve, so they can be compared
 * at a glance. The one in use glows gold.
 */
@Composable
private fun PresetGallery(gains: List<Float>, onPreset: (String) -> Unit) {
    val current = EqPresets.matching(gains)
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // Bring the preset in use into view, as the gallery opens and when it changes.
    LaunchedEffect(current) {
        val index = EqPresets.ALL.indexOfFirst { it.first == current }
        if (index > 0) scroll.animateScrollTo(with(density) { ((118 + 10) * index - 40).dp.roundToPx() }.coerceAtLeast(0))
    }
    Row(
        Modifier.horizontalScroll(scroll).padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EqPresets.ALL.forEach { (name, preset) ->
            val selected = name == current
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier
                    .width(118.dp)
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(Color(0xFF2B2B40), Color(0xFF14141C))))
                    .border(if (selected) 2.dp else 1.dp, if (selected) WinampGold else Color(0xFF3A3A52), shape)
                    .clickable(role = Role.Button) { onPreset(name) }
                    .testTag(WinampTags.preset(name))
                    .padding(10.dp),
            ) {
                MiniCurve(preset, Modifier.fillMaxWidth().height(40.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    name,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) WinampGold else Color(0xFFE6E4D8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Headroom ${fmtDb(EqResponse.autoPreampDb(preset))}",
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF8E8EA6),
                    maxLines = 1,
                )
            }
        }
    }
}

/** A preset's curve on a tiny black display, in Winamp's red-yellow-green. */
@Composable
private fun MiniCurve(gains: List<Float>, modifier: Modifier) {
    val points = remember(gains) {
        val freqs = FloatArray(40) { i -> 25f * Math.pow(760.0, i / 39.0).toFloat() }
        EqResponse.bandsDb(gains, freqs)
    }
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
        val mid = size.height / 2f
        drawLine(Color(0xFF173D22), Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
        val path = Path()
        points.forEachIndexed { i, db ->
            val x = size.width * i / (points.size - 1f)
            val y = mid - (db / EqSettings.MAX_GAIN_DB).coerceIn(-1f, 1f) * size.height * 0.42f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            Brush.verticalGradient(listOf(Color(0xFFFF3B1F), Color(0xFFF2D21B), Color(0xFF2FD12F)), startY = 0f, endY = size.height),
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** Four readouts: the headroom taken, the high-pass for this output, the limiter's ceiling, the device. */
@Composable
private fun SignalTiles(eq: EqSettings, gains: List<Float>, status: EqStatus, nowPlaying: NowPlayingInfo?, palette: EditorialPalette) {
    val headroom = if (eq.enabled) EqResponse.autoPreampDb(gains) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SignalTile("Headroom", fmtDb(headroom), "taken back for your boosts", palette, Modifier.weight(1f))
            SignalTile("Preamp", fmtDb(if (eq.enabled) eq.preampDb else 0f), "yours, on top", palette, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SignalTile(
                "High-pass",
                "${status.output.highPassHz} Hz",
                "below it the ${status.output.label.lowercase()} only distorts",
                palette,
                Modifier.weight(1f),
            )
            SignalTile(
                "Ceiling",
                "%.2f dBFS".format(status.output.ceilingDb),
                status.outputName.ifBlank { nowPlaying?.output ?: "look-ahead limiter" },
                palette,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SignalTile(label: String, value: String, detail: String, palette: EditorialPalette, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(palette.accent.copy(alpha = 0.07f))
            .border(1.dp, palette.line, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label.uppercase(), fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold, color = palette.muted)
        Text(
            value,
            fontSize = 20.sp,
            lineHeight = 25.sp,
            fontWeight = FontWeight.Bold,
            color = palette.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(detail, fontSize = 11.sp, lineHeight = 14.sp, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun fmtDb(v: Float): String = when {
    abs(v) < 0.05f -> "0 dB"
    v > 0 -> "+${(v * 10).toInt() / 10f} dB"
    else -> "−${(-v * 10).toInt() / 10f} dB"
}
