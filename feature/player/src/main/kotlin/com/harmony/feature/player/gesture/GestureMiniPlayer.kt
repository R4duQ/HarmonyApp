package com.harmony.feature.player.gesture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.harmony.feature.player.R
import kotlinx.coroutines.launch

/** A song as the mini player shows it. [key] says which song (and which queue entry) it is. */
@Immutable
data class MiniTrack(
    val key: Any,
    val title: String,
    val artist: String,
    /** What [GestureMiniPlayer]'s artwork slot is given to draw the cover. */
    val artwork: Any? = null,
)

/** Everything the player can ask for; each is called at most once per committed transition. */
class GestureMiniActions(
    val onSkip: () -> Unit = {},
    val onReplay: () -> Unit = {},
    /** Back to the song before (the left button for a song's first 5 s, frame 07). */
    val onPrevious: () -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onExpand: () -> Unit = {},
)

/** The words the player speaks to a screen reader (strings from resources at the call site). */
@Immutable
data class GestureMiniLabels(
    val nowPlaying: String,
    val state: String,
    val next: String,
    val replay: String,
    val play: String,
    val pause: String,
    val open: String,
    val previous: String = "Previous song",
)

internal object MiniTags {
    const val PLAYER = "gesture_mini"
    const val CAPSULE = "gesture_mini_capsule"
    const val SKIP = "gesture_mini_skip"
    const val REPLAY = "gesture_mini_replay"
    const val PAUSE = "gesture_mini_pause"
}

private val Inter = FontFamily(
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_medium, FontWeight.Medium),
)

/** Figma: Inter Semi Bold 14, line height 130%. */
private val TitleStyle = TextStyle(
    fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 1.3.em,
    color = MiniColors.Title, textAlign = TextAlign.Center,
)

/** Figma: Inter Medium 10, line height 130%. */
private val ArtistStyle = TextStyle(
    fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 1.3.em,
    color = MiniColors.Artist, textAlign = TextAlign.Center,
)

/**
 * Harmony's mini player, as designed in Figma (file rrilnVhuT79ENxaSaCUBnU,
 * frames 01–07): a capsule with the song between a previous/replay and a
 * skip button. The left button goes back to [previous] for a song's first
 * 5 s and restarts the song after that, turning from one to the other as
 * frame 07 does; a swipe left does what it shows.
 *
 * Swipe the capsule right to skip, left to replay: it follows the finger,
 * resisting more the further it goes and compressing toward the button that
 * receives it; let go short of the threshold and it goes back. Past it, the
 * capsule is absorbed into the button, and the next song (or the same one,
 * restarted) is born from exactly where it disappeared. The buttons do the
 * same with a tap. Every movement is Figma's own timeline ([MiniMotionData]).
 *
 * The song changes, and so does what is shown, at the handoff, while neither
 * capsule is visible. [current] is the player's song; [next] the one a skip
 * goes to (null when there is none: nothing to swipe to). [progress] is how
 * far into [current] it is, 0..1, read every frame.
 */
@Composable
fun GestureMiniPlayer(
    current: MiniTrack,
    next: MiniTrack?,
    isPlaying: Boolean,
    progress: () -> Float,
    positionMs: () -> Long,
    actions: GestureMiniActions,
    labels: GestureMiniLabels,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    /** The song's length, for automatic next (frame 06) as it ends; 0 when unknown. */
    durationMs: () -> Long = { 0L },
    state: MiniMotionState = rememberMiniMotionState(),
    /** The song before [current] in the queue, for the left button's first 5 s; null when there is none. */
    previous: MiniTrack? = null,
    artwork: @Composable (MiniTrack, Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(actions)
    state.Follow(current, next, positionMs, reducedMotion, durationMs, isPlaying, hasPrevious = previous != null)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(80.dp)
            .testTag(MiniTags.PLAYER),
    ) {
        val geometry = remember(maxWidth) { MiniGeometry(maxWidth.value) }
        val pose = state.pose(geometry)
        val shown = state.shown(current)
        val incoming = state.incoming
        val canSkip = next != null && !state.busy
        val canReplay = !state.busy
        val density = LocalDensity.current
        val latestNext by rememberUpdatedState(next)
        val latestShown by rememberUpdatedState(shown)
        val latestPrevious by rememberUpdatedState(previous)
        val goesBack = state.leftIsPrevious && previous != null
        // The left button: back to the song before, or this one again.
        fun left(fromDrag: Boolean = false) {
            val before = latestPrevious
            if (state.leftIsPrevious && before != null) state.previous(scope, before, reducedMotion, fromDrag) { latest.onPrevious() }
            else state.replay(scope, latestShown, reducedMotion, fromDrag) { latest.onReplay() }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(40.dp))
                .background(MiniColors.Player)
                .border(1.dp, MiniColors.PlayerEdge, RoundedCornerShape(40.dp))
                .semantics {
                    // The swipes have buttons too; these are the same, for a screen reader's actions menu.
                    customActions = listOfNotNull(
                        if (previous != null) CustomAccessibilityAction(labels.previous) {
                            if (canReplay) state.previous(scope, previous, reducedMotion) { latest.onPrevious() }; canReplay
                        } else null,
                        CustomAccessibilityAction(labels.replay) { if (canReplay) state.replay(scope, shown, reducedMotion) { latest.onReplay() }; canReplay },
                        if (canSkip) CustomAccessibilityAction(labels.next) { state.skip(scope, next!!, reducedMotion) { latest.onSkip() }; true } else null,
                        CustomAccessibilityAction(if (isPlaying) labels.pause else labels.play) { latest.onPlayPause(); true },
                    )
                }
                .pointerInput(geometry, reducedMotion) {
                    state.detectSwipes(
                        input = this,
                        scope = scope,
                        g = geometry,
                        nextTrack = { latestNext },
                        leftTrack = { latestPrevious?.takeIf { state.leftIsPrevious } ?: latestShown },
                        reducedMotion = reducedMotion,
                        onSkip = { latestNext?.let { state.skip(scope, it, reducedMotion, fromDrag = true) { latest.onSkip() } } },
                        onReplay = { left(fromDrag = true) },
                        onExpand = { latest.onExpand() },
                    )
                },
        ) {
            // Resting capsule outline, elastic trail and absorption bridge: drawn under everything.
            if (pose.outlineAlpha > 0f) {
                Box(
                    Modifier
                        .offset(geometry.capsuleX.dp, geometry.capsuleY.dp)
                        .size(geometry.capsuleWidth.dp, geometry.capsuleHeight.dp)
                        .graphicsLayer { alpha = pose.outlineAlpha }
                        .border(1.dp, MiniColors.PlayerEdge, RoundedCornerShape(32.dp)),
                )
            }
            if (pose.trailAlpha > 0f) ElasticTrail(geometry, pose.trailIsReplay, pose.trailAlpha)
            if (pose.bridgeAlpha > 0f) {
                // "Absorption bridge": 26 x 36, radius 12, stretched about its centre toward the button.
                Box(
                    Modifier
                        .offset(pose.bridgeX.dp, 22.dp)
                        .size(26.dp, 36.dp)
                        .graphicsLayer {
                            alpha = pose.bridgeAlpha
                            translationX = pose.bridgeShiftX * this.density
                            scaleX = pose.bridgeScaleX
                            scaleY = pose.bridgeScaleY
                        }
                        .background(MiniColors.Bridge, RoundedCornerShape(12.dp)),
                )
            }

            if (pose.outgoing.visible && pose.outgoing.alpha > 0f) {
                Capsule(
                    pose.outgoing, geometry, shown, progress, density, artwork,
                    Modifier
                        .testTag(MiniTags.CAPSULE)
                        .semantics {
                            contentDescription = labels.nowPlaying
                            stateDescription = labels.state
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClickLabel = labels.open,
                        ) { latest.onExpand() },
                )
            }
            val inPose = pose.incoming
            if (incoming != null && inPose != null && inPose.alpha > 0f) {
                Capsule(inPose, geometry, incoming, { if (state.mode == MiniMode.Replay) 0f else progress() }, density, artwork, Modifier)
            }

            // The side buttons over the capsules, as in Figma: the song goes in under the button that takes it.
            SideButton(
                x = geometry.replayX, y = geometry.buttonY, pose = pose.replay, tag = MiniTags.REPLAY,
                label = if (goesBack) labels.previous else labels.replay, enabled = canReplay,
                onClick = { left() },
                transformIcon = false,
            ) {
                Box {
                    // Frame 07's previous-track glyph: the skip glyph turned half round.
                    SkipGlyph(
                        MiniColors.SkipFillAtRest, pose.replay.stroke,
                        Modifier.graphicsLayer {
                            alpha = pose.replay.previousAlpha
                            translationX = pose.replay.previousX * this.density
                            rotationZ = 180f
                        },
                    )
                    ReplayGlyph(
                        pose.replay.stroke,
                        Modifier.graphicsLayer {
                            alpha = pose.replay.iconAlpha
                            translationX = pose.replay.iconX * this.density
                            rotationZ = pose.replay.iconRotationDegrees
                        },
                    )
                }
            }
            SideButton(
                x = geometry.skipX, y = geometry.buttonY, pose = pose.skip, tag = MiniTags.SKIP,
                label = labels.next, enabled = canSkip,
                onClick = { next?.let { state.skip(scope, it, reducedMotion) { latest.onSkip() } } },
                overlay = pose.prepare?.let { prepare -> { PreparingGlyph(prepare) } },
            ) { SkipGlyph(pose.skip.fill, pose.skip.stroke) }

            // Play/pause sits over both capsules, at its resting place.
            Box(
                Modifier
                    .offset(geometry.pauseX.dp, geometry.pauseY.dp)
                    .size(48.dp)
                    .graphicsLayer {
                        alpha = pose.pauseAlpha
                        translationX = pose.pauseX * this.density
                        scaleX = pose.pauseScale
                        scaleY = pose.pauseScale
                    }
                    .clip(CircleShape)
                    .testTag(MiniTags.PAUSE)
                    .clickable(
                        enabled = pose.pauseAlpha > 0.5f,
                        role = Role.Button,
                        onClickLabel = if (isPlaying) labels.pause else labels.play,
                    ) { latest.onPlayPause() }
                    .semantics { contentDescription = if (isPlaying) labels.pause else labels.play },
                contentAlignment = Alignment.Center,
            ) { PauseGlyph(isPlaying) }
        }
    }
}

@Composable
private fun SideButton(
    x: Float,
    y: Float,
    pose: ButtonPose,
    tag: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    overlay: (@Composable () -> Unit)? = null,
    /** False when [icon] places and fades its own glyphs (the left button's two). */
    transformIcon: Boolean = true,
    icon: @Composable () -> Unit,
) {
    Box(
        Modifier
            .offset(x.dp, y.dp)
            .size(48.dp)
            .graphicsLayer {
                translationX = pose.offsetX * density
                scaleX = pose.scaleX
                scaleY = pose.scaleY
            }
            .clip(CircleShape)
            .background(pose.background)
            .testTag(tag)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics {
                contentDescription = label
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.graphicsLayer {
            if (transformIcon) {
                translationX = pose.iconX * density
                alpha = pose.iconAlpha
            }
        }) { icon() }
        overlay?.invoke()
    }
}

/**
 * One capsule. Its dimensions are what animate; what it holds keeps its
 * resting size and place and is clipped by it, never squeezed.
 */
@Composable
private fun Capsule(
    pose: CapsulePose,
    g: MiniGeometry,
    track: MiniTrack,
    progress: () -> Float,
    density: Density,
    artwork: @Composable (MiniTrack, Modifier) -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(pose.radius.dp)
    Box(
        Modifier
            .offset(pose.x.dp, pose.y.dp)
            .size(pose.width.dp, pose.height.dp)
            .graphicsLayer {
                alpha = pose.alpha
                scaleX = pose.scaleX
                scaleY = pose.scaleY
                transformOrigin = TransformOrigin.Center
            }
            .then(if (pose.glowAlpha > 0f) Modifier.glow(pose.radius, pose.glowBlur, pose.glowAlpha) else Modifier)
            .clip(shape)
            .background(pose.background)
            .then(modifier),
    ) {
        Box(
            Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize(g.capsuleWidth.dp, g.capsuleHeight.dp),
        ) {
            // The page's large text is honoured up to 130% inside a 64 dp capsule.
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.3f))) {
                artwork(
                    track,
                    Modifier
                        .offset(10.dp, 14.dp)
                        .size(36.dp)
                        .graphicsLayer {
                            translationX = pose.artX * density.density
                            translationY = pose.artY * density.density
                            scaleX = pose.artScale
                            scaleY = pose.artScale
                            alpha = pose.artAlpha
                        }
                        .figmaBlur(pose.artBlur)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Text(
                    track.title,
                    style = TitleStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .offset(49.dp, 14.dp)
                        .width(g.textWidth.dp)
                        .graphicsLayer {
                            translationX = pose.titleX * density.density
                            translationY = pose.titleY * density.density
                            alpha = pose.titleAlpha
                        }
                        .figmaBlur(pose.titleBlur),
                )
                Text(
                    track.artist,
                    style = ArtistStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .offset(49.dp, 35.dp)
                        .width(g.textWidth.dp)
                        .graphicsLayer {
                            translationX = pose.artistX * density.density
                            translationY = pose.artistY * density.density
                            alpha = pose.artistAlpha
                        }
                        .figmaBlur(pose.artistBlur),
                )
            }
            ProgressRail(pose, g, progress)
        }
    }
}

/** The track duration rail: Figma's widths during a transition, the real position otherwise. */
@Composable
private fun ProgressRail(pose: CapsulePose, g: MiniGeometry, progress: () -> Float) {
    Box(
        Modifier
            .offset(62.dp, 59.dp)
            .size(g.railWidth.dp, 2.dp)
            .graphicsLayer {
                translationX = pose.railX * density
                translationY = pose.railY * density
                scaleY = pose.railScaleY
                alpha = pose.railAlpha
                scaleX = pose.railScaleX
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .drawBehind {
                val unit = density
                val rail = g.railWidth
                val real = (progress().coerceIn(0f, 1f) * rail)
                val figma = pose.progressFigma
                // Replay: Figma's 90 -> 2 is "from where it was to the start", so it starts from the real width.
                val width = when {
                    figma == null -> real.coerceAtLeast(2f)
                    figma > 4f -> 2f + (figma - 2f) / 88f * (real - 2f).coerceAtLeast(0f)
                    else -> figma
                }
                drawRoundRect(MiniColors.Rail, cornerRadius = CornerRadius(1f * unit))
                drawRoundRect(
                    MiniColors.Progress,
                    size = androidx.compose.ui.geometry.Size(width * unit, size.height),
                    cornerRadius = CornerRadius(1f * unit),
                )
                // The restart position: a 4 dp dot at the start of a song born in a transition (frames 03, 04, 06;
                // 04 stretches it sideways about its centre as the song settles back in). At rest there is none (07).
                if (figma != null && width <= 4f) {
                    val w = 4f * pose.restartScaleX
                    drawRoundRect(
                        MiniColors.Progress,
                        topLeft = androidx.compose.ui.geometry.Offset((2f - w / 2f) * unit, -1f * unit),
                        size = androidx.compose.ui.geometry.Size(w * unit, 4f * unit),
                        cornerRadius = CornerRadius(2f * unit),
                    )
                }
            },
    )
}

/** Figma's elastic motion trail: two 1 dp curves (path data from its SVG), at 36% under the track's opacity. */
@Composable
private fun ElasticTrail(g: MiniGeometry, replay: Boolean, alpha: Float) {
    val shift = if (replay) g.width - 388f else 0f
    val path = remember(replay) {
        androidx.compose.ui.graphics.vector.PathParser().parsePathString(
            if (replay) "M305 26C273 26 269 32 228 32M305 54C273 54 269 48 228 48"
            else "M83 26C115 26 119 32 160 32M83 54C115 54 119 48 160 48",
        ).toPath()
    }
    androidx.compose.foundation.Canvas(
        Modifier
            .width(g.width.dp)
            .height(80.dp)
            .graphicsLayer { this.alpha = alpha * 0.36f },
    ) {
        val unit = density
        withTransform({
            translate(left = shift * unit - 1f * unit, top = -1f * unit)
            scale(unit, unit, pivot = androidx.compose.ui.geometry.Offset.Zero)
        }) {
            drawPath(path, MiniColors.Icon, style = Stroke(1f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}

/** CSS blur(px) is a standard deviation; Android's blur radius converts to one (Skia: sigma = 0.57735 r + 0.5). */
private fun Modifier.figmaBlur(sigma: Float): Modifier =
    if (sigma <= 0.01f) this else blur(((sigma - 0.5f).coerceAtLeast(0f) / 0.57735f).dp, BlurredEdgeTreatment.Unbounded)

/** CSS box-shadow 0 0 [blur] 0 rgba(166, 255, 204, [alpha]) around the capsule. */
private fun Modifier.glow(radius: Float, blur: Float, alpha: Float): Modifier = drawBehind {
    val sigma = blur / 2f * density
    if (sigma <= 0f) return@drawBehind
    drawIntoCanvas { canvas ->
        // The shape itself, blurred: the capsule's own fill then covers its inside, as a spread-0 shadow.
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = MiniColors.Glow.copy(alpha = alpha).toArgb()
            maskFilter = android.graphics.BlurMaskFilter(
                ((sigma - 0.5f).coerceAtLeast(0.1f) / 0.57735f),
                android.graphics.BlurMaskFilter.Blur.NORMAL,
            )
        }
        val r = radius * density
        canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
    }
}
