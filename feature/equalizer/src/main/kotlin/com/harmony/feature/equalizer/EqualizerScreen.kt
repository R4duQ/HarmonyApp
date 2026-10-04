package com.harmony.feature.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import com.harmony.core.model.AutoEqSettings
import com.harmony.core.model.EqSettings
import com.harmony.core.model.EqStyle
import com.harmony.core.model.WinampEqDesign
import com.harmony.core.ui.component.EditorialCircleButton
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialSwitch
import com.harmony.core.ui.component.LocalFloatingChromeHeight
import com.harmony.core.ui.component.bluePalette
import com.harmony.domain.playback.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

/**
 * All writes go to the DataStore only; SettingsApplier pushes the change into
 * the live audio chain. The screen therefore reflects reality even if the
 * change was made elsewhere, and EQ state survives process death for free.
 *
 * Winamp is the manual equalizer; Auto (its own ViewModel) adds its
 * corrections on top.
 */
@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    val eq: StateFlow<EqSettings> = settings.settings.map { it.eq }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EqSettings(style = EqStyle.WINAMP))

    val autoEq: StateFlow<AutoEqSettings> = settings.settings.map { it.autoEq }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoEqSettings())

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

    fun setEnabled(on: Boolean) = update { it.copy(enabled = on, style = EqStyle.WINAMP) }

    fun setWinampBand(index: Int, gainDb: Float) = update { current ->
        current.copy(
            enabled = true,
            style = EqStyle.WINAMP,
            winampGainsDb = current.winampGainsDb.toMutableList()
                .also { it[index] = gainDb.coerceIn(-WinampEqDesign.MAX_DB, WinampEqDesign.MAX_DB) },
        )
    }

    fun setWinampPreamp(gainDb: Float) = update {
        it.copy(enabled = true, style = EqStyle.WINAMP, winampPreampDb = gainDb.coerceIn(-WinampEqDesign.MAX_DB, WinampEqDesign.MAX_DB))
    }

    fun applyWinampPreset(name: String) {
        val (preamp, gains) = WinampEqDesign.PRESETS.firstOrNull { it.first == name }?.second ?: return
        update { it.copy(enabled = true, style = EqStyle.WINAMP, winampPreampDb = preamp, winampGainsDb = gains.toList()) }
    }

    fun resetWinamp() = update {
        it.copy(style = EqStyle.WINAMP, winampPreampDb = 0f, winampGainsDb = List(EqSettings.BAND_COUNT) { 0f })
    }

    /** Winamp's ON button. */
    fun toggleWinamp() = update { it.copy(enabled = !it.enabled, style = EqStyle.WINAMP) }

    /**
     * Winamp's AUTO button, which once loaded a preset per song. Here it does
     * the modern version of that: Auto's song-by-song tone correction.
     */
    fun toggleAuto() {
        viewModelScope.launch {
            val s = settings.settings.first()
            val on = !s.autoEq.tone
            settings.setAutoEq(s.autoEq.copy(tone = on))
            if (on && !s.eq.enabled) settings.setEq(s.eq.copy(enabled = true, style = EqStyle.WINAMP))
        }
    }

    private fun update(transform: (EqSettings) -> EqSettings) {
        viewModelScope.launch { settings.setEq(transform(eq.value)) }
    }
}

// =====================================================================
//  Screen
// =====================================================================

/**
 * The equalizer: Winamp's, and Auto on top of it.
 *
 * A soft glow of the section colour sits behind the header, and the two
 * tabs are a segmented switch rather than big text tabs: there are only two,
 * and each is a whole instrument.
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
    /** The Auto tab; a slot so previews can show it without the microphone and player behind it. */
    autoTab: @Composable (EditorialPalette) -> Unit = { AutoTab(it) },
) {
    val eq by viewModel.eq.collectAsStateWithLifecycle()
    val auto by viewModel.autoEq.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(WINAMP_TAB) }
    val palette = bluePalette()

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
                .padding(start = 22.dp, end = 20.dp, top = 14.dp),
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
                        !eq.enabled -> "Off — audio passes through untouched"
                        auto.any -> "On — Winamp with Auto on top"
                        else -> "On — playing through Winamp's EQ"
                    },
                    fontSize = 12.sp,
                    color = palette.muted,
                )
            }
            EditorialSwitch(eq.enabled, viewModel::setEnabled, palette)
        }

        SegmentedTabs(
            selected = tab,
            onSelect = { tab = it },
            autoOn = eq.enabled && auto.any,
            winampOn = eq.enabled,
            palette = palette,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )

        when (tab) {
            WINAMP_TAB -> WinampTab(eq, viewModel, palette)
            AUTO_TAB -> autoTab(palette)
        }
    }
}

private const val WINAMP_TAB = 0
private const val AUTO_TAB = 1
private val WinampGold = Color(0xFFE2B451)

/** Winamp | Auto, each with an icon and a small light when it's working. */
@Composable
private fun SegmentedTabs(
    selected: Int,
    onSelect: (Int) -> Unit,
    autoOn: Boolean,
    winampOn: Boolean,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.ink.copy(alpha = 0.06f))
            .border(1.dp, palette.line, shape)
            .padding(4.dp),
    ) {
        TabPill("Winamp", Icons.Rounded.Tune, selected == WINAMP_TAB, winampOn, palette, Modifier.weight(1f)) { onSelect(WINAMP_TAB) }
        TabPill("Auto", Icons.Rounded.AutoAwesome, selected == AUTO_TAB, autoOn, palette, Modifier.weight(1f)) { onSelect(AUTO_TAB) }
    }
}

@Composable
private fun TabPill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    lit: Boolean,
    palette: EditorialPalette,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(
                if (selected) Brush.horizontalGradient(listOf(palette.accent, palette.accent.copy(alpha = 0.78f)))
                else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)),
            )
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (selected) palette.onAccent else palette.ink.copy(alpha = 0.75f)
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink, modifier = Modifier.padding(start = 8.dp))
        if (lit) {
            Box(
                Modifier
                    .padding(start = 8.dp)
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) palette.onAccent else Color(0xFF3CDB5A)),
            )
        }
    }
}

// =====================================================================
//  WINAMP tab
// =====================================================================

@Composable
private fun WinampTab(eq: EqSettings, viewModel: EqualizerViewModel, palette: EditorialPalette) {
    val auto by viewModel.autoEq.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val actions = remember(viewModel) {
        WinampEqActions(
            onToggle = viewModel::toggleWinamp,
            onAuto = viewModel::toggleAuto,
            onBand = viewModel::setWinampBand,
            onPreamp = viewModel::setWinampPreamp,
            onPreset = viewModel::applyWinampPreset,
            onReset = viewModel::resetWinamp,
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalFloatingChromeHeight.current)
            .padding(horizontal = 16.dp),
    ) {
        WinampEqPanel(eq, auto.tone, nowPlaying, actions, Modifier.fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        SectionTitle("Presets", "${WinampEqDesign.PRESETS.size} from Winamp", palette)
        Spacer(Modifier.height(10.dp))
        PresetGallery(eq, actions.onPreset)
        Spacer(Modifier.height(18.dp))
        SectionTitle("Signal", if (eq.enabled) "What the EQ does now" else "Equalizer off", palette)
        Spacer(Modifier.height(10.dp))
        SignalTiles(eq, nowPlaying, palette)
        Text(
            "Winamp's own equalizer: ten one-octave bands from 60 Hz to 16 kHz, ±20 dB each, mixed in parallel " +
                "the way Winamp did it, with its preamp and presets. Tap a slider to jump, drag it, double-tap to " +
                "centre. AUTO switches on Auto's song-by-song tone on top.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = palette.muted,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
        )
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
 * Every Winamp preset as a little card with its own curve, so they can be
 * compared at a glance. The one in use glows gold.
 */
@Composable
private fun PresetGallery(eq: EqSettings, onPreset: (String) -> Unit) {
    val current = currentPreset(eq)
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // Bring the preset in use into view, as the gallery opens and when it changes.
    LaunchedEffect(current) {
        val index = WinampEqDesign.PRESETS.indexOfFirst { it.first == current }
        if (index > 0) scroll.animateScrollTo(with(density) { ((118 + 10) * index - 40).dp.roundToPx() }.coerceAtLeast(0))
    }
    Row(
        Modifier.horizontalScroll(scroll).padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        WinampEqDesign.PRESETS.forEach { (name, preset) ->
            val (preamp, gains) = preset
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
                MiniCurve(gains, preamp, Modifier.fillMaxWidth().height(40.dp))
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
                    "Preamp ${fmtDb(preamp)}",
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
private fun MiniCurve(gains: List<Float>, preamp: Float, modifier: Modifier) {
    val points = remember(gains, preamp) {
        val freqs = FloatArray(40) { i -> 30f * 600f.let { r -> Math.pow(r.toDouble(), i / 39.0).toFloat() } }
        WinampEqDesign.responseDb(gains, 0f, freqs)
    }
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
        val mid = size.height / 2f
        drawLine(Color(0xFF173D22), Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
        val path = Path()
        points.forEachIndexed { i, db ->
            val x = size.width * i / (points.size - 1f)
            val y = mid - (db / 20f).coerceIn(-1f, 1f) * size.height * 0.42f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            Brush.verticalGradient(listOf(Color(0xFFFF3B1F), Color(0xFFF2D21B), Color(0xFF2FD12F)), startY = 0f, endY = size.height),
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** Four readouts: preamp, the strongest boost and cut, and the device it's playing on. */
@Composable
private fun SignalTiles(eq: EqSettings, nowPlaying: NowPlayingInfo?, palette: EditorialPalette) {
    val gains = eq.winampGainsDb
    val up = gains.indices.maxByOrNull { gains[it] }
    val down = gains.indices.minByOrNull { gains[it] }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SignalTile("Preamp", fmtDb(eq.winampPreampDb), "applied after the bands", palette, Modifier.weight(1f))
            SignalTile(
                "Biggest boost",
                if (up != null && gains[up] > 0.05f) fmtDb(gains[up]) else "—",
                if (up != null && gains[up] > 0.05f) "at ${bandName(up)}" else "no band raised",
                palette,
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SignalTile(
                "Biggest cut",
                if (down != null && gains[down] < -0.05f) fmtDb(gains[down]) else "—",
                if (down != null && gains[down] < -0.05f) "at ${bandName(down)}" else "no band lowered",
                palette,
                Modifier.weight(1f),
            )
            SignalTile("Output", nowPlaying?.output ?: "—", if (nowPlaying?.playing == true) "playing now" else "idle", palette, Modifier.weight(1f))
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

/** "60 Hz", "12 kHz". */
private fun bandName(i: Int): String {
    val f = WinampEqDesign.FREQUENCIES_HZ[i]
    return if (f >= 1000f) "${(f / 1000f).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }} kHz" else "${f.toInt()} Hz"
}

private fun fmtDb(v: Float): String = when {
    abs(v) < 0.05f -> "0 dB"
    v > 0 -> "+${(v * 10).toInt() / 10f} dB"
    else -> "−${(-v * 10).toInt() / 10f} dB"
}
