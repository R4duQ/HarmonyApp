package com.harmony.feature.equalizer

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.model.AutoEqDesign
import com.harmony.core.model.AutoEqReadout
import com.harmony.core.model.AutoEqSettings
import com.harmony.core.model.EqSettings
import com.harmony.core.model.ListeningKind
import com.harmony.core.model.RoomCorrection
import com.harmony.core.ui.component.EditorialCard
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialPill
import com.harmony.core.ui.component.EditorialSectionLabel
import com.harmony.core.ui.component.EditorialSwitch
import com.harmony.core.ui.component.EditorialTextAction
import com.harmony.core.ui.component.LocalFloatingChromeHeight
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
)

class AutoEqActions(
    val onTone: (Boolean) -> Unit = {},
    val onRoom: (Boolean) -> Unit = {},
    val onNoise: (Boolean) -> Unit = {},
    val onCalibrate: () -> Unit = {},
    val onCancelCalibration: () -> Unit = {},
    val onForgetCorrection: () -> Unit = {},
)

internal object AutoTags {
    const val TONE = "auto_tone"
    const val ROOM = "auto_room"
    const val NOISE = "auto_noise"
    const val MEASURE = "auto_measure"
    const val CURVE = "auto_curve"
}

/** The three parts' colours, the same in the curve and on their cards. */
private object AutoColors {
    val Room = Color(0xFF22B8A5)
    val Noise = Color(0xFFF59E2E)
}

/**
 * The Auto tab: what the automatic equalizer is doing right now, and its
 * three parts, each with its own switch: song by song, speaker and room,
 * and the noise around you.
 */
@Composable
fun AutoEqContent(state: AutoEqUiState, actions: AutoEqActions, palette: EditorialPalette) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalFloatingChromeHeight.current)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LiveCurveCard(state, palette)
        PartCard(
            icon = Icons.Rounded.GraphicEq,
            tint = palette.accent,
            title = "Song by song",
            subtitle = "Evens out each song's tone towards a well-mastered record: a little body for thin " +
                "recordings, a little less edge for harsh ones. A few dB at most.",
            checked = state.auto.tone,
            onChecked = actions.onTone,
            palette = palette,
            tag = AutoTags.TONE,
        ) {
            if (state.auto.tone) Detail(toneDetail(state), palette)
        }
        PartCard(
            icon = Icons.Rounded.Speaker,
            tint = AutoColors.Room,
            title = "Speaker & room",
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
            tint = AutoColors.Noise,
            title = "Noise around you",
            subtitle = "On headphones, the microphone listens to your surroundings while music plays and " +
                "lifts what the noise covers up: bass on a bus, voices in a crowd.",
            checked = state.auto.noise,
            onChecked = actions.onNoise,
            palette = palette,
            tag = AutoTags.NOISE,
        ) {
            if (state.auto.noise || state.kind != ListeningKind.HEADPHONES) Detail(noiseDetail(state), palette)
        }
        Text(
            "Auto adds small corrections on top of your own settings in Simple, Advanced or Winamp. The " +
                "microphone is used only while measuring a speaker and, with noise adaptation on, while music " +
                "plays on headphones. Nothing is recorded or sent anywhere.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = palette.muted,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun LiveCurveCard(state: AutoEqUiState, palette: EditorialPalette) {
    val r = state.readout
    val running = state.eqOn && state.auto.any
    EditorialCard(palette = palette, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EditorialSectionLabel("Right now", palette, Modifier.weight(1f))
                Text(
                    when {
                        !state.auto.any -> "Off"
                        !state.eqOn -> "Equalizer off"
                        else -> "Adjusting · ${state.outputLabel}"
                    },
                    fontSize = 12.sp,
                    color = palette.muted,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(10.dp))
            AutoCurve(r, running, palette, Modifier.fillMaxWidth().height(132.dp).testTag(AutoTags.CURVE))
            Spacer(Modifier.height(10.dp))
            Row {
                Legend("Total", palette.ink, r.totalDb, palette, Modifier.weight(1f))
                Legend("Song", palette.accent, r.toneDb, palette, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Legend("Room", AutoColors.Room, r.roomDb, palette, Modifier.weight(1f))
                Legend("Noise", AutoColors.Noise, r.noiseDb, palette, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Legend(label: String, color: Color, db: List<Float>, palette: EditorialPalette, modifier: Modifier) {
    val biggest = db.maxByOrNull { abs(it) } ?: 0f
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            "$label ${if (abs(biggest) < 0.05f) "0" else fmtDb1(biggest)}",
            fontSize = 11.sp,
            color = palette.muted,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * The total correction as a filled curve, with each part as a thin line
 * under it, from 31 Hz to 16 kHz.
 */
@Composable
private fun AutoCurve(r: AutoEqReadout, running: Boolean, palette: EditorialPalette, modifier: Modifier) {
    val total = remember(r) { r.totalDb }
    Canvas(modifier) {
        val mid = size.height / 2f
        val span = size.height * 0.44f
        val range = 8f
        fun y(db: Float) = mid - (db / range).coerceIn(-1.1f, 1.1f) * span
        fun x(i: Float) = size.width * (i / (EqSettings.BAND_COUNT - 1))
        // Grid: 0 dB, ±4 dB, and the bands.
        drawLine(palette.line, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.5f)
        for (db in listOf(-4f, 4f)) {
            drawLine(palette.line.copy(alpha = 0.5f), Offset(0f, y(db)), Offset(size.width, y(db)), strokeWidth = 1f)
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
        val alpha = if (running) 1f else 0.35f
        val totalPath = curve(total)
        val fill = Path().apply {
            addPath(totalPath)
            lineTo(size.width, mid)
            lineTo(0f, mid)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(palette.accent.copy(alpha = 0.28f * alpha), palette.accent.copy(alpha = 0.04f))))
        val thin = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
        drawPath(curve(r.toneDb), palette.accent.copy(alpha = 0.9f * alpha), style = thin)
        drawPath(curve(r.roomDb), AutoColors.Room.copy(alpha = 0.9f * alpha), style = thin)
        drawPath(curve(r.noiseDb), AutoColors.Noise.copy(alpha = 0.9f * alpha), style = thin)
        // The sum of the three, which is what plays.
        drawPath(totalPath, palette.ink.copy(alpha = alpha), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
    // Frequencies under the bands they name.
    BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp).padding(top = 3.dp)) {
        val labels = listOf(0 to "31", 2 to "125", 4 to "500", 6 to "2K", 8 to "8K")
        labels.forEach { (band, text) ->
            val x = maxWidth * (band / (EqSettings.BAND_COUNT - 1f))
            Text(
                text,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                color = palette.muted,
                modifier = Modifier.offset(x = if (band == 0) x else x - 8.dp),
            )
        }
        Text("16K", fontSize = 9.sp, lineHeight = 11.sp, color = palette.muted, modifier = Modifier.align(Alignment.TopEnd))
    }
}

/** Smooth (Catmull-Rom) value of the band gains [db] at fractional band [t]. */
private fun interpolate(db: List<Float>, t: Float): Float {
    if (db.isEmpty()) return 0f
    val i = t.toInt().coerceIn(0, db.size - 1)
    val f = t - i
    fun at(k: Int) = db[k.coerceIn(0, db.size - 1)]
    val p0 = at(i - 1); val p1 = at(i); val p2 = at(i + 1); val p3 = at(i + 2)
    return 0.5f * ((2 * p1) + (-p0 + p2) * f + (2 * p0 - 5 * p1 + 4 * p2 - p3) * f * f + (-p0 + 3 * p1 - 3 * p2 + p3) * f * f * f)
}

@Composable
private fun PartCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    palette: EditorialPalette,
    tag: String,
    detail: @Composable () -> Unit,
) {
    EditorialCard(palette = palette, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.ink)
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                EditorialSwitch(checked, onChecked, palette, Modifier.padding(start = 8.dp).testTag(tag))
            }
            detail()
        }
    }
}

@Composable
private fun Detail(text: String, palette: EditorialPalette, color: Color = palette.ink) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier.padding(start = 48.dp, top = 10.dp),
    )
}

@Composable
private fun RoomDetail(state: AutoEqUiState, actions: AutoEqActions, palette: EditorialPalette) {
    Column(Modifier.padding(start = 48.dp, top = 10.dp)) {
        if (state.kind == ListeningKind.HEADPHONES) {
            Text(
                "Playing on ${state.outputLabel}. Room correction is for speakers: with headphones on, " +
                    "the room doesn't reach your ears.",
                fontSize = 12.sp, lineHeight = 16.sp, color = palette.muted,
            )
            return@Column
        }
        when (val c = state.calibration) {
            is CalibrationUi.Running -> Measuring(c.step, actions, palette)
            else -> {
                val correction = state.correction
                Text(
                    when {
                        c is CalibrationUi.Failed -> c.message
                        c is CalibrationUi.Done -> "Measured ${state.outputLabel}: ${describeCurve(c.gainsDb)}."
                        correction != null -> "${state.outputLabel}: ${describeCurve(correction.gainsDb)}."
                        else -> "${state.outputLabel} hasn't been measured yet. Put the phone where you listen, " +
                            "set a normal volume and keep the room quiet for 10 seconds."
                    },
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (c is CalibrationUi.Failed) Color(0xFFE5484D) else palette.ink,
                )
                Spacer(Modifier.height(10.dp))
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
private fun Measuring(step: CalibrationStep, actions: AutoEqActions, palette: EditorialPalette) {
    val (label, progress, level) = when (step) {
        is CalibrationStep.Silence -> Triple("Listening to the room in silence…", step.progress, 0f)
        is CalibrationStep.Playing -> Triple("Playing the test sound…", step.progress, step.level)
    }
    Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = palette.ink)
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(palette.line.copy(alpha = 0.6f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(AutoColors.Room))
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Mic", fontSize = 11.sp, color = palette.muted)
        Spacer(Modifier.width(8.dp))
        // The microphone's level, so the listener can see it hears something.
        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(palette.line.copy(alpha = 0.4f))) {
            Box(Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).height(4.dp).background(palette.accent))
        }
        Spacer(Modifier.width(12.dp))
        EditorialTextAction("Cancel", actions.onCancelCalibration, palette)
    }
}

private fun toneDetail(state: AutoEqUiState): String {
    val r = state.readout
    return when {
        !state.eqOn -> "Switch the equalizer on to use it."
        !state.playing && r.songHeardSeconds == 0f -> "Starts with the next song you play."
        r.songHeardSeconds < AutoEqDesign.TONE_SETTLE_SECONDS && r.toneDb.all { abs(it) < 0.05f } ->
            "Listening to this song…"
        else -> "This song: ${describeCurve(r.toneDb)}."
    }
}

private fun noiseDetail(state: AutoEqUiState): String = when (state.kind) {
    ListeningKind.SPEAKER -> "Works on headphones. On ${state.outputLabel} the microphone would hear the music itself."
    ListeningKind.UNKNOWN -> "Works on headphones. If ${state.outputLabel} is a pair of headphones, say so in Now " +
        "Playing: tap the device under the song."
    ListeningKind.HEADPHONES -> {
        val ambient = state.readout.ambientDb
        when {
            !state.eqOn -> "Switch the equalizer on to use it."
            ambient == null -> "Listens while music plays on ${state.outputLabel}."
            else -> "Around you: ${ambient.roundToInt()} dB · ${describeCurve(state.readout.noiseDb, quiet = "quiet, nothing to lift")}."
        }
    }
}

// Non-breaking spaces: "125 Hz" never splits across lines.
private val BAND_NAMES = listOf(31, 62, 125, 250, 500).map { "$it\u00A0Hz" } +
    listOf(1, 2, 4, 8, 16).map { "$it\u00A0kHz" }

/** "+2.1 dB at 62 Hz, −1.8 dB at 4 kHz": the largest lift and cut in [db]. */
internal fun describeCurve(db: List<Float>, quiet: String = "no change needed"): String {
    if (db.isEmpty()) return quiet
    val up = db.indices.maxBy { db[it] }
    val down = db.indices.minBy { db[it] }
    val parts = buildList {
        if (db[up] >= 0.5f) add("${fmtDb1(db[up])}\u00A0at ${BAND_NAMES[up]}")
        if (db[down] <= -0.5f) add("${fmtDb1(db[down])}\u00A0at ${BAND_NAMES[down]}")
    }
    return if (parts.isEmpty()) quiet else parts.joinToString(", ")
}

private fun fmtDb1(v: Float): String {
    val r = (v * 10).roundToInt() / 10f
    return when {
        r > 0 -> "+$r\u00A0dB"
        r < 0 -> "−${-r}\u00A0dB"
        else -> "0\u00A0dB"
    }
}
