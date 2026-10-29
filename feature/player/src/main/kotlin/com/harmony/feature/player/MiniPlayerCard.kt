package com.harmony.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.harmony.core.ui.component.neonGlow
import com.harmony.core.ui.theme.CyberGlassColors
import com.harmony.core.ui.theme.CyberGlassScheme
import com.harmony.domain.playback.AudioLevels
import com.harmony.core.model.RepeatMode
import com.harmony.core.model.Song
import com.harmony.core.ui.component.Artwork
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Everything the mini player shows. */
data class MiniPlayerUi(
    val song: Song,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val shuffleOn: Boolean,
    val repeatMode: RepeatMode,
    /** Where the music is playing, as the listener knows it ("Pixel Buds Pro"). */
    val deviceLabel: String,
    val deviceIcon: ImageVector,
    /** The next songs, with their place in the queue. */
    val upNext: List<Pair<Int, Song>>,
    /** A live micro-subtitle ("LIVE SPECTRAL EQ • VIBE MATCH 94%"), shown in Cyber-Glass; null for none. */
    val telemetry: String? = null,
)

class MiniPlayerActions(
    val onExpand: () -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onShuffle: () -> Unit = {},
    val onRepeat: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onUpNext: (Int) -> Unit = {},
)

internal object MiniPlayerTags {
    const val CARD = "mini_card"
    const val DISC = "mini_disc"
    const val PROGRESS = "mini_progress"
    const val SHUFFLE = "mini_shuffle"
    const val PREVIOUS = "mini_previous"
    const val PLAY = "mini_play"
    const val NEXT = "mini_next"
    const val REPEAT = "mini_repeat"
    fun upNext(index: Int) = "mini_up_next_$index"
}

/**
 * The card's colours: Cyber-Glass in both themes, smoked obsidian glass in
 * dark and pearl glass in light, taken from the theme's [CyberGlassScheme].
 */
private class MiniColors(
    val card: Color,
    val ink: Color,
    val muted: Color,
    val button: Color,
    val buttonInk: Color,
    val chip: Color,
    val chipInk: Color,
    val deviceDot: Color,
    val deviceDotInk: Color,
    val track: Color,
)

private fun miniColors(c: CyberGlassScheme) = MiniColors(
    card = c.Card.copy(alpha = c.CARD_ALPHA),
    ink = c.TextPrimary,
    muted = c.TextSecondary,
    button = c.Recessed,
    buttonInk = c.TextPlaceholder,
    chip = c.Recessed,
    chipInk = c.TextSilver,
    deviceDot = c.Cyan.copy(alpha = 0.16f),
    deviceDotInk = c.Cyan,
    track = c.Sky.copy(alpha = 0.12f),
)

/**
 * The mini player: the record, half out of the card, turning while the music
 * plays; where it is playing; the song; a wave that runs along as it plays;
 * the five transport controls; and what comes next.
 *
 * Tap the card or the record to open Now Playing, swipe up
 * for the same, swipe sideways to skip. Drag along the wave to seek.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiniPlayerCard(ui: MiniPlayerUi, actions: MiniPlayerActions, modifier: Modifier = Modifier) {
    val glass = CyberGlassColors
    val colors = remember(glass) { miniColors(glass) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val onExpand by rememberUpdatedState(actions.onExpand)
    val onNext by rememberUpdatedState(actions.onNext)
    val onPrevious by rememberUpdatedState(actions.onPrevious)

    // Sideways swipe to skip: the content follows the finger, the record turns with it.
    val dragX = remember { Animatable(0f) }
    var skipForward by remember { mutableStateOf(true) }

    // Swipe up to open Now Playing: the card rises with the finger (px, 0 or less).
    val lift = remember { Animatable(0f) }
    // 0 -> 1 as the card arrives (and overshoots a little): the record spins in, the content settles.
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 210f)) }

    val tapLiftPx = with(LocalDensity.current) { TAP_LIFT.toPx() }
    val liftFullPx = with(LocalDensity.current) { LIFT_FULL.toPx() }

    /** Opens Now Playing with the card already on its way up to [liftPx], whatever started it. */
    fun expand(liftPx: Float) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onExpand()
        scope.launch { lift.animateTo(liftPx, tween(240, easing = FastOutSlowInEasing)) }
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .graphicsLayer {
                val rise = (-lift.value / LIFT_FULL.toPx()).coerceIn(0f, 1.4f)
                translationY = lift.value
                scaleX = 1f + 0.05f * rise
                scaleY = 1f + 0.05f * rise
                transformOrigin = TransformOrigin(0.5f, 1f)
            },
    ) {
        val disc = (maxWidth * 0.27f).coerceIn(84.dp, 120.dp)
        val shape = RoundedCornerShape(28.dp)
        val skipAt = 72.dp

        Box(Modifier.fillMaxWidth().padding(start = disc / 2)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .shadow(
                        18.dp,
                        shape,
                        ambientColor = glass.Violet,
                        spotColor = glass.Cyan,
                    )
                    .clip(shape)
                    .background(colors.card)
                    .border(1.dp, glass.Iridescent, shape)
                    .testTag(MiniPlayerTags.CARD)
                    .semantics { contentDescription = "Now playing: ${ui.song.title} by ${ui.song.artist}. Open the player." }
                    .clickable(onClick = { expand(-tapLiftPx) })
                    .pointerInput(Unit) {
                        // Up follows the finger one to one (with some give past the full lift);
                        // down barely moves, since there is nowhere to go.
                        val full = LIFT_FULL.toPx()
                        val tracker = VelocityTracker()
                        var total = 0f
                        fun shown(t: Float) = when {
                            t > 0f -> t * 0.2f
                            -t <= full -> t
                            else -> -(full + (-t - full) * 0.35f)
                        }
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f; tracker.resetTracking() },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                tracker.addPointerInputChange(change)
                                total += amount
                                scope.launch { lift.snapTo(shown(total)) }
                            },
                            onDragEnd = {
                                val velocity = tracker.calculateVelocity().y
                                if (total < -SWIPE_UP_PX || velocity < -FLING_UP_PX_PER_S) {
                                    expand(-full * 1.3f)
                                } else {
                                    scope.launch { lift.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 380f)) }
                                }
                            },
                            onDragCancel = { scope.launch { lift.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 380f)) } },
                        )
                    }
                    .pointerInput(Unit) {
                        val threshold = skipAt.toPx()
                        var total = 0f
                        var past = false
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f; past = false },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                total += amount
                                scope.launch { dragX.snapTo(total * 0.55f) }
                                val nowPast = abs(total) >= threshold
                                if (nowPast && !past) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                past = nowPast
                            },
                            onDragCancel = { scope.launch { dragX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) } },
                            onDragEnd = {
                                val forward = total <= -threshold
                                val back = total >= threshold
                                if (forward || back) {
                                    skipForward = forward
                                    if (forward) onNext() else onPrevious()
                                }
                                scope.launch { dragX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
                            },
                        )
                    },
            ) {
                Column(
                    Modifier
                        .padding(start = disc / 2 + 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)
                        .graphicsLayer {
                            translationX = dragX.value
                            // Settles in just behind the record as the card arrives.
                            val e = entrance.value
                            alpha = (e * 1.5f - 0.4f).coerceIn(0f, 1f)
                            translationY = (1f - e) * 14.dp.toPx()
                        },
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        DeviceChip(ui.deviceLabel, ui.deviceIcon, colors)
                    }
                    AnimatedContent(
                        targetState = ui.song,
                        transitionSpec = {
                            val dir = if (skipForward) 1 else -1
                            (slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { dir * it / 3 } + fadeIn(tween(320)))
                                .togetherWith(slideOutHorizontally(tween(200)) { -dir * it / 3 } + fadeOut(tween(200)))
                        },
                        contentKey = { it.id },
                        label = "mini-title",
                        modifier = Modifier.padding(top = 8.dp),
                    ) { song ->
                        Text(
                            "${song.title} - ${song.artist}",
                            fontSize = 18.sp,
                            lineHeight = 23.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000),
                        )
                    }
                    val telemetry = ui.telemetry
                    if (telemetry != null) {
                        Text(
                            telemetry,
                            fontSize = 10.5.sp,
                            lineHeight = 13.sp,
                            letterSpacing = 0.9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = glass.LiveInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                    WaveProgress(
                        positionMs = ui.positionMs,
                        durationMs = ui.durationMs,
                        playing = ui.isPlaying,
                        track = colors.track,
                        onSeek = actions.onSeek,
                        modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(22.dp).testTag(MiniPlayerTags.PROGRESS),
                    )
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        // A live mini-waveform at the start of the row, when the card
                        // is wide enough for it beside all five controls.
                        val withSpectrum = maxWidth >= SPECTRUM_ROW_MIN
                        val button = if (withSpectrum) 34.dp else 38.dp
                        val play = if (withSpectrum) 46.dp else 50.dp
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (withSpectrum) MiniSpectrum(ui.isPlaying, Modifier.size(width = 34.dp, height = 24.dp))
                            RoundButton(
                                Icons.Rounded.Shuffle, if (ui.shuffleOn) "Shuffle on" else "Shuffle off", colors, actions.onShuffle,
                                MiniPlayerTags.SHUFFLE, active = ui.shuffleOn, size = button,
                            )
                            RoundButton(Icons.Rounded.SkipPrevious, "Previous", colors, actions.onPrevious, MiniPlayerTags.PREVIOUS, size = button)
                            PlayButton(ui.isPlaying, actions.onPlayPause, size = play)
                            RoundButton(Icons.Rounded.SkipNext, "Next", colors, actions.onNext, MiniPlayerTags.NEXT, size = button)
                            RoundButton(
                                if (ui.repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                when (ui.repeatMode) {
                                    RepeatMode.OFF -> "Repeat off"
                                    RepeatMode.ALL -> "Repeat all"
                                    RepeatMode.ONE -> "Repeat one"
                                },
                                colors, actions.onRepeat, MiniPlayerTags.REPEAT, active = ui.repeatMode != RepeatMode.OFF, size = button,
                            )
                        }
                    }
                    if (ui.upNext.isNotEmpty()) UpNextRow(ui.upNext, colors, actions.onUpNext)
                }
            }
        }
        Disc(
            artworkUri = ui.song.artworkUri,
            songId = ui.song.id,
            playing = ui.isPlaying,
            nudge = dragX.value,
            rise = { (-lift.value / liftFullPx).coerceIn(0f, 1.4f) },
            entrance = { entrance.value },
            onClick = { expand(-tapLiftPx) },
            modifier = Modifier.align(Alignment.CenterStart).size(disc),
        )
    }
}

private const val SWIPE_UP_PX = 90f

/** The narrowest controls row that still fits the mini-waveform beside the five buttons. */
private val SPECTRUM_ROW_MIN = 228.dp
private const val FLING_UP_PX_PER_S = 1_400f

/** How far the card rises at full swipe before it gives; and the small hop a tap gives it. */
private val LIFT_FULL = 150.dp
private val TAP_LIFT = 36.dp

// ---------------------------------------------------------------------------
// The record
// ---------------------------------------------------------------------------

/**
 * The cover as a record: round, with an iridescent rim, turning about once every
 * nine seconds while the music plays. On pause it coasts to a stop and sinks
 * back a little; a skip gives it a quick extra turn; a swipe turns it with
 * the finger.
 */
@Composable
private fun Disc(
    artworkUri: String?,
    songId: Long,
    playing: Boolean,
    nudge: Float,
    /** 0..1.4 as the card is swiped up: the record grows and rises ahead of it. */
    rise: () -> Float,
    /** 0 -> 1 (overshooting) as the card arrives: the record spins and grows into place. */
    entrance: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val glass = CyberGlassColors
    val turn = remember { Animatable(0f) }
    var firstSong by remember { mutableStateOf(true) }
    LaunchedEffect(playing) {
        if (playing) {
            while (true) {
                turn.snapTo(turn.value % 360f)
                turn.animateTo(turn.value + 360f, tween(ROTATION_MS, easing = LinearEasing))
            }
        } else {
            turn.animateTo(turn.value + 24f, tween(700, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(songId) {
        if (firstSong) {
            firstSong = false
        } else {
            turn.animateTo(turn.value + 200f, tween(650, easing = FastOutSlowInEasing))
        }
    }
    val scale by animateFloatAsState(if (playing) 1f else 0.93f, spring(dampingRatio = 0.55f, stiffness = 300f), label = "disc-scale")
    Box(
        modifier
            .graphicsLayer {
                val r = rise()
                val e = entrance()
                val grow = scale * (1f + 0.3f * r) * (0.55f + 0.45f * e)
                scaleX = grow
                scaleY = grow
                translationY = -r * size.height * 0.35f
                rotationZ = turn.value + nudge * 0.4f - (1f - e) * 160f
                alpha = e.coerceIn(0f, 1f)
            }
            .shadow(16.dp, CircleShape, ambientColor = glass.Violet, spotColor = glass.Cyan)
            .clip(CircleShape)
            // The record stays a dark object in either theme.
            .background(Color(0xFF0D0D10))
            // A 2dp neon violet-cyan ring.
            .border(2.dp, glass.IridescentSweep, CircleShape)
            .testTag(MiniPlayerTags.DISC)
            .clickable(role = Role.Button, onClickLabel = "Open the player", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = artworkUri,
            transitionSpec = { (fadeIn(tween(400)) + scaleIn(initialScale = 0.92f)).togetherWith(fadeOut(tween(250))) },
            label = "disc-art",
        ) { uri ->
            Artwork(
                uri, null, Modifier.fillMaxSize().padding(3.dp).clip(CircleShape), cornerRadius = 999.dp,
                // The record stays dark in either theme: without a cover, a dark label, not the light theme's pale one.
                fallback = if (glass.isDark) null else {
                    {
                        Box(Modifier.fillMaxSize().background(DiscLabel), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = DiscLabelInk)
                        }
                    }
                },
            )
        }
        // Grooves catching the light, and the spindle.
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(
                Brush.sweepGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent, Color.White.copy(alpha = 0.06f), Color.Transparent),
                ),
                radius = r * 0.98f,
            )
            drawCircle(Color.Black.copy(alpha = 0.55f), radius = r * 0.09f)
            drawCircle(Color.White.copy(alpha = 0.35f), radius = r * 0.09f, style = Stroke(1.dp.toPx()))
        }
    }
}

private const val ROTATION_MS = 9_000

/** A coverless record's label: the dark theme's own artwork well, so the record looks the same in light. */
private val DiscLabel = Color(0xFF211F26)
private val DiscLabelInk = Color(0xFFCAC4D0)

// ---------------------------------------------------------------------------
// Device, controls, up next
// ---------------------------------------------------------------------------

@Composable
private fun DeviceChip(label: String, icon: ImageVector, colors: MiniColors) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            // A cyan-tinted capsule with cyan ink.
            .background(CyberGlassColors.DevicePill)
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(colors.deviceDot), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.deviceDotInk, modifier = Modifier.size(14.dp))
        }
        AnimatedContent(
            targetState = label,
            transitionSpec = { fadeIn(tween(250)).togetherWith(fadeOut(tween(150))) },
            label = "mini-device",
        ) { text ->
            Text(
                text.uppercase(),
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.6.sp,
                fontWeight = FontWeight.Medium,
                color = colors.deviceDotInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    description: String,
    colors: MiniColors,
    onClick: () -> Unit,
    tag: String,
    active: Boolean = false,
    size: Dp = 38.dp,
) {
    val glass = CyberGlassColors
    val tint = if (active) glass.Cyan else colors.buttonInk
    Box(
        Modifier
            .size(size)
            // A glowing cyan ring on what is switched on, a faint sky rim on the rest.
            .then(if (active) Modifier.neonGlow(glass.Cyan, 5.dp, 0.30f * glass.Glow) else Modifier)
            .clip(CircleShape)
            .background(colors.button)
            .border(1.dp, if (active) tint.copy(alpha = 0.7f) else glass.Sky.copy(alpha = 0.18f), CircleShape)
            .testTag(tag)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(if (size < 38.dp) 18.dp else 20.dp))
    }
}

/** The big violet button, with a soft halo breathing around it while the music plays. */
@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit, size: Dp = 50.dp) {
    val glass = CyberGlassColors
    val halo = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            while (true) {
                halo.snapTo(0f)
                halo.animateTo(1f, tween(1_600, easing = LinearEasing))
            }
        } else {
            halo.animateTo(0f, tween(300))
        }
    }
    val haloColor = glass.Purple
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        Canvas(Modifier.fillMaxSize()) {
            val t = halo.value
            if (t > 0f) {
                drawCircle(
                    haloColor.copy(alpha = 0.35f * (1f - t)),
                    radius = this.size.minDimension / 2 * (0.86f + 0.14f * t),
                )
            }
        }
        Box(
            Modifier
                .size(size - 6.dp)
                .neonGlow(glass.Violet, 6.dp, 0.30f * glass.Glow)
                .clip(CircleShape)
                .background(glass.PlayButton)
                .border(1.dp, glass.Cyan.copy(alpha = 0.45f), CircleShape)
                .testTag(MiniPlayerTags.PLAY)
                .clickable(role = Role.Button, onClickLabel = if (playing) "Pause" else "Play", onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = playing,
                transitionSpec = {
                    (scaleIn(initialScale = 0.5f, animationSpec = tween(180)) + fadeIn(tween(180)))
                        .togetherWith(scaleOut(targetScale = 0.5f, animationSpec = tween(120)) + fadeOut(tween(120)))
                },
                label = "mini-play",
            ) { isPlaying ->
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

@Composable
private fun UpNextRow(upNext: List<Pair<Int, Song>>, colors: MiniColors, onUpNext: (Int) -> Unit) {
    val divider = CyberGlassColors.Divider
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "UP NEXT",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.8.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.muted,
            modifier = Modifier.padding(end = 10.dp),
        )
        AnimatedContent(
            targetState = upNext,
            contentKey = { list -> list.map { it.second.id } },
            transitionSpec = {
                (slideInHorizontally(tween(300)) { it / 4 } + fadeIn(tween(300))).togetherWith(fadeOut(tween(150)))
            },
            label = "mini-up-next",
        ) { list ->
            Row(
                Modifier
                    .fillMaxWidth()
                    // The chips run off under a soft fade at the card's edge, inviting a scroll.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.horizontalGradient(0.8f to Color.Black, 1f to Color.Transparent),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                list.forEach { (index, song) ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.chip)
                            // Recessed, a hairline round the inside edge.
                            .border(1.dp, divider, RoundedCornerShape(50))
                            .testTag(MiniPlayerTags.upNext(index))
                            .semantics { contentDescription = "${song.title} by ${song.artist}" }
                            .clickable(role = Role.Button, onClickLabel = "Play now") { onUpNext(index) }
                            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(song.artworkUri, null, Modifier.size(24.dp).clip(CircleShape), cornerRadius = 999.dp)
                        Text(
                            song.title,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.chipInk,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 7.dp).widthIn(max = 130.dp),
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The wave
// ---------------------------------------------------------------------------

/**
 * Progress as a rainbow wave up to where the song is, and a flat line after
 * it. While the music plays the wave runs along and the position moves on
 * every frame between the player's updates; on pause the wave settles flat.
 * Tap or drag to seek.
 */
@Composable
private fun WaveProgress(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    track: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
) {
    // Violet into cyan into mint, ending in a glowing laser spark.
    val glass = CyberGlassColors
    val seek by rememberUpdatedState(onSeek)
    // Where the song is now: the last reported position, carried on with the clock while playing.
    var shown by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(positionMs, durationMs, playing) {
        if (durationMs <= 0) {
            shown = 0f
            return@LaunchedEffect
        }
        val start = withFrameMillis { it }
        do {
            val now = withFrameMillis { it }
            val elapsed = if (playing) now - start else 0L
            shown = ((positionMs + elapsed).toFloat() / durationMs).coerceIn(0f, 1f)
        } while (playing)
    }
    val phase = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            while (true) {
                phase.snapTo(phase.value % (2 * PI.toFloat()))
                phase.animateTo(phase.value + 2 * PI.toFloat(), tween(1_400, easing = LinearEasing))
            }
        }
    }
    val amplitude by animateFloatAsState(if (playing) 1f else 0f, tween(500), label = "wave-amplitude")
    // For screen readers; the drawing below reads the per-frame position itself, so only it redraws.
    val reported = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Canvas(
        modifier
            // Its own node, not folded into the card's "open the player": screen readers can seek with it.
            .semantics(mergeDescendants = true) {
                progressBarRangeInfo = ProgressBarRangeInfo(reported, 0f..1f)
                setProgress { v -> if (durationMs > 0) seek((v.coerceIn(0f, 1f) * durationMs).toLong()); true }
            }
            .pointerInput(durationMs) {
                detectTapGestures { o ->
                    if (durationMs > 0) seek(((o.x / size.width).coerceIn(0f, 1f) * durationMs).toLong())
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = (o.x / size.width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragging = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragging?.let { if (durationMs > 0) seek((it * durationMs).toLong()) }
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                )
            },
    ) {
        val thumbR = 8.dp.toPx()
        val left = thumbR
        val right = size.width - thumbR
        val mid = size.height / 2
        val fraction = dragging ?: shown
        val x = left + (right - left) * fraction
        val stroke = 4.dp.toPx()
        // What is still to come.
        drawLine(track, Offset(x, mid), Offset(right, mid), strokeWidth = stroke, cap = StrokeCap.Round)
        // What has been played: a wave in the rainbow.
        if (x > left) {
            val wave = Path()
            val wavelength = 16.dp.toPx()
            val amp = 3.dp.toPx() * amplitude
            var px = left
            wave.moveTo(px, mid + amp * sin(phase.value))
            val step = 2f
            while (px < x) {
                px = (px + step).coerceAtMost(x)
                // The wave flattens into the thumb so the two meet cleanly.
                val ease = ((x - px) / (wavelength * 0.75f)).coerceIn(0f, 1f)
                wave.lineTo(px, mid + amp * ease * sin((px - left) / wavelength * 2 * PI.toFloat() - phase.value))
            }
            drawPath(
                wave,
                // Violet into cyan into mint over what has been played.
                Brush.horizontalGradient(
                    glass.ProgressColors,
                    startX = left,
                    endX = maxOf(x, left + wavelength),
                ),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        // The laser spark: a wide cyan bloom, a short streak along the line, a white-hot core.
        drawCircle(
            Brush.radialGradient(
                listOf(glass.Cyan.copy(alpha = 0.55f * glass.Glow), glass.Cyan.copy(alpha = 0.12f * glass.Glow), Color.Transparent),
                center = Offset(x, mid),
                radius = thumbR * 2.2f,
            ),
            radius = thumbR * 2.2f,
            center = Offset(x, mid),
        )
        drawLine(
            Brush.horizontalGradient(
                listOf(Color.Transparent, glass.Mint.copy(alpha = 0.9f), Color.Transparent),
                startX = x - thumbR * 2.4f,
                endX = x + thumbR * 2.4f,
            ),
            Offset(x - thumbR * 2.4f, mid),
            Offset(x + thumbR * 2.4f, mid),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(glass.Cyan, radius = thumbR * 0.62f, center = Offset(x, mid))
        drawCircle(Color.White, radius = thumbR * 0.34f, center = Offset(x, mid))
    }
}

// ---------------------------------------------------------------------------
// The live mini-waveform (Cyber-Glass)
// ---------------------------------------------------------------------------

private const val SPECTRUM_BARS = 7

/**
 * Seven bars dancing to the music, from the EQ engine's own spectrum
 * (AudioLevels), cyan into violet with a soft glow. The engine's analyser
 * is only asked for while the music plays and the app is on screen; on
 * pause the bars settle low and the polling stops.
 */
@Composable
private fun MiniSpectrum(playing: Boolean, modifier: Modifier) {
    val levels = remember { FloatArray(SPECTRUM_BARS) { 0.12f } }
    val spectrum = remember { FloatArray(AudioLevels.SPECTRUM_BARS) }
    // Read only by the drawing: a new frame redraws without recomposing.
    var frame by remember { mutableIntStateOf(0) }
    val glass = CyberGlassColors
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, playing) {
        val choreographer = android.view.Choreographer.getInstance()
        var running = false
        val callback = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                val n = AudioLevels.poll(spectrum, null)
                val t = frameTimeNanos / 1e9f
                for (i in 0 until SPECTRUM_BARS) {
                    val target = if (n > 0) {
                        // Each bar averages its share of the 32 FFT bars.
                        val from = i * n / SPECTRUM_BARS
                        val to = ((i + 1) * n / SPECTRUM_BARS).coerceAtLeast(from + 1)
                        var sum = 0f
                        for (b in from until to) sum += spectrum[b]
                        (sum / (to - from)).coerceIn(0.08f, 1f)
                    } else {
                        // No engine attached (another output, a cold start): a calm idle pulse.
                        0.25f + 0.2f * sin(t * 5f + i * 0.9f)
                    }
                    levels[i] += (target - levels[i]) * 0.35f
                }
                frame++
                choreographer.postFrameCallback(this)
            }
        }
        fun start() {
            if (running || !playing) return
            running = true
            AudioLevels.startWatching()
            choreographer.postFrameCallback(callback)
        }
        fun stop() {
            if (!running) return
            running = false
            choreographer.removeFrameCallback(callback)
            AudioLevels.stopWatching()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        if (!playing) {
            for (i in 0 until SPECTRUM_BARS) levels[i] = 0.12f
            frame++
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stop()
        }
    }
    Canvas(modifier.semantics { contentDescription = "Live spectrum" }) {
        if (frame < 0) return@Canvas // reading the frame redraws the bars each tick
        val slot = size.width / SPECTRUM_BARS
        val barWidth = slot * 0.52f
        val brush = Brush.verticalGradient(listOf(glass.Cyan, glass.Violet, glass.Purple))
        for (i in 0 until SPECTRUM_BARS) {
            val h = (size.height * levels[i]).coerceAtLeast(barWidth)
            val x = i * slot + (slot - barWidth) / 2f
            val top = (size.height - h) / 2f
            drawRoundRect(
                brush,
                topLeft = Offset(x - barWidth * 0.5f, top - barWidth * 0.4f),
                size = Size(barWidth * 2f, h + barWidth * 0.8f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth),
                alpha = 0.16f * glass.Glow,
            )
            drawRoundRect(
                brush,
                topLeft = Offset(x, top),
                size = Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
            )
        }
    }
}
