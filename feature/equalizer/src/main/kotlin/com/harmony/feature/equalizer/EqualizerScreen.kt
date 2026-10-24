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
import com.harmony.core.model.EqPresets
import com.harmony.core.ui.component.EditorialCircleButton
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialSwitch
import com.harmony.core.ui.component.LocalFloatingChromeHeight
import com.harmony.core.ui.component.bluePalette
import com.harmony.domain.playback.EqLive
import com.harmony.domain.playback.EqStatus
import com.harmony.domain.playback.EqOutput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * All writes go to the DataStore only; SettingsApplier pushes the change into
 * the live audio chain. The screen therefore reflects reality even if the
 * change was made elsewhere, and EQ state survives process death for free.
 *
 * The sliders are always the listener's own. What the engine plays on top of
 * them (Auto's live offsets, the tamers) is drawn live by
 * [WinampLiveEqualizerScreen] from the engine's telemetry, never stored.
 */
@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val eq: StateFlow<EqSettings> = settings.settings.map { it.eq }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EqSettings())

    /** What the engine is doing: the output, its rate, bit-perfect. */
    val status: StateFlow<EqStatus> = EqLive.status

    fun setEnabled(on: Boolean) = update { it.copy(enabled = on) }

    /** EQ ON. */
    fun toggle() = update { it.copy(enabled = !it.enabled) }

    /** AUTO LIVE; switching it on switches the equalizer on too. */
    fun toggleAuto() = update { it.copy(auto = !it.auto, enabled = it.enabled || !it.auto) }

    fun setBand(index: Int, gainDb: Float) = update { current ->
        current.copy(
            enabled = true,
            bandGainsDb = EqSettings.sanitize(
                current.bandGainsDb.toMutableList().also {
                    it[index] = gainDb.coerceIn(-EqSettings.MAX_GAIN_DB, EqSettings.MAX_GAIN_DB)
                },
            ),
        )
    }

    fun applyPreset(name: String) {
        val preset = EqPresets.named(name) ?: return
        update { it.copy(enabled = true, bandGainsDb = preset) }
    }

    /** FLAT: every slider and the preamp at 0. Auto keeps listening. */
    fun flat() = update { it.copy(bandGainsDb = EqSettings.FLAT, preampDb = 0f) }

    fun setBitPerfect(on: Boolean) = update { it.copy(bitPerfectUsb = on) }

    private fun update(transform: (EqSettings) -> EqSettings) {
        viewModelScope.launch { settings.setEq(transform(eq.value)) }
    }
}

// =====================================================================
//  Screen
// =====================================================================

/**
 * The equalizer: Harmony's native engine behind a Winamp face. The live
 * window ([WinampLiveEqualizerScreen]) on top, then what Auto does, the
 * presets, and how the engine keeps the sound clean on the device playing.
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
                        eq.auto -> "On — Auto calibrates to what it hears, live"
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
            WinampLiveEqualizerScreen(
                eq = eq,
                status = status,
                onToggleEq = viewModel::toggle,
                onToggleAuto = viewModel::toggleAuto,
                onBand = viewModel::setBand,
                onFlat = viewModel::flat,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            AutoCard(eq, status, viewModel, palette)
            Spacer(Modifier.height(18.dp))
            SectionTitle("Presets", "${EqPresets.ALL.size} curves for your sliders", palette)
            Spacer(Modifier.height(10.dp))
            PresetGallery(eq.bandGainsDb, viewModel::applyPreset)
            Spacer(Modifier.height(18.dp))
            SectionTitle("Clean output", status.output.label, palette)
            Spacer(Modifier.height(10.dp))
            SignalTiles(status, palette)
            if (status.bitPerfectAvailable) {
                Spacer(Modifier.height(10.dp))
                BitPerfectRow(eq.bitPerfectUsb, status.bitPerfect, viewModel::setBitPerfect, palette)
            }
            Text(
                "Ten bands from 31 Hz to 16 kHz, ±12 dB each, run by Harmony's own engine in 32-bit float at the " +
                    "file's sample rate. Everything reacts to what the music sounds like right now: the preamp " +
                    "takes only the headroom the boosted bands need for the energy actually in them, the high-pass " +
                    "and the ceiling follow the output, and a soft saturator and a look-ahead limiter keep the sound " +
                    "from ever clipping. Drag a slider, tap to jump, double-tap to centre. The white thumb is yours; " +
                    "the glowing one is what plays.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = palette.muted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
            )
        }
    }
}

private val WinampGold = Color(0xFFE2B451)

/** What AUTO LIVE does, and what it aims at on this output. */
@Composable
private fun AutoCard(eq: EqSettings, status: EqStatus, viewModel: EqualizerViewModel, palette: EditorialPalette) {
    val on = eq.enabled && eq.auto
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
                Text("Auto live", fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, color = palette.ink)
                Text(
                    if (on) "Aiming at: ${autoTarget(status.output)}" else "Off — the sliders play as set",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = palette.muted,
                )
            }
            EditorialSwitch(on, { viewModel.toggleAuto() }, palette)
        }
        Text(
            "Auto listens to the music as it plays, in the ten bands, about every 1.2 seconds, and compares " +
                "its balance with a target for the output. It lifts thin sub-bass and air by up to 4.5 dB and " +
                "takes out boxy mid-bass and harsh upper mids by up to 4.5 dB, gliding on top of your sliders. " +
                "In silence and quiet intros (under −42 dBFS) it holds still, so it never lifts noise. Heavy " +
                "kicks pull the bass boost back and sharp sibilance the 4–8 kHz boost, within milliseconds. " +
                "It never reads tags or genres.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = palette.muted,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

private fun autoTarget(output: EqOutput): String = when (output) {
    EqOutput.PHONE_SPEAKER -> "midrange-focused, no sub-bass lift (phone speaker)"
    else -> "warm −4.5 dB/octave (${output.label.lowercase()})"
}

/**
 * Every preset as a little card with its curve, so they can be compared at a
 * glance. The one the sliders match glows gold.
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
            }
        }
    }
}

/** A preset's ten points joined by a smooth curve, on a tiny black display. */
@Composable
private fun MiniCurve(gains: List<Float>, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
        val mid = size.height / 2f
        drawLine(Color(0xFF173D22), Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
        val n = gains.size
        val xs = FloatArray(n) { size.width * (it + 0.5f) / n }
        val ys = FloatArray(n) { mid - (gains[it] / EqSettings.MAX_GAIN_DB).coerceIn(-1f, 1f) * size.height * 0.42f }
        val path = Path().apply {
            moveTo(xs[0], ys[0])
            for (i in 0 until n - 1) {
                val i0 = (i - 1).coerceAtLeast(0)
                val i3 = (i + 2).coerceAtMost(n - 1)
                cubicTo(
                    xs[i] + (xs[i + 1] - xs[i0]) / 6f, ys[i] + (ys[i + 1] - ys[i0]) / 6f,
                    xs[i + 1] - (xs[i3] - xs[i]) / 6f, ys[i + 1] - (ys[i3] - ys[i]) / 6f,
                    xs[i + 1], ys[i + 1],
                )
            }
        }
        drawPath(
            path,
            Brush.verticalGradient(listOf(Color(0xFFFF3B1F), Color(0xFFF2D21B), Color(0xFF2FD12F)), startY = 0f, endY = size.height),
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** Four readouts: the high-pass and ceiling for this output, Auto's target, the device. */
@Composable
private fun SignalTiles(status: EqStatus, palette: EditorialPalette) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SignalTile("High-pass", "${status.output.highPassHz} Hz", "rumble the output can't play", palette, Modifier.weight(1f))
            SignalTile("Ceiling", "%.2f dBFS".format(status.output.ceilingDb), "look-ahead limiter", palette, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SignalTile(
                "Auto target",
                if (status.output == EqOutput.PHONE_SPEAKER) "Midrange" else "−4.5 dB/oct",
                if (status.output == EqOutput.PHONE_SPEAKER) "no sub-bass lift" else "warm, full sub-bass",
                palette,
                Modifier.weight(1f),
            )
            SignalTile("Output", hardwareBadge(status).substringBefore(" •"), status.outputName.ifBlank { "this device" }, palette, Modifier.weight(1f))
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
