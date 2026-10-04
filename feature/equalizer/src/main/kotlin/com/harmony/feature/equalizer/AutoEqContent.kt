package com.harmony.feature.equalizer

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.model.AutoEqDesign
import com.harmony.core.model.AutoEqReadout
import com.harmony.core.model.AutoEqSettings
import com.harmony.core.model.ClarityReadout
import com.harmony.core.model.ClaritySettings
import com.harmony.core.model.EqSettings
import com.harmony.core.model.ListeningKind
import com.harmony.core.model.RoomCorrection
import com.harmony.core.ui.component.EditorialCard
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialPill
import com.harmony.core.ui.component.EditorialSwitch
import com.harmony.core.ui.component.EditorialTextAction
import com.harmony.core.ui.component.LocalFloatingChromeHeight
import com.harmony.domain.playback.AudioLevels
import com.harmony.domain.playback.AutoEqLive
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where a calibration is, for the screen. */
sealed interface CalibrationStep {
    /** Hearing the room in silence, to know its own noise. */
    data class Silence(val progress: Float) : CalibrationStep

    /** Playing the test sound and listening to it. [level] is the microphone, 0..1. */
    data class Playing(val progress: Float, val level: Float) : CalibrationStep
}

sealed interface CalibrationUi {
    data object Idle : CalibrationUi
    data class Running(val step: CalibrationStep) : CalibrationUi
    data class Done(val gainsDb: List<Float>) : CalibrationUi
    data class Failed(val message: String) : CalibrationUi
}

/** Everything the Auto tab shows. */
data class AutoEqUiState(
    val eqOn: Boolean = false,
    val auto: AutoEqSettings = AutoEqSettings(),
    val readout: AutoEqReadout = AutoEqReadout(),
    /** The device playing, as the listener knows it. */
    val outputLabel: String = "Phone speaker",
    val kind: ListeningKind = ListeningKind.SPEAKER,
    /** The correction measured for that device, if any. */
    val correction: RoomCorrection? = null,
    val calibration: CalibrationUi = CalibrationUi.Idle,
    val playing: Boolean = false,
    /** "Title · Artist" of the song playing, if any. */
    val nowPlaying: String? = null,
    /** How Clarity is set; whether it runs is [AutoEqSettings.clarity]. */
    val clarity: ClaritySettings = ClaritySettings(),
)

class AutoEqActions(
    val onTone: (Boolean) -> Unit = {},
    val onRoom: (Boolean) -> Unit = {},
    val onNoise: (Boolean) -> Unit = {},
    val onCalibrate: () -> Unit = {},
    val onCancelCalibration: () -> Unit = {},
    val onForgetCorrection: () -> Unit = {},
    val onClarity: (Boolean) -> Unit = {},
    val onClaritySettings: (ClaritySettings) -> Unit = {},
)

internal object AutoTags {
    const val TONE = "auto_tone"
    const val ROOM = "auto_room"
    const val NOISE = "auto_noise"
    const val MEASURE = "auto_measure"
    const val CURVE = "auto_curve"
    const val HERO = "auto_hero"
    const val GAUGE = "auto_noise_gauge"
}

/** The parts' colours, the same in the hero, the rings and on their cards. */
private object AutoColors {
    val Song = Color(0xFF5B8CFF)
    val SongLight = Color(0xFF9DB8FF)
    val Room = Color(0xFF1FD1B5)
    val RoomLight = Color(0xFF8BF0DF)
    val Noise = Color(0xFFFF9F2E)
    val NoiseLight = Color(0xFFFFCB86)
    val HeroTop = Color(0xFF101634)
    val HeroBottom = Color(0xFF1C1240)
    val Live = Color(0xFF3CDB5A)
}

/**
 * The Auto tab: a glowing hero that shows what the automatic equalizer is
 * doing right now (the total curve over the live spectrum, and a ring for
 * each part), then a card per part with its own switch, curve and readout.
 * Clarity, the hearing model, comes first with its live view and controls.
 */
@Composable
fun AutoEqContent(
    state: AutoEqUiState,
    actions: AutoEqActions,
    palette: EditorialPalette,
    spectrum: StateFlow<FloatArray> = AudioLevels.spectrum,
    clarityLive: StateFlow<ClarityReadout> = AutoEqLive.clarityLive,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalFloatingChromeHeight.current)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Hero(state, spectrum)
        PartCard(
            icon = Icons.Rounded.AutoAwesome,
            colors = ClarityColors.Clarity to ClarityColors.ClarityLight,
            title = "Clarity",
            status = when {
                !state.auto.clarity -> null
                !state.eqOn -> "PAUSED"
                state.playing -> "LISTENING"
                else -> "READY"
            },
            subtitle = "A model of human hearing listens to the music about 90 times a second and moves 24 bands " +
                "as it plays: it brings out what other sounds cover up and holds back what pushes forward.",
            checked = state.auto.clarity,
            onChecked = actions.onClarity,
            palette = palette,
            tag = ClarityTags.SWITCH,
        ) {
            if (state.auto.clarity) ClarityDetail(state, clarityLive, actions.onClaritySettings, palette)
        }
        PartCard(
            icon = Icons.Rounded.GraphicEq,
            colors = AutoColors.Song to AutoColors.SongLight,
            title = "Song by song",
            status = when {
                !state.auto.tone -> null
                !state.eqOn -> "PAUSED"
                state.readout.songHeardSeconds < AutoEqDesign.TONE_SETTLE_SECONDS -> "LEARNING"
                else -> "ACTIVE"
            },
            subtitle = "Evens out each song's tone towards a well-mastered record: a little body for thin " +
                "recordings, a little less edge for harsh ones. A few dB at most.",
            checked = state.auto.tone,
            onChecked = actions.onTone,
            palette = palette,
            tag = AutoTags.TONE,
        ) {
            if (state.auto.tone) SongDetail(state, palette)
        }
        PartCard(
            icon = Icons.Rounded.Speaker,
            colors = AutoColors.Room to AutoColors.RoomLight,
            title = "Speaker & room",
            status = when {
                state.calibration is CalibrationUi.Running -> "MEASURING"
                state.kind == ListeningKind.HEADPHONES -> if (state.auto.room) "NOT FOR HEADPHONES" else null
                !state.auto.room -> null
                state.correction == null -> "NOT MEASURED"
                else -> "ACTIVE"
            },
            subtitle = "Plays a short test sound and listens with the microphone, then takes out what your " +
                "speaker and room add or swallow. Measured once for each speaker.",
            checked = state.auto.room,
            onChecked = actions.onRoom,
            palette = palette,
            tag = AutoTags.ROOM,
        ) {
            RoomDetail(state, actions, palette)
        }
        PartCard(
            icon = Icons.Rounded.Hearing,
            colors = AutoColors.Noise to AutoColors.NoiseLight,
            title = "Noise around you",
            status = when {
                !state.auto.noise -> null
                state.kind != ListeningKind.HEADPHONES -> "HEADPHONES ONLY"
                state.readout.ambientDb != null -> "LISTENING"
                else -> "READY"
            },
            subtitle = "On headphones, the microphone listens to your surroundings while music plays and " +
                "lifts what the noise covers up: bass on a bus, voices in a crowd.",
            checked = state.auto.noise,
            onChecked = actions.onNoise,
            palette = palette,
            tag = AutoTags.NOISE,
        ) {
            if (state.auto.noise || state.kind != ListeningKind.HEADPHONES) NoiseDetail(state, palette)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.ink.copy(alpha = 0.05f))
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = palette.muted, modifier = Modifier.size(16.dp))
            Text(
                "Auto adds its corrections on top of Winamp. Clarity listens to the music itself, inside the app. " +
                    "The microphone is used only while measuring a speaker and, with noise adaptation on, while " +
                    "music plays on headphones. Nothing is recorded or sent anywhere.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = palette.muted,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Hero
// ---------------------------------------------------------------------------

@Composable
private fun Hero(state: AutoEqUiState, spectrum: StateFlow<FloatArray>) {
    val r = state.readout
    val running = state.eqOn && state.auto.any
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(AutoColors.HeroTop, AutoColors.HeroBottom)))
            .drawBehind {
                // Aurora: the three parts' colours as soft light behind everything.
                fun glow(color: Color, x: Float, y: Float, r: Float) = drawCircle(
                    Brush.radialGradient(listOf(color.copy(alpha = if (running) 0.42f else 0.16f), Color.Transparent), Offset(x, y), r),
                    radius = r, center = Offset(x, y),
                )
                glow(AutoColors.Song, size.width * 0.1f, size.height * 0.05f, size.width * 0.75f)
                glow(ClarityColors.Clarity, size.width * 0.75f, size.height * 0.0f, size.width * 0.55f)
                glow(AutoColors.Room, size.width * 0.95f, size.height * 0.35f, size.width * 0.6f)
                glow(AutoColors.Noise, size.width * 0.35f, size.height * 1.0f, size.width * 0.7f)
            }
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .padding(18.dp)
            .testTag(AutoTags.HERO),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "AUTO EQ",
                fontSize = 11.sp,
                lineHeight = 14.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (running) AutoColors.Live else Color.White.copy(alpha = 0.35f)))
                Text(
                    if (running) "LIVE" else "OFF",
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                !state.auto.any -> "Auto is off"
                !state.eqOn -> "Equalizer is off"
                else -> "Tuning for ${state.outputLabel}"
            },
            fontSize = 26.sp,
            lineHeight = 31.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            when {
                !state.auto.any -> "Switch on a part below and Auto adjusts the sound by itself."
                !state.eqOn -> "Switch the equalizer on at the top to let Auto work."
                state.nowPlaying != null -> "Now: ${state.nowPlaying}"
                else -> "Play something and the curve comes alive."
            },
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(14.dp))
        HeroCurve(r, running, spectrum, Modifier.fillMaxWidth().height(150.dp).testTag(AutoTags.CURVE))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Ring("Clarity", r.clarityDb, ClarityColors.Clarity, state.auto.clarity && running)
            Ring("Song", r.toneDb, AutoColors.Song, state.auto.tone && running)
            Ring("Room", r.roomDb, AutoColors.Room, state.auto.room && running)
            Ring("Noise", r.noiseDb, AutoColors.Noise, state.auto.noise && running)
            Ring("Total", r.totalDb, Color.White, running)
        }
    }
}

/**
 * The total correction as a glowing white curve, each part as a thin line
 * in its colour, over the live spectrum of what's playing.
 */
@Composable
private fun HeroCurve(r: AutoEqReadout, running: Boolean, spectrum: StateFlow<FloatArray>, modifier: Modifier) {
    val bands by spectrum.collectAsStateWithLifecycle()
    val total = remember(r) { r.totalDb }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val mid = size.height / 2f
            val span = size.height * 0.44f
            val range = 8f
            fun y(db: Float) = mid - (db / range).coerceIn(-1.1f, 1.1f) * span
            fun x(i: Float) = size.width * (i / (EqSettings.BAND_COUNT - 1))
            // The live spectrum, faint, one bar per band.
            val barW = size.width / EqSettings.BAND_COUNT * 0.55f
            bands.forEachIndexed { i, v ->
                val h = size.height * 0.9f * v.coerceIn(0f, 1f)
                val cx = x(i.toFloat()).coerceIn(barW / 2, size.width - barW / 2)
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.03f)), startY = size.height - h, endY = size.height),
                    topLeft = Offset(cx - barW / 2, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 3),
                )
            }
            // Grid.
            drawLine(Color.White.copy(alpha = 0.22f), Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.5f)
            for (db in listOf(-4f, 4f)) {
                drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, y(db)), Offset(size.width, y(db)), strokeWidth = 1f)
            }
            fun curve(db: List<Float>): Path {
                val path = Path()
                val steps = 90
                for (k in 0..steps) {
                    val t = k.toFloat() / steps * (EqSettings.BAND_COUNT - 1)
                    val v = interpolate(db, t)
                    if (k == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v))
                }
                return path
            }
            val alpha = if (running) 1f else 0.4f
            val totalPath = curve(total)
            val fill = Path().apply {
                addPath(totalPath)
                lineTo(size.width, mid)
                lineTo(0f, mid)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f * alpha), Color.White.copy(alpha = 0.02f))))
            val thin = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
            drawPath(curve(r.clarityDb), ClarityColors.ClarityLight.copy(alpha = alpha), style = thin)
            drawPath(curve(r.toneDb), AutoColors.SongLight.copy(alpha = alpha), style = thin)
            drawPath(curve(r.roomDb), AutoColors.RoomLight.copy(alpha = alpha), style = thin)
            drawPath(curve(r.noiseDb), AutoColors.NoiseLight.copy(alpha = alpha), style = thin)
            drawPath(totalPath, Color.White.copy(alpha = 0.22f * alpha), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
            drawPath(totalPath, Color.White.copy(alpha = alpha), style = Stroke(2.8.dp.toPx(), cap = StrokeCap.Round))
        }
        // Frequencies under the bands they name.
        BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp).padding(top = 3.dp)) {
            listOf(0 to "31", 2 to "125", 4 to "500", 6 to "2K", 8 to "8K").forEach { (band, text) ->
                val xPos = maxWidth * (band / (EqSettings.BAND_COUNT - 1f))
                Text(
                    text, fontSize = 9.sp, lineHeight = 11.sp, color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.offset(x = if (band == 0) xPos else xPos - 8.dp),
                )
            }
            Text("16K", fontSize = 9.sp, lineHeight = 11.sp, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

/** A ring that fills with the size of a part's largest correction (6 dB fills it). */
@Composable
private fun Ring(label: String, db: List<Float>, color: Color, active: Boolean) {
    val biggest = db.maxByOrNull { abs(it) } ?: 0f
    val fill = (abs(biggest) / 6f).coerceIn(0f, 1f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 5.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(Color.White.copy(alpha = 0.12f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                if (active && fill > 0f) {
                    drawArc(
                        color, -90f, 360f * fill.coerceAtLeast(0.04f), false, Offset(inset, inset), arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Text(
                if (!active || abs(biggest) < 0.05f) "0" else fmtDb1(biggest).removeSuffix(" dB"),
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (active) Color.White else Color.White.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
            )
        }
        Text(
            label.uppercase(),
            fontSize = 9.sp,
            lineHeight = 12.sp,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (active) color.let { if (it == Color.White) it else lerp(it, Color.White, 0.35f) } else Color.White.copy(alpha = 0.45f),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Smooth (Catmull-Rom) value of the band gains [db] at fractional band [t]. */
internal fun interpolate(db: List<Float>, t: Float): Float {
    if (db.isEmpty()) return 0f
    val i = t.toInt().coerceIn(0, db.size - 1)
    val f = t - i
    fun at(k: Int) = db[k.coerceIn(0, db.size - 1)]
    val p0 = at(i - 1); val p1 = at(i); val p2 = at(i + 1); val p3 = at(i + 2)
    return 0.5f * ((2 * p1) + (-p0 + p2) * f + (2 * p0 - 5 * p1 + 4 * p2 - p3) * f * f + (-p0 + 3 * p1 - 3 * p2 + p3) * f * f * f)
}

// ---------------------------------------------------------------------------
// Part cards
// ---------------------------------------------------------------------------

@Composable
private fun PartCard(
    icon: ImageVector,
    colors: Pair<Color, Color>,
    title: String,
    status: String?,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    palette: EditorialPalette,
    tag: String,
    detail: @Composable () -> Unit,
) {
    val (color, light) = colors
    EditorialCard(palette = palette, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(
            Modifier
                .drawBehind {
                    // A wash of the part's colour from the top left when it's on.
                    if (checked) {
                        drawCircle(
                            Brush.radialGradient(listOf(color.copy(alpha = 0.16f), Color.Transparent), Offset.Zero, size.width * 0.8f),
                            radius = size.width * 0.8f, center = Offset.Zero,
                        )
                    }
                }
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(listOf(color, light))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(title, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, color = palette.ink)
                    if (status != null) {
                        Text(
                            status,
                            fontSize = 9.sp,
                            lineHeight = 12.sp,
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.Bold,
                            color = color,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clip(RoundedCornerShape(50))
                                .background(color.copy(alpha = 0.14f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                EditorialSwitch(checked, onChecked, palette, Modifier.padding(start = 8.dp).testTag(tag))
            }
            Text(
                subtitle,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 10.dp),
            )
            detail()
        }
    }
}

/** A part's own curve, small, in its colour. */
@Composable
private fun Sparkline(db: List<Float>, color: Color, palette: EditorialPalette, modifier: Modifier) {
    Canvas(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.22f), RoundedCornerShape(12.dp)),
    ) {
        val mid = size.height / 2f
        val span = size.height * 0.4f
        drawLine(palette.line, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
        val path = Path()
        val steps = 60
        for (k in 0..steps) {
            val t = k.toFloat() / steps * (EqSettings.BAND_COUNT - 1)
            val x = size.width * k / steps
            val y = mid - (interpolate(db, t) / 6f).coerceIn(-1.1f, 1.1f) * span
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, mid)
            lineTo(0f, mid)
            close()
        }
        drawPath(fill, color.copy(alpha = 0.18f))
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun DetailLine(text: String, palette: EditorialPalette, color: Color = palette.ink, top: Int = 8) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier.padding(top = top.dp),
    )
}

@Composable
private fun SongDetail(state: AutoEqUiState, palette: EditorialPalette) {
    val r = state.readout
    Spacer(Modifier.height(12.dp))
    Sparkline(r.toneDb, AutoColors.Song, palette, Modifier.fillMaxWidth().height(54.dp))
    when {
        !state.eqOn -> DetailLine("Switch the equalizer on to use it.", palette)
        !state.playing && r.songHeardSeconds == 0f -> DetailLine("Starts with the next song you play.", palette)
        else -> {
            val learned = (r.songHeardSeconds / AutoEqDesign.TONE_SETTLE_SECONDS).coerceIn(0f, 1f)
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (learned < 1f) "Learning ${(learned * 100).roundToInt()}%" else "Learned",
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AutoColors.Song,
                    modifier = Modifier.width(92.dp),
                )
                Bar(learned, AutoColors.Song, palette, Modifier.weight(1f))
            }
            if (state.nowPlaying != null) DetailLine(state.nowPlaying, palette, palette.muted)
            DetailLine("This song: ${describeCurve(r.toneDb)}.", palette, top = 4)
        }
    }
}

@Composable
private fun Bar(fraction: Float, color: Color, palette: EditorialPalette, modifier: Modifier) {
    Box(modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(palette.line.copy(alpha = 0.6f))) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(listOf(color, lerp(color, Color.White, 0.35f)))),
        )
    }
}

@Composable
private fun RoomDetail(state: AutoEqUiState, actions: AutoEqActions, palette: EditorialPalette) {
    Column(Modifier.padding(top = 12.dp)) {
        DeviceChip(state.outputLabel, Icons.Rounded.Speaker, AutoColors.Room, palette)
        if (state.kind == ListeningKind.HEADPHONES) {
            DetailLine(
                "Room correction is for speakers: with headphones on, the room doesn't reach your ears.",
                palette, palette.muted,
            )
            return@Column
        }
        when (val c = state.calibration) {
            is CalibrationUi.Running -> Measuring(c.step, actions, palette)
            else -> {
                val correction = state.correction
                val shown = (c as? CalibrationUi.Done)?.gainsDb ?: correction?.gainsDb
                if (shown != null) {
                    Spacer(Modifier.height(10.dp))
                    Sparkline(shown, AutoColors.Room, palette, Modifier.fillMaxWidth().height(54.dp))
                }
                DetailLine(
                    when {
                        c is CalibrationUi.Failed -> c.message
                        c is CalibrationUi.Done -> "Measured just now: ${describeCurve(c.gainsDb)}."
                        correction != null -> listOfNotNull(measuredOn(correction.measuredAtMs), describeCurve(correction.gainsDb)).joinToString(" · ") + "."
                        else -> "Not measured yet. Put the phone where you listen, set a normal volume and keep " +
                            "the room quiet for 10 seconds."
                    },
                    palette,
                    if (c is CalibrationUi.Failed) Color(0xFFE5484D) else palette.ink,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    EditorialPill(
                        if (correction == null) "Measure" else "Measure again",
                        Icons.Rounded.Mic,
                        actions.onCalibrate,
                        palette,
                        Modifier.testTag(AutoTags.MEASURE),
                    )
                    if (correction != null) EditorialTextAction("Forget", actions.onForgetCorrection, palette)
                }
            }
        }
    }
}

@Composable
private fun DeviceChip(label: String, icon: ImageVector, color: Color, palette: EditorialPalette) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(palette.ink.copy(alpha = 0.06f))
            .border(1.dp, palette.line, RoundedCornerShape(50))
            .padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(
            label,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

private fun measuredOn(ms: Long): String? {
    if (ms <= 0) return null
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    return "Measured ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.year}"
}

@Composable
private fun Measuring(step: CalibrationStep, actions: AutoEqActions, palette: EditorialPalette) {
    val (label, progress, level) = when (step) {
        is CalibrationStep.Silence -> Triple("Listening to the room in silence…", step.progress, 0f)
        is CalibrationStep.Playing -> Triple("Playing the test sound…", step.progress, step.level)
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.ink, modifier = Modifier.weight(1f))
        Text("${(progress * 100).roundToInt()}%", fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, color = AutoColors.Room)
    }
    Spacer(Modifier.height(8.dp))
    Bar(progress, AutoColors.Room, palette, Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Mic, contentDescription = null, tint = palette.muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        // The microphone's level, so the listener can see it hears something.
        Bar(level, AutoColors.Song, palette, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        EditorialTextAction("Cancel", actions.onCancelCalibration, palette)
    }
}

@Composable
private fun NoiseDetail(state: AutoEqUiState, palette: EditorialPalette) {
    Column(Modifier.padding(top = 12.dp)) {
        DeviceChip(state.outputLabel, Icons.Rounded.Hearing, AutoColors.Noise, palette)
        when (state.kind) {
            ListeningKind.SPEAKER -> DetailLine(
                "Works on headphones. On ${state.outputLabel} the microphone would hear the music itself.",
                palette, palette.muted,
            )
            ListeningKind.UNKNOWN -> DetailLine(
                "Works on headphones. If ${state.outputLabel} is a pair of headphones, say so in Now Playing: " +
                    "tap the device under the song.",
                palette, palette.muted,
            )
            ListeningKind.HEADPHONES -> {
                val ambient = state.readout.ambientDb
                when {
                    !state.eqOn -> DetailLine("Switch the equalizer on to use it.", palette)
                    ambient == null -> DetailLine("Listens while music plays on ${state.outputLabel}.", palette)
                    else -> {
                        Spacer(Modifier.height(12.dp))
                        AmbientGauge(ambient, palette, Modifier.fillMaxWidth().testTag(AutoTags.GAUGE))
                        Spacer(Modifier.height(10.dp))
                        Sparkline(state.readout.noiseDb, AutoColors.Noise, palette, Modifier.fillMaxWidth().height(54.dp))
                        DetailLine("Lifting ${describeCurve(state.readout.noiseDb, quiet = "nothing: it's quiet")}.", palette)
                    }
                }
            }
        }
    }
}

/**
 * How loud it is around the listener, on a 30–90 dB scale from green to red,
 * with the everyday places those levels belong to.
 */
@Composable
private fun AmbientGauge(db: Float, palette: EditorialPalette, modifier: Modifier) {
    val lo = 30f
    val hi = 90f
    val t = ((db - lo) / (hi - lo)).coerceIn(0f, 1f)
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${db.roundToInt()}", fontSize = 28.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = palette.ink)
            Text(" dB around you", fontSize = 12.sp, lineHeight = 18.sp, color = palette.muted, modifier = Modifier.weight(1f))
            Text(
                ambientPlace(db),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
                color = AutoColors.Noise,
            )
        }
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(18.dp)) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF3CDB5A), Color(0xFFF2D21B), Color(0xFFFF9F2E), Color(0xFFFF3B30)))),
            )
            Box(
                Modifier
                    .offset(x = (maxWidth - 18.dp) * t)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(3.dp, AutoColors.Noise, CircleShape),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            listOf("Quiet", "Office", "Street", "Bus").forEach {
                Text(it, fontSize = 10.sp, lineHeight = 12.sp, color = palette.muted, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun ambientPlace(db: Float): String = when {
    db < 45 -> "QUIET ROOM"
    db < 60 -> "OFFICE"
    db < 72 -> "BUSY STREET"
    else -> "BUS · TRAIN"
}

private val BAND_NAMES = listOf(31, 62, 125, 250, 500).map { "$it Hz" } +
    listOf(1, 2, 4, 8, 16).map { "$it kHz" }

/** "+2.1 dB at 62 Hz, −1.8 dB at 4 kHz": the largest lift and cut in [db]. */
internal fun describeCurve(db: List<Float>, quiet: String = "no change needed"): String {
    if (db.isEmpty()) return quiet
    val up = db.indices.maxBy { db[it] }
    val down = db.indices.minBy { db[it] }
    val parts = buildList {
        if (db[up] >= 0.5f) add("${fmtDb1(db[up])} at ${BAND_NAMES[up]}")
        if (db[down] <= -0.5f) add("${fmtDb1(db[down])} at ${BAND_NAMES[down]}")
    }
    return if (parts.isEmpty()) quiet else parts.joinToString(", ")
}

private fun fmtDb1(v: Float): String {
    val r = (v * 10).roundToInt() / 10f
    return when {
        r > 0 -> "+$r dB"
        r < 0 -> "−${-r} dB"
        else -> "0 dB"
    }
}
