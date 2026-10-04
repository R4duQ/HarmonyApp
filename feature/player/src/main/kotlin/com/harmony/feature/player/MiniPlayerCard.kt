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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * The card's colours. It stands out against the page: a dark card on a
 * light page and a light one on a dark page.
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

private val DarkCard = MiniColors(
    card = Color(0xFF1A1F27),
    ink = Color.White,
    muted = Color.White.copy(alpha = 0.42f),
    button = Color(0xFF243238),
    buttonInk = Color(0xFFD9E3E8),
    chip = Color(0xFF282E37),
    chipInk = Color.White.copy(alpha = 0.92f),
    deviceDot = Color.White,
    deviceDotInk = Color(0xFF1A1F27),
    track = Color.White.copy(alpha = 0.10f),
)

private val LightCard = MiniColors(
    card = Color(0xFFF3F2FA),
    ink = Color(0xFF15151C),
    muted = Color(0xFF15151C).copy(alpha = 0.55f),
    button = Color(0xFFC9F4F8),
    buttonInk = Color(0xFF6A3DE8),
    chip = Color(0xFFC9F4F8),
    chipInk = Color(0xFF34344A),
    deviceDot = Color(0xFF6A3DE8),
    deviceDotInk = Color.White,
    track = Color(0xFF6A3DE8).copy(alpha = 0.10f),
)

private object MiniAccent {
    val Purple = Color(0xFF7C4DFF)
    val PurpleDeep = Color(0xFF5A2BD8)
    val Orange = Color(0xFFF2A65A)

    /** The progress wave, from where the song starts to where it is. */
    val Wave = listOf(
        Color(0xFF8F5CFF), Color(0xFFE48AB3), Color(0xFFF3A45E), Color(0xFFE9C85A),
        Color(0xFF63C99B), Color(0xFF39B9D3), Color(0xFF6C63FF),
    )

    /** The rim around the card and the record. */
    val Rim = listOf(
        Color(0xFF8F5CFF), Color(0xFF39B9D3), Color(0xFFF3A45E), Color(0xFFE48AB3), Color(0xFF8F5CFF),
    )
}

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
    val colors = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) LightCard else DarkCard
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
                    .shadow(18.dp, shape, ambientColor = MiniAccent.Purple, spotColor = MiniAccent.Purple)
                    .clip(shape)
                    .background(colors.card)
                    .border(1.5.dp, Brush.linearGradient(MiniAccent.Rim.map { it.copy(alpha = 0.7f) }), shape)
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
                    WaveProgress(
                        positionMs = ui.positionMs,
                        durationMs = ui.durationMs,
                        playing = ui.isPlaying,
                        track = colors.track,
                        onSeek = actions.onSeek,
                        modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(22.dp).testTag(MiniPlayerTags.PROGRESS),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoundButton(
                            Icons.Rounded.Shuffle, if (ui.shuffleOn) "Shuffle on" else "Shuffle off", colors, actions.onShuffle,
                            MiniPlayerTags.SHUFFLE, active = ui.shuffleOn,
                        )
                        RoundButton(Icons.Rounded.SkipPrevious, "Previous", colors, actions.onPrevious, MiniPlayerTags.PREVIOUS)
                        PlayButton(ui.isPlaying, actions.onPlayPause)
                        RoundButton(Icons.Rounded.SkipNext, "Next", colors, actions.onNext, MiniPlayerTags.NEXT)
                        RoundButton(
                            if (ui.repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            when (ui.repeatMode) {
                                RepeatMode.OFF -> "Repeat off"
                                RepeatMode.ALL -> "Repeat all"
                                RepeatMode.ONE -> "Repeat one"
                            },
                            colors, actions.onRepeat, MiniPlayerTags.REPEAT, active = ui.repeatMode != RepeatMode.OFF,
                        )
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
private const val FLING_UP_PX_PER_S = 1_400f

/** How far the card rises at full swipe before it gives; and the small hop a tap gives it. */
private val LIFT_FULL = 150.dp
private val TAP_LIFT = 36.dp

// ---------------------------------------------------------------------------
// The record
// ---------------------------------------------------------------------------

/**
 * The cover as a record: round, with a rainbow rim, turning about once every
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
            .shadow(14.dp, CircleShape, ambientColor = MiniAccent.Purple, spotColor = MiniAccent.Purple)
            .clip(CircleShape)
            .background(Color(0xFF0D0D10))
            .border(2.dp, Brush.sweepGradient(MiniAccent.Rim), CircleShape)
            .testTag(MiniPlayerTags.DISC)
            .clickable(role = Role.Button, onClickLabel = "Open the player", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = artworkUri,
            transitionSpec = { (fadeIn(tween(400)) + scaleIn(initialScale = 0.92f)).togetherWith(fadeOut(tween(250))) },
            label = "disc-art",
        ) { uri ->
            Artwork(uri, null, Modifier.fillMaxSize().padding(3.dp).clip(CircleShape), cornerRadius = 999.dp)
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

// ---------------------------------------------------------------------------
// Device, controls, up next
// ---------------------------------------------------------------------------

@Composable
private fun DeviceChip(label: String, icon: ImageVector, colors: MiniColors) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.chip)
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
                text,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                color = colors.chipInk,
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
) {
    val tint = if (active) MiniAccent.Purple.let { if (colors === DarkCard) Color(0xFFB79CFF) else it } else colors.buttonInk
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(colors.button)
            .then(if (active) Modifier.border(1.5.dp, tint.copy(alpha = 0.55f), CircleShape) else Modifier)
            .testTag(tag)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** The big purple button, with a soft halo breathing around it while the music plays. */
@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
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
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(50.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val t = halo.value
            if (t > 0f) {
                drawCircle(
                    MiniAccent.Purple.copy(alpha = 0.35f * (1f - t)),
                    radius = size.minDimension / 2 * (0.86f + 0.14f * t),
                )
            }
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(MiniAccent.Purple, MiniAccent.PurpleDeep)))
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
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Up Next", fontSize = 13.sp, lineHeight = 16.sp, color = colors.muted, modifier = Modifier.padding(end = 10.dp))
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
                // The whole rainbow over what has been played, ending in blue at the thumb.
                Brush.horizontalGradient(MiniAccent.Wave, startX = left, endX = maxOf(x, left + wavelength)),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        drawCircle(MiniAccent.Orange.copy(alpha = 0.25f), radius = thumbR * 1.5f, center = Offset(x, mid))
        drawCircle(
            Brush.linearGradient(listOf(MiniAccent.Purple, MiniAccent.Orange), start = Offset(x - thumbR, mid - thumbR), end = Offset(x + thumbR, mid + thumbR)),
            radius = thumbR,
            center = Offset(x, mid),
        )
    }
}
