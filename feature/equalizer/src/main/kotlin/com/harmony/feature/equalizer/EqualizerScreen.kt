package com.harmony.feature.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.datastore.SettingsRepository
import com.harmony.core.model.EqSettings
import com.harmony.core.ui.component.EditorialCard
import com.harmony.core.ui.component.EditorialChoiceChips
import com.harmony.core.ui.component.EditorialCircleButton
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialPill
import com.harmony.core.ui.component.EditorialSectionGap
import com.harmony.core.ui.component.EditorialSwitch
import com.harmony.core.ui.component.EditorialTab
import com.harmony.core.ui.component.EditorialTabs
import com.harmony.core.ui.component.EditorialTextAction
import com.harmony.core.ui.component.bluePalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * All writes go to the DataStore only; SettingsApplier pushes the change into
 * the live audio chain. The screen therefore reflects reality even if the
 * change was made elsewhere, and EQ state survives process death for free.
 */
@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val eq: StateFlow<EqSettings> = settings.settings.map { it.eq }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EqSettings())

    val userPresets: StateFlow<Map<String, List<Float>>> =
        settings.settings.map { it.userEqPresets }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun saveCurrentAsPreset(name: String) {
        viewModelScope.launch { settings.saveUserEqPreset(name, eq.value.bandGainsDb) }
    }

    fun deleteUserPreset(name: String) {
        viewModelScope.launch { settings.deleteUserEqPreset(name) }
    }

    fun applyGains(gains: List<Float>) = update { it.copy(bandGainsDb = gains, enabled = true) }

    fun setEnabled(on: Boolean) = update { it.copy(enabled = on) }

    fun setBand(index: Int, gainDb: Float) = update { current ->
        current.copy(
            bandGainsDb = current.bandGainsDb.toMutableList()
                .also { it[index] = gainDb.coerceIn(-EqSettings.MAX_GAIN_DB, EqSettings.MAX_GAIN_DB) },
        )
    }

    /**
     * Simple-tab macro: set a contiguous GROUP of bands to one gain. The
     * tri-dial's Bass/Mid/Treble map to the 10 real bands as 0-2 (31-125Hz),
     * 3-6 (250Hz-2kHz), 7-9 (4-16kHz) — so the Simple and Advanced tabs are
     * two views of the same underlying state, never two competing states.
     */
    fun setBandGroup(range: IntRange, gainDb: Float) = update { current ->
        val clamped = gainDb.coerceIn(-EqSettings.MAX_GAIN_DB, EqSettings.MAX_GAIN_DB)
        current.copy(
            enabled = true,
            bandGainsDb = current.bandGainsDb.mapIndexed { i, g ->
                if (i in range) clamped else g
            },
        )
    }

    fun reset() = update { it.copy(bandGainsDb = List(EqSettings.BAND_COUNT) { 0f }) }

    /**
     * Simple-tab preset applied as ONE atomic write. The first version
     * called setBandGroup three times back-to-back; each launched its own
     * coroutine that read the CURRENT settings and wrote a modified copy —
     * three concurrent read-modify-writes racing on the same state, so
     * whichever landed last clobbered the other two's changes (visible as
     * the "glitch" when pressing a preset right after moving a dial).
     */
    fun applySimplePreset(bass: Float, mid: Float, treble: Float) = update { current ->
        current.copy(
            enabled = true,
            bandGainsDb = current.bandGainsDb.mapIndexed { i, _ ->
                when (i) {
                    in BASS_BANDS -> bass
                    in MID_BANDS -> mid
                    else -> treble
                }.coerceIn(-EqSettings.MAX_GAIN_DB, EqSettings.MAX_GAIN_DB)
            },
        )
    }

    private fun update(transform: (EqSettings) -> EqSettings) {
        viewModelScope.launch { settings.setEq(transform(eq.value)) }
    }

    companion object {
        val BASS_BANDS = 0..2
        val MID_BANDS = 3..6
        val TREBLE_BANDS = 7..9

        /** Simple-tab presets as (bass, mid, treble) macro gains in dB. */
        val SIMPLE_PRESETS: List<Pair<String, Triple<Float, Float, Float>>> = listOf(
            "Balanced" to Triple(0f, 0f, 0f),
            "More bass" to Triple(5f, 0f, 1f),
            "More treble" to Triple(0f, 0f, 5f),
            "Voice" to Triple(-2f, 4f, 2f),
        )
    }
}

private fun List<Float>.groupAvg(range: IntRange): Float =
    range.map { this[it] }.average().toFloat()

// =====================================================================
//  Screen
// =====================================================================

/**
 * The equalizer in the editorial treatment, on the blue field — an
 * instrument panel next to the library's amber and the playlists' green.
 *
 * The two custom canvas controls (the tri-dial and the vertical band
 * sliders) were already hand-drawn, so restyling them was a matter of
 * feeding them palette colors instead of Material scheme roles; their
 * geometry and gesture handling are untouched. Everything Material —
 * TabRow, segmented preset buttons, the profile dropdown trigger, Reset —
 * became the editorial equivalents so the screen reads as one piece.
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
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val palette = bluePalette()

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.field),
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
                    if (eq.enabled) "On — applied to everything playing" else "Off — audio passes through untouched",
                    fontSize = 12.sp,
                    color = palette.muted,
                )
            }
            EditorialSwitch(eq.enabled, viewModel::setEnabled, palette)
        }

        EditorialTabs(
            tabs = listOf(EditorialTab("Simple"), EditorialTab("Advanced")),
            selected = selectedTab,
            onSelect = { selectedTab = it },
            palette = palette,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
        )

        when (selectedTab) {
            0 -> SimpleTab(viewModel, palette, onCustom = { selectedTab = 1 })
            1 -> AdvancedTab(viewModel, palette)
        }
    }
}

// =====================================================================
//  SIMPLE tab: tri-dial + presets
// =====================================================================

@Composable
private fun SimpleTab(
    viewModel: EqualizerViewModel,
    palette: EditorialPalette,
    onCustom: () -> Unit,
) {
    val eq by viewModel.eq.collectAsStateWithLifecycle()
    val bass = eq.bandGainsDb.groupAvg(EqualizerViewModel.BASS_BANDS)
    val mid = eq.bandGainsDb.groupAvg(EqualizerViewModel.MID_BANDS)
    val treble = eq.bandGainsDb.groupAvg(EqualizerViewModel.TREBLE_BANDS)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EditorialCard(palette = palette, modifier = Modifier.fillMaxWidth()) {
            TriDial(
                bass = bass,
                mid = mid,
                treble = treble,
                enabled = eq.enabled,
                palette = palette,
                onChange = { which, value ->
                    val range = when (which) {
                        0 -> EqualizerViewModel.BASS_BANDS
                        1 -> EqualizerViewModel.MID_BANDS
                        else -> EqualizerViewModel.TREBLE_BANDS
                    }
                    viewModel.setBandGroup(range, value)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .aspectRatio(1f),
            )
        }

        // The four macro presets. A chip row rather than four outlined
        // buttons: these are alternatives to each other, and chips say that
        // where a grid of buttons doesn't. The selected chip fills when the
        // current curve actually matches that preset, so the row doubles as
        // a readout of where you are.
        val activePreset = EqualizerViewModel.SIMPLE_PRESETS.indexOfFirst { (_, t) ->
            val (b, m, tr) = t
            nearly(bass, b) && nearly(mid, m) && nearly(treble, tr)
        }
        EditorialChoiceChips(
            options = EqualizerViewModel.SIMPLE_PRESETS.map { it.first },
            selectedIndex = activePreset,
            onSelect = { index ->
                val (b, m, t) = EqualizerViewModel.SIMPLE_PRESETS[index].second
                viewModel.applySimplePreset(b, m, t)
            },
            palette = palette,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        )

        EditorialPill(
            text = "Custom",
            icon = Icons.Rounded.Tune,
            onClick = onCustom,
            palette = palette,
            modifier = Modifier.padding(top = 18.dp),
        )
        EditorialSectionGap(28)
    }
}

/** Within a snapped half-decibel — the resolution the dial itself works at. */
private fun nearly(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.26f

/**
 * The circular three-handle control: Mid at the top, Bass lower-left,
 * Treble lower-right. Each handle slides along its own radial axis; distance
 * from center maps linearly to -12..+12 dB. Dragging grabs whichever handle
 * is nearest to the touch, then projects finger movement onto that handle's
 * axis — so a rough diagonal swipe still feels precise.
 */
@Composable
private fun TriDial(
    bass: Float,
    mid: Float,
    treble: Float,
    enabled: Boolean,
    palette: EditorialPalette,
    onChange: (which: Int, value: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Screen coords, y down: top = -90deg, lower-left = 150deg, lower-right = 30deg.
    val anglesDeg = listOf(150f, -90f, 30f) // bass, mid, treble
    val values = listOf(bass, mid, treble)
    val max = EqSettings.MAX_GAIN_DB
    val plateColor = palette.ink.copy(alpha = 0.07f)
    val hubColor = palette.ink.copy(alpha = 0.12f)
    val dotColor = palette.muted.copy(alpha = 0.5f)
    val handleColor = if (enabled) palette.accent else palette.muted
    val axisColor = palette.line.copy(alpha = 0.35f)

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    var active = -1
                    detectDragGestures(
                        onDragStart = { pos ->
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val rMin = size.width * 0.14f
                            val rMax = size.width * 0.42f
                            var best = -1
                            var bestDist = Float.MAX_VALUE
                            anglesDeg.forEachIndexed { i, deg ->
                                val rad = Math.toRadians(deg.toDouble())
                                val frac = (values[i] + max) / (2 * max)
                                val r = rMin + (rMax - rMin) * frac
                                val hx = c.x + r * cos(rad).toFloat()
                                val hy = c.y + r * sin(rad).toFloat()
                                val d = hypot(pos.x - hx, pos.y - hy)
                                if (d < bestDist) { bestDist = d; best = i }
                            }
                            active = if (bestDist < size.width * 0.2f) best else -1
                        },
                        onDrag = { change, _ ->
                            if (active < 0) return@detectDragGestures
                            change.consume()
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val rMin = size.width * 0.14f
                            val rMax = size.width * 0.42f
                            val rad = Math.toRadians(anglesDeg[active].toDouble())
                            val dir = Offset(cos(rad).toFloat(), sin(rad).toFloat())
                            val v = change.position - c
                            val proj = (v.x * dir.x + v.y * dir.y).coerceIn(rMin, rMax)
                            val frac = (proj - rMin) / (rMax - rMin)
                            val gain = (frac * 2 * max - max)
                            onChange(active, (gain * 2).roundToInt() / 2f) // snap 0.5 dB
                        },
                        onDragEnd = { active = -1 },
                        onDragCancel = { active = -1 },
                    )
                },
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val rMin = size.width * 0.14f
            val rMax = size.width * 0.42f

            drawCircle(plateColor, radius = size.width * 0.46f, center = c)
            drawCircle(
                color = axisColor,
                radius = size.width * 0.46f,
                center = c,
                style = Stroke(width = 2f),
            )
            drawCircle(hubColor, radius = size.width * 0.20f, center = c)

            anglesDeg.forEachIndexed { i, deg ->
                val rad = Math.toRadians(deg.toDouble())
                val dir = Offset(cos(rad).toFloat(), sin(rad).toFloat())
                // The axis each handle travels along, so the control explains
                // its own degrees of freedom before you touch it.
                drawLine(
                    color = axisColor,
                    start = c + dir * rMin,
                    end = c + dir * rMax,
                    strokeWidth = 2f,
                )
                listOf(0.0f, 0.5f, 1.0f).forEach { t ->
                    val r = rMin + (rMax - rMin) * t
                    drawCircle(dotColor, radius = 4f, center = c + dir * r)
                }
                val frac = (values[i] + max) / (2 * max)
                val r = rMin + (rMax - rMin) * frac
                val center = c + dir * r
                drawCircle(handleColor, radius = size.width * 0.045f, center = center)
                drawCircle(
                    color = palette.line,
                    radius = size.width * 0.045f,
                    center = center,
                    style = Stroke(width = 2f),
                )
            }
        }
        DialLabel("Mid", mid, palette, Modifier.align(Alignment.TopCenter))
        DialLabel("Bass", bass, palette, Modifier.align(Alignment.BottomStart))
        DialLabel("Treble", treble, palette, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun DialLabel(
    name: String,
    value: Float,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            name.uppercase(),
            fontSize = 10.sp,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.muted,
        )
        Text(
            fmtDb(value),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = palette.ink,
        )
    }
}

private fun fmtDb(v: Float): String {
    val r = (v * 2).roundToInt() / 2f
    val s = if (r == r.toInt().toFloat()) r.toInt().toString() else "%.1f".format(r)
    return if (r > 0) "+$s" else s
}

// =====================================================================
//  ADVANCED tab: profiles + response curve + vertical band sliders
// =====================================================================

@Composable
private fun AdvancedTab(viewModel: EqualizerViewModel, palette: EditorialPalette) {
    val eq by viewModel.eq.collectAsStateWithLifecycle()
    val userPresets by viewModel.userPresets.collectAsStateWithLifecycle()
    var showSaveDialog by remember { mutableStateOf(false) }
    var profileMenuOpen by remember { mutableStateOf(false) }

    val allProfiles: List<Pair<String, List<Float>>> =
        EqSettings.PRESETS.map { it.key to it.value } + userPresets.map { it.key to it.value }
    val currentProfileName = allProfiles.firstOrNull { it.second == eq.bandGainsDb }?.first ?: "Custom"

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        // ---- Profile picker ----
        EditorialCard(palette = palette, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "PROFILE",
                        fontSize = 10.sp,
                        letterSpacing = 1.6.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.muted,
                    )
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { profileMenuOpen = true },
                        ) {
                            Text(
                                currentProfileName,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.3).sp,
                                color = palette.ink,
                            )
                            Icon(
                                Icons.Rounded.ArrowDropDown,
                                contentDescription = "Choose a profile",
                                tint = palette.ink,
                            )
                        }
                        DropdownMenu(
                            expanded = profileMenuOpen,
                            onDismissRequest = { profileMenuOpen = false },
                        ) {
                            allProfiles.forEach { (name, gains) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        viewModel.applyGains(gains)
                                        profileMenuOpen = false
                                    },
                                    trailingIcon = if (name in userPresets) ({
                                        TextButton(onClick = {
                                            viewModel.deleteUserPreset(name)
                                            profileMenuOpen = false
                                        }) { Text("Delete") }
                                    }) else null,
                                )
                            }
                        }
                    }
                }
                EditorialCircleButton(
                    onClick = { showSaveDialog = true },
                    contentDescription = "Save current settings as a profile",
                    palette = palette,
                    size = 40.dp,
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // ---- Response curve ----
        EditorialCard(
            palette = palette,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                ResponseCurve(
                    gains = eq.bandGainsDb,
                    enabled = eq.enabled,
                    palette = palette,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BAND_LABELS.forEach {
                        Text(it, fontSize = 9.sp, color = palette.muted)
                    }
                }
            }
        }

        // ---- Band sliders ----
        EditorialCard(
            palette = palette,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            Column(Modifier.padding(vertical = 14.dp, horizontal = 8.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    eq.bandGainsDb.forEachIndexed { index, gain ->
                        VerticalBandSlider(
                            value = gain,
                            enabled = eq.enabled,
                            palette = palette,
                            label = BAND_LABELS[index],
                            onChange = { viewModel.setBand(index, it) },
                            modifier = Modifier.width(26.dp),
                        )
                    }
                }
            }
        }

        Row(Modifier.padding(top = 16.dp)) {
            EditorialTextAction(
                text = "Reset all bands to 0 dB",
                onClick = viewModel::reset,
                palette = palette,
            )
        }
        EditorialSectionGap(28)
    }

    if (showSaveDialog) {
        var name by remember { mutableStateOf("") }
        // Styled from the section palette rather than Material defaults: a
        // stock filled Button lands as a wallpaper-coloured slab on a screen
        // that is otherwise flat blue.
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = {
                Text(
                    "Save profile",
                    fontSize = 20.sp,
                    lineHeight = 25.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.ink,
                )
            },
            text = {
                Column {
                    Text(
                        "Stores the current ten-band curve so you can come back to it.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("Profile name") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.saveCurrentAsPreset(name); showSaveDialog = false },
                    enabled = name.isNotBlank(),
                ) {
                    Text(
                        "Save",
                        fontWeight = FontWeight.Bold,
                        color = if (name.isNotBlank()) palette.ink else palette.muted,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel", color = palette.muted)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = palette.field,
            titleContentColor = palette.ink,
            textContentColor = palette.ink,
        )
    }
}

private val BAND_LABELS =
    listOf("31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K")

/** Smoothed frequency-response preview drawn from the 10 band gains. */
@Composable
private fun ResponseCurve(
    gains: List<Float>,
    enabled: Boolean,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    val lineColor = if (enabled) palette.accent else palette.muted
    val fillTop = lineColor.copy(alpha = 0.3f)
    val dotColor = palette.ink

    Canvas(modifier) {
        val n = gains.size
        val maxDb = EqSettings.MAX_GAIN_DB
        val midY = size.height / 2f
        val amp = size.height * 0.42f
        val pts = List(n) { i ->
            Offset(
                x = size.width * i / (n - 1f),
                y = midY - (gains[i] / maxDb) * amp,
            )
        }
        drawLine(
            palette.line.copy(alpha = 0.35f),
            Offset(0f, midY),
            Offset(size.width, midY),
            strokeWidth = 2f,
        )

        // Smooth path through points via midpoint quadratics
        val path = Path().apply {
            moveTo(pts.first().x, pts.first().y)
            for (i in 0 until n - 1) {
                val p0 = pts[i]
                val p1 = pts[i + 1]
                val midX = (p0.x + p1.x) / 2f
                quadraticTo(p0.x, p0.y, midX, (p0.y + p1.y) / 2f)
            }
            lineTo(pts.last().x, pts.last().y)
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, midY)
            lineTo(0f, midY)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(fillTop, Color.Transparent), endY = size.height))
        drawPath(path, lineColor, style = Stroke(width = 5f))
        pts.forEach { drawCircle(dotColor, radius = 4f, center = it) }
    }
}

/**
 * Pill-track vertical slider: rounded outline, guide dots, single round
 * thumb, -12..+12 dB with 0 centered. Direct vertical drag anywhere on the
 * track; no rotated-horizontal-Slider tricks (the old approach — which also
 * never laid out reliably across screen sizes).
 */
@Composable
private fun VerticalBandSlider(
    value: Float,
    enabled: Boolean,
    palette: EditorialPalette,
    label: String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val max = EqSettings.MAX_GAIN_DB
    val outline = palette.line
    val dotColor = palette.muted.copy(alpha = 0.45f)
    val thumbColor = if (enabled) palette.accent else palette.muted
    val density = LocalDensity.current

    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            fmtDb(value),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (value == 0f) palette.muted else palette.ink,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 4.dp)
                .semantics {
                    contentDescription = "$label hertz band, ${fmtDb(value)} decibels"
                    progressBarRangeInfo = ProgressBarRangeInfo(value, -max..max)
                    setProgress { target ->
                        onChange(target.coerceIn(-max, max))
                        true
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectVerticalDragGestures { change, _ ->
                        change.consume()
                        val pad = with(density) { 14.dp.toPx() }
                        val usable = size.height - 2 * pad
                        val frac = 1f - ((change.position.y - pad) / usable).coerceIn(0f, 1f)
                        val gain = frac * 2 * max - max
                        onChange((gain * 2).roundToInt() / 2f)
                    }
                },
        ) {
            val pad = with(density) { 14.dp.toPx() }
            val usable = size.height - 2 * pad
            val cx = size.width / 2f

            drawRoundRect(
                color = outline,
                style = Stroke(width = 3f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2f),
            )
            listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { t ->
                drawCircle(dotColor, radius = 3f, center = Offset(cx, pad + usable * t))
            }
            val frac = (value + max) / (2 * max)
            val thumbY = pad + usable * (1f - frac)
            drawCircle(thumbColor, radius = size.width * 0.34f, center = Offset(cx, thumbY))
            drawCircle(
                color = outline,
                radius = size.width * 0.34f,
                center = Offset(cx, thumbY),
                style = Stroke(width = 2.5f),
            )
        }
    }
}
