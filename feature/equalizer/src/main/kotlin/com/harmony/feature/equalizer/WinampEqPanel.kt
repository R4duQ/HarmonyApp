package com.harmony.feature.equalizer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.model.EqSettings
import com.harmony.core.model.WinampEqDesign
import com.harmony.domain.playback.AudioLevels
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** The song as Winamp's main window shows it. */
data class NowPlayingInfo(
    val title: String,
    val artist: String,
    val durationMs: Long,
    val kbps: Int?,
    val sampleRateHz: Int?,
    val channels: Int?,
    val playing: Boolean,
    /** Where it's playing, as the listener knows the device. */
    val output: String,
)

/** What the Winamp panel can ask for. */
class WinampEqActions(
    val onToggle: () -> Unit = {},
    val onAuto: () -> Unit = {},
    val onBand: (index: Int, gainDb: Float) -> Unit = { _, _ -> },
    val onPreamp: (gainDb: Float) -> Unit = {},
    val onPreset: (name: String) -> Unit = {},
    val onReset: () -> Unit = {},
)

internal object WinampTags {
    const val ON = "winamp_on"
    const val AUTO = "winamp_auto"
    const val PRESETS = "winamp_presets"
    const val PREAMP = "winamp_preamp"
    const val MAIN = "winamp_main"
    const val ANALYZER = "winamp_analyzer"
    fun band(index: Int) = "winamp_band_$index"
    fun preset(name: String) = "winamp_preset_$name"
}

/**
 * Winamp, as two of its windows stacked the way people docked them:
 *
 *  - the main window: the song scrolling across a green LCD, its bitrate,
 *    sample rate and stereo lights, and the spectrum analyser with falling
 *    peaks (measured live from what reaches the speaker);
 *  - the equalizer: ON, AUTO, PRESETS, the black display with the filters'
 *    real response, the preamp and ten sliders whose LED ladders light from
 *    green through yellow to red as they rise.
 *
 * Stateless: the screen owns the settings and passes them in.
 */
@Composable
fun WinampEqPanel(
    eq: EqSettings,
    autoOn: Boolean,
    nowPlaying: NowPlayingInfo?,
    actions: WinampEqActions,
    modifier: Modifier = Modifier,
    spectrum: StateFlow<FloatArray> = AudioLevels.spectrum,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MainWindow(nowPlaying, spectrum, Modifier.fillMaxWidth().testTag(WinampTags.MAIN))
        EqWindow(eq, eq.enabled, autoOn, actions, Modifier.fillMaxWidth())
    }
}

/** The name of the Winamp preset these settings match, if any. */
internal fun currentPreset(eq: EqSettings): String? = WinampEqDesign.PRESETS.firstOrNull { (_, preset) ->
    val (preamp, gains) = preset
    abs(preamp - eq.winampPreampDb) < 0.05f &&
        gains.indices.all { abs(gains[it] - eq.winampGainsDb.getOrElse(it) { 0f }) < 0.05f }
}?.first

private object Skin {
    val PanelTop = Color(0xFF3E3E58)
    val PanelMid = Color(0xFF2A2A3D)
    val PanelBottom = Color(0xFF16161F)
    val BevelLight = Color(0xFF7474A0)
    val BevelDark = Color(0xFF07070B)
    val Display = Color(0xFF000000)
    val Grid = Color(0xFF123A1C)
    val GridText = Color(0xFF2E8C46)
    val Lcd = Color(0xFF00E05A)
    val LcdDim = Color(0xFF0B3A1C)
    val Red = Color(0xFFFF3B1F)
    val Yellow = Color(0xFFF2D21B)
    val Green = Color(0xFF2FD12F)
    val Gold = Color(0xFFE2B451)
    val Text = Color(0xFFE6E4D8)
    val Dim = Color(0xFF8E8EA6)
    val Led = Color(0xFF3CFF3C)
    val LedOff = Color(0xFF1C2E1C)
    val Groove = Color(0xFF0B0B11)
    val ThumbTop = Color(0xFFEDEDF5)
    val ThumbBottom = Color(0xFF8C8C9E)
}

/** A Winamp window: brushed gunmetal with faint scanlines and a bevelled rim. */
private fun Modifier.skinWindow(): Modifier {
    val shape = RoundedCornerShape(18.dp)
    return this
        .shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(shape)
        .background(Brush.verticalGradient(listOf(Skin.PanelTop, Skin.PanelMid, Skin.PanelBottom)))
        .drawBehind {
            // Scanlines: the brushed look of the old skin.
            val step = 3.dp.toPx()
            var y = 0f
            while (y < size.height) {
                drawLine(Color.White.copy(alpha = 0.025f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += step
            }
        }
        .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(Skin.BevelLight, Skin.BevelDark))), shape)
        .padding(12.dp)
}

@Composable
private fun TitleBar(title: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Stripes(Modifier.weight(1f).height(9.dp))
        Text(
            title,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = Skin.Text,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Stripes(Modifier.weight(1f).height(9.dp))
        // The window buttons, for the look of it.
        Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFF6A6A88), Color(0xFF2A2A3A))))
                        .border(0.5.dp, Skin.BevelDark, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

/** The ridged gold bars either side of a Winamp title. */
@Composable
private fun Stripes(modifier: Modifier) {
    Canvas(modifier) {
        val step = size.height / 3f
        for (k in 0 until 3) {
            val y = step * k + step / 2f
            drawLine(Skin.Gold.copy(alpha = 0.9f - k * 0.22f), Offset(0f, y), Offset(size.width, y), strokeWidth = step * 0.55f)
        }
    }
}

// ---------------------------------------------------------------------------
// Main window
// ---------------------------------------------------------------------------

@Composable
private fun MainWindow(nowPlaying: NowPlayingInfo?, spectrum: StateFlow<FloatArray>, modifier: Modifier) {
    Column(modifier.skinWindow()) {
        TitleBar("WINAMP")
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(92.dp)) {
            // Left: play state and the song's length, in LCD digits.
            Column(
                Modifier
                    .width(112.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Skin.Display)
                    .border(1.dp, Skin.BevelDark, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StateGlyph(nowPlaying)
                    Text(
                        nowPlaying?.let { lcdTime(it.durationMs) } ?: "--:--",
                        fontSize = 24.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Skin.Lcd,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    if (nowPlaying == null) "NO TRACK" else "TRACK LENGTH",
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Skin.LcdDim.let { lerp(it, Skin.Lcd, 0.45f) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val stereo = (nowPlaying?.channels ?: 2) >= 2
                    LcdLight("MONO", nowPlaying != null && !stereo)
                    LcdLight("STEREO", nowPlaying != null && stereo)
                }
            }
            Spacer(Modifier.width(8.dp))
            Analyzer(spectrum, nowPlaying?.playing == true, Modifier.weight(1f).fillMaxHeight().testTag(WinampTags.ANALYZER))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Marquee(
                nowPlaying?.let { "${it.artist} - ${it.title} (${lcdTime(it.durationMs)})" }
                    ?: "Harmony · Winamp equalizer · pick a song and move the sliders",
                Modifier.weight(1f).height(24.dp),
            )
            Spacer(Modifier.width(6.dp))
            LcdBox(nowPlaying?.kbps?.toString() ?: "---", "kbps")
            Spacer(Modifier.width(4.dp))
            LcdBox(nowPlaying?.sampleRateHz?.let { "${it / 1000}" } ?: "--", "kHz")
        }
        if (nowPlaying != null) {
            Text(
                "▸ ${nowPlaying.output}",
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = Skin.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp),
            )
        }
    }
}

@Composable
private fun StateGlyph(nowPlaying: NowPlayingInfo?) {
    Canvas(Modifier.size(16.dp)) {
        val c = if (nowPlaying == null) Skin.LcdDim else Skin.Lcd
        when {
            nowPlaying?.playing == true -> {
                val p = Path().apply {
                    moveTo(size.width * 0.15f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(size.width * 0.15f, size.height)
                    close()
                }
                drawPath(p, c)
            }
            nowPlaying != null -> {
                drawRect(c, Offset(size.width * 0.1f, 0f), Size(size.width * 0.3f, size.height))
                drawRect(c, Offset(size.width * 0.6f, 0f), Size(size.width * 0.3f, size.height))
            }
            else -> drawRect(c, Offset(size.width * 0.1f, size.height * 0.1f), Size(size.width * 0.8f, size.height * 0.8f))
        }
    }
}

@Composable
private fun LcdLight(label: String, lit: Boolean) {
    Text(
        label,
        fontSize = 8.sp,
        lineHeight = 10.sp,
        letterSpacing = 0.5.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        color = if (lit) Skin.Lcd else Skin.LcdDim,
    )
}

@Composable
private fun LcdBox(value: String, unit: String) {
    Row(
        Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Skin.Display)
            .border(1.dp, Skin.BevelDark, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Skin.Lcd)
        Text(" $unit", fontSize = 8.sp, lineHeight = 10.sp, fontFamily = FontFamily.Monospace, color = Skin.Dim)
    }
}

/** The song's name sliding across the LCD, round and round, as Winamp's did. */
@Composable
private fun Marquee(text: String, modifier: Modifier) {
    val shown = "${text.uppercase()}   ***   "
    val t = rememberInfiniteTransition(label = "marquee")
    val shift by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween((shown.length * 220).coerceAtLeast(4000), easing = LinearEasing)),
        label = "shift",
    )
    Box(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Skin.Display)
            .border(1.dp, Skin.BevelDark, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Two copies side by side; the pair slides by one copy's width and wraps.
        val copy = CHAR_WIDTH * shown.length
        Row(
            Modifier
                .padding(start = 6.dp)
                .graphicsLayer { translationX = -copy.toPx() * shift },
        ) {
            repeat(2) {
                Text(
                    shown,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Skin.Lcd,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.width(copy),
                )
            }
        }
    }
}

/**
 * Winamp's spectrum analyser: twenty bars (two per octave band, the second
 * eased towards the next band), coloured green to red by height, each with
 * a peak cap that hangs a moment and then falls.
 */
@Composable
private fun Analyzer(spectrum: StateFlow<FloatArray>, playing: Boolean, modifier: Modifier) {
    val bars = 20
    var heights by remember { mutableStateOf(FloatArray(bars)) }
    var peaks by remember { mutableStateOf(FloatArray(bars)) }
    val playingNow by rememberUpdatedState(playing)
    LaunchedEffect(spectrum) {
        var last = System.nanoTime()
        spectrum.collect { bands ->
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceIn(0f, 0.2f)
            last = now
            val next = FloatArray(bars) { i ->
                val b = i / 2
                val v = bands.getOrElse(b) { 0f }
                val target = if (i % 2 == 0) v else (v + bands.getOrElse(b + 1) { v }) / 2f
                if (playingNow) target else 0f
            }
            val h = heights
            val p = peaks
            heights = FloatArray(bars) { i -> if (next[i] > h[i]) next[i] else (h[i] - dt * 1.6f).coerceAtLeast(next[i]) }
            peaks = FloatArray(bars) { i -> if (next[i] >= p[i]) next[i] else (p[i] - dt * 0.55f).coerceAtLeast(0f) }
        }
    }
    Canvas(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Skin.Display)
            .border(1.dp, Skin.BevelDark, RoundedCornerShape(8.dp))
            .semantics { contentDescription = "Spectrum analyser" },
    ) {
        val pad = 6.dp.toPx()
        val gap = 2.dp.toPx()
        val w = (size.width - 2 * pad - gap * (bars - 1)) / bars
        val usable = size.height - 2 * pad
        // Dim cells under the bars, like the LCD's unlit segments.
        val segment = 3.dp.toPx()
        for (i in 0 until bars) {
            val x = pad + i * (w + gap)
            var y = size.height - pad - segment
            while (y > pad) {
                drawRect(Skin.LcdDim.copy(alpha = 0.45f), Offset(x, y), Size(w, segment - 1f))
                y -= segment
            }
            val h = heights[i] * usable
            if (h > 0.5f) {
                drawRect(
                    Brush.verticalGradient(listOf(Skin.Red, Skin.Yellow, Skin.Green), startY = pad, endY = size.height - pad),
                    Offset(x, size.height - pad - h),
                    Size(w, h),
                )
            }
            val peakY = size.height - pad - peaks[i] * usable
            drawRect(Color(0xFFD8D8E8), Offset(x, (peakY - 2.dp.toPx()).coerceAtLeast(pad)), Size(w, 1.5.dp.toPx()))
        }
    }
}

private fun lcdTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${(s / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
}

// ---------------------------------------------------------------------------
// Equalizer window
// ---------------------------------------------------------------------------

@Composable
private fun EqWindow(eq: EqSettings, on: Boolean, autoOn: Boolean, actions: WinampEqActions, modifier: Modifier) {
    Column(modifier.skinWindow()) {
        TitleBar("WINAMP EQUALIZER")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LedButton("ON", on, actions.onToggle, Modifier.testTag(WinampTags.ON), if (on) "Winamp equalizer on" else "Winamp equalizer off")
            LedButton("AUTO", autoOn, actions.onAuto, Modifier.testTag(WinampTags.AUTO), if (autoOn) "Auto tone on" else "Auto tone off")
            Spacer(Modifier.weight(1f))
            PresetsButton(currentPreset(eq), actions.onPreset)
            SkinButton("RESET", onClick = actions.onReset)
        }
        Spacer(Modifier.height(10.dp))
        ResponseDisplay(
            gains = eq.winampGainsDb,
            preampDb = eq.winampPreampDb,
            lit = on,
            modifier = Modifier.fillMaxWidth().height(96.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(224.dp)) {
            DbScale(Modifier.width(28.dp).fillMaxHeight())
            WinampSlider(
                value = eq.winampPreampDb,
                label = "PREAMP",
                lit = on,
                onChange = actions.onPreamp,
                modifier = Modifier.weight(1.25f).testTag(WinampTags.PREAMP),
            )
            // The gap Winamp left between the preamp and the bands.
            Box(Modifier.padding(horizontal = 3.dp).width(1.dp).fillMaxHeight().background(Skin.BevelDark.copy(alpha = 0.8f)))
            WinampEqDesign.LABELS.forEachIndexed { i, label ->
                WinampSlider(
                    value = eq.winampGainsDb.getOrElse(i) { 0f },
                    label = label,
                    lit = on,
                    onChange = { actions.onBand(i, it) },
                    modifier = Modifier.weight(1f).testTag(WinampTags.band(i)),
                )
            }
        }
    }
}

/** A bevelled Winamp button with a little light in it. */
@Composable
private fun LedButton(text: String, lit: Boolean, onClick: () -> Unit, modifier: Modifier, description: String) {
    SkinButton(text, onClick = onClick, modifier = modifier.semantics { contentDescription = description }, leading = {
        Box(
            Modifier
                .padding(end = 6.dp)
                .size(9.dp)
                .shadow(if (lit) 6.dp else 0.dp, CircleShape, ambientColor = Skin.Led, spotColor = Skin.Led)
                .clip(CircleShape)
                .background(if (lit) Skin.Led else Skin.LedOff),
        )
    })
}

@Composable
private fun PresetsButton(current: String?, onPreset: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SkinButton(
            current?.uppercase() ?: "PRESETS",
            onClick = { open = true },
            modifier = Modifier.testTag(WinampTags.PRESETS),
            trailing = "▾",
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            WinampEqDesign.PRESETS.forEach { (name, _) ->
                DropdownMenuItem(
                    text = { Text(if (name == current) "✓  $name" else name) },
                    onClick = { open = false; onPreset(name) },
                )
            }
        }
    }
}

/** A small bevelled Winamp button: light top edge, dark bottom edge. */
@Composable
private fun SkinButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF55557A), Color(0xFF26263A))))
            .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(Skin.BevelLight, Skin.BevelDark))), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(
            text, fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, color = Skin.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            Text(trailing, fontSize = 10.sp, lineHeight = 12.sp, color = Skin.Dim, modifier = Modifier.padding(start = 5.dp))
        }
    }
}

/** Winamp's colour for a level from -1 (bottom) to +1 (top): green, yellow, red. */
private fun levelColor(t: Float): Color {
    val u = ((t + 1f) / 2f).coerceIn(0f, 1f)
    return if (u < 0.5f) lerp(Skin.Green, Skin.Yellow, u * 2f) else lerp(Skin.Yellow, Skin.Red, (u - 0.5f) * 2f)
}

/**
 * The black display: the filters' real response from 20 Hz to 20 kHz with
 * a glow under it, the preamp line, dB marks, and a dot on each band.
 */
@Composable
private fun ResponseDisplay(gains: List<Float>, preampDb: Float, lit: Boolean, modifier: Modifier) {
    val frequencies = remember { FloatArray(CURVE_POINTS) { i -> 20f * 1000f.pow(i / (CURVE_POINTS - 1f)) } }
    val curve = remember(gains, preampDb) { WinampEqDesign.responseDb(gains, preampDb, frequencies) }
    val bandDots = remember(gains, preampDb) { WinampEqDesign.responseDb(gains, preampDb, WinampEqDesign.FREQUENCIES_HZ) }
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.clip(shape).background(Skin.Display).border(BorderStroke(1.dp, Skin.BevelDark), shape)) {
        Canvas(Modifier.fillMaxSize()) {
            val mid = size.height / 2f
            val span = size.height * 0.42f
            fun yFor(db: Float) = mid - (db / WinampEqDesign.MAX_DB).coerceIn(-1.15f, 1.15f) * span
            fun xFor(f: Float) = size.width * (kotlin.math.log10(f / 20f) / 3f)
            // Grid: 0 dB, ±10 dB, and the bands.
            drawLine(Skin.Grid, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.5f)
            for (db in listOf(-10f, 10f)) drawLine(Skin.Grid.copy(alpha = 0.6f), Offset(0f, yFor(db)), Offset(size.width, yFor(db)), strokeWidth = 1f)
            WinampEqDesign.FREQUENCIES_HZ.forEach { f ->
                drawLine(Skin.Grid.copy(alpha = 0.6f), Offset(xFor(f), 0f), Offset(xFor(f), size.height), strokeWidth = 1f)
            }
            // Preamp level.
            drawLine(Skin.Yellow.copy(alpha = if (lit) 0.45f else 0.2f), Offset(0f, yFor(preampDb)), Offset(size.width, yFor(preampDb)), strokeWidth = 1.5f)
            val path = Path()
            curve.forEachIndexed { i, db ->
                val x = size.width * i / (CURVE_POINTS - 1f)
                if (i == 0) path.moveTo(x, yFor(db)) else path.lineTo(x, yFor(db))
            }
            val alpha = if (lit) 1f else 0.35f
            // The area between the curve and 0 dB, softly lit.
            val fill = Path().apply {
                addPath(path)
                lineTo(size.width, mid)
                lineTo(0f, mid)
                close()
            }
            drawPath(
                fill,
                Brush.verticalGradient(
                    listOf(Skin.Red.copy(alpha = 0.24f * alpha), Skin.Yellow.copy(alpha = 0.10f * alpha), Skin.Green.copy(alpha = 0.24f * alpha)),
                    startY = mid - span, endY = mid + span,
                ),
            )
            val brush = Brush.verticalGradient(listOf(Skin.Red, Skin.Yellow, Skin.Green), startY = mid - span, endY = mid + span)
            // Glow, then the line.
            drawPath(path, brush, alpha = 0.25f * alpha, style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round))
            drawPath(path, brush, alpha = alpha, style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round))
            WinampEqDesign.FREQUENCIES_HZ.forEachIndexed { i, f ->
                val y = yFor(bandDots[i])
                drawCircle(Color.Black, radius = 3.6.dp.toPx(), center = Offset(xFor(f), y))
                drawCircle(levelColor(gains.getOrElse(i) { 0f } / WinampEqDesign.MAX_DB).copy(alpha = alpha), radius = 2.6.dp.toPx(), center = Offset(xFor(f), y))
            }
        }
        Text(
            "+20", fontSize = 8.sp, lineHeight = 9.sp, fontFamily = FontFamily.Monospace, color = Skin.GridText,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp),
        )
        Text(
            "-20", fontSize = 8.sp, lineHeight = 9.sp, fontFamily = FontFamily.Monospace, color = Skin.GridText,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun DbScale(modifier: Modifier) {
    // Lined up with the slider travel: value text + gap + half a thumb above, label + gap + half a thumb below.
    Column(modifier.padding(top = 15.5.dp, bottom = 16.5.dp), verticalArrangement = Arrangement.SpaceBetween) {
        listOf("+20", "+10", "0", "-10", "-20").forEach {
            Text(it, fontSize = 9.sp, lineHeight = 11.sp, fontFamily = FontFamily.Monospace, color = Skin.Dim)
        }
    }
}

/**
 * One Winamp slider, -20..+20 dB. Drag, or tap a spot to jump there;
 * double-tap puts it back to 0. The groove is a ladder of LED cells that
 * light from the centre to the setting, green near 0 and red at the top.
 */
@Composable
private fun WinampSlider(
    value: Float,
    label: String,
    lit: Boolean,
    onChange: (Float) -> Unit,
    modifier: Modifier,
) {
    val max = WinampEqDesign.MAX_DB
    // The gesture handlers outlive recompositions; read the latest callback through this.
    val change by rememberUpdatedState(onChange)
    Column(modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            fmtWinamp(value), fontSize = 8.sp, lineHeight = 10.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = when {
                value == 0f -> Skin.Dim
                lit -> levelColor(value / max)
                else -> Skin.Text
            },
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .semantics {
                    contentDescription = "$label, ${fmtWinamp(value)} decibels"
                    progressBarRangeInfo = ProgressBarRangeInfo(value, -max..max)
                    setProgress { target -> change(target.coerceIn(-max, max)); true }
                }
                .pointerInput(Unit) { sliderGestures { change(it) } },
        ) {
            val pad = THUMB_HALF.toPx()
            val usable = size.height - 2 * pad
            val cx = size.width / 2f
            val grooveW = (size.width * 0.36f).coerceIn(6.dp.toPx(), 10.dp.toPx())
            val t = value / max
            drawRoundRect(
                Skin.Groove,
                topLeft = Offset(cx - grooveW / 2, pad - 2), size = Size(grooveW, usable + 4),
                cornerRadius = CornerRadius(grooveW / 2),
            )
            // LED ladder: cells between the centre and the value light up in their own colour.
            val cells = 20
            val cellH = usable / cells
            val centre = pad + usable / 2f
            val valueY = pad + usable * (1f - (t + 1f) / 2f)
            for (k in 0 until cells) {
                val top = pad + k * cellH
                val cellMid = top + cellH / 2f
                val cellLevel = 1f - (cellMid - pad) / usable * 2f // +1 at the top, -1 at the bottom
                val litCell = lit && ((valueY <= cellMid && cellMid <= centre) || (centre <= cellMid && cellMid <= valueY))
                drawRoundRect(
                    if (litCell) levelColor(cellLevel) else Color.White.copy(alpha = 0.05f),
                    topLeft = Offset(cx - grooveW / 2 + 1.5f, top + 1f),
                    size = Size(grooveW - 3f, cellH - 2f),
                    cornerRadius = CornerRadius(1.5f),
                )
            }
            // Centre mark.
            drawLine(Skin.Dim.copy(alpha = 0.7f), Offset(cx - grooveW, centre), Offset(cx + grooveW, centre), strokeWidth = 1.5f)
            // Thumb: a bevelled metal block, glowing in its level's colour when on.
            val thumbW = (size.width * 0.74f).coerceAtMost(26.dp.toPx())
            val thumbH = 2 * pad * 0.8f
            if (lit && value != 0f) {
                drawRoundRect(
                    levelColor(t).copy(alpha = 0.35f),
                    topLeft = Offset(cx - thumbW / 2 - 3, valueY - thumbH / 2 - 3), size = Size(thumbW + 6, thumbH + 6),
                    cornerRadius = CornerRadius(5.dp.toPx()),
                )
            }
            drawRoundRect(
                Brush.verticalGradient(listOf(Skin.ThumbTop, Skin.ThumbBottom), startY = valueY - thumbH / 2, endY = valueY + thumbH / 2),
                topLeft = Offset(cx - thumbW / 2, valueY - thumbH / 2), size = Size(thumbW, thumbH),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
            drawLine(Color.Black.copy(alpha = 0.45f), Offset(cx - thumbW / 2 + 3, valueY), Offset(cx + thumbW / 2 - 3, valueY), strokeWidth = 1.5f)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label, fontSize = if (label.length > 4) 7.sp else 9.sp, lineHeight = 11.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, color = Skin.Text, maxLines = 1,
        )
    }
}

/**
 * Tap to jump, drag to slide, double-tap for 0, in one gesture loop. A tap
 * lands on release, so a finger that starts on a slider to scroll the page
 * doesn't move it; a drag takes over once it passes the touch slop.
 */
private suspend fun PointerInputScope.sliderGestures(onChange: (Float) -> Unit) {
    var lastTapUp: Long? = null // none yet
    var lastTapY = 0f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val pad = THUMB_HALF.toPx()
        val drag = awaitVerticalTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
        if (drag != null) {
            lastTapUp = null
            onChange(valueAt(drag.position.y, size.height.toFloat(), pad))
            verticalDrag(drag.id) { c ->
                c.consume()
                onChange(valueAt(c.position.y, size.height.toFloat(), pad))
            }
            return@awaitEachGesture
        }
        val up = currentEvent.changes.firstOrNull { it.id == down.id }
        if (up == null || up.pressed || up.isConsumed) return@awaitEachGesture // taken by the page scroll
        up.consume()
        val again = lastTapUp?.let { up.uptimeMillis - it < viewConfiguration.doubleTapTimeoutMillis } == true
        if (again && abs(down.position.y - lastTapY) < DOUBLE_TAP_REACH.toPx()) {
            lastTapUp = null
            onChange(0f)
        } else {
            lastTapUp = up.uptimeMillis
            lastTapY = down.position.y
            onChange(valueAt(down.position.y, size.height.toFloat(), pad))
        }
    }
}

/** Slider value for a touch at [y], to the nearest 0.4 dB (Winamp's own step was about 0.6). */
private fun valueAt(y: Float, height: Float, pad: Float): Float {
    val usable = (height - 2 * pad).coerceAtLeast(1f)
    val frac = 1f - ((y - pad) / usable).coerceIn(0f, 1f)
    val db = frac * 2 * WinampEqDesign.MAX_DB - WinampEqDesign.MAX_DB
    return ((db / 0.4f).roundToInt() * 0.4f).let { if (abs(it) < 0.2f) 0f else it }
        .coerceIn(-WinampEqDesign.MAX_DB, WinampEqDesign.MAX_DB)
}

private fun fmtWinamp(v: Float): String = when {
    abs(v) < 0.05f -> "0"
    v > 0 -> "+" + ((v * 10).roundToInt() / 10f)
    else -> ((v * 10).roundToInt() / 10f).toString()
}

private val THUMB_HALF = 7.dp
private val CHAR_WIDTH = 7.25.dp

/** How close a second tap has to land to count as a double-tap rather than a quick move. */
private val DOUBLE_TAP_REACH = 32.dp
private const val CURVE_POINTS = 120
