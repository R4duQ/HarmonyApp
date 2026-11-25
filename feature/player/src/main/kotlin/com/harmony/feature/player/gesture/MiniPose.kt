package com.harmony.feature.player.gesture

import androidx.compose.ui.graphics.Color

/*
 * Where every layer of the mini player is, at one moment.
 *
 * All geometry is in dp, in the player's own coordinates, for the Figma
 * reference size 388 x 80 and adapted to other widths: the side buttons keep
 * their place against each edge, the capsule takes the width between them,
 * and the travel to a button follows that button. At 388 every number is
 * Figma's own.
 */

internal enum class MiniMode {
    /** At rest (Figma frame 01). */
    Rest,
    /** Swipe right / skip: absorbed into the skip button, the next song unfolds out of it (frame 02). */
    Skip,
    /** The next song grows out of the skip button, for a skip made elsewhere (frame 03). */
    SkipCompleted,
    /** Swipe left / replay: absorbed into the replay button, the song restarts (frame 04). */
    Replay,
    /** The restart pulse, for a restart made elsewhere (frame 05). */
    ReplayCompleted,
    /**
     * The song ends by itself: the skip button prepares the next song (it
     * holds there, pulsing, until the player has it), then the capsule flips
     * over to show it (frame 06).
     */
    AutoNext,
}

/** The fixed parts of the layout for a player [width] dp wide (Figma: 388). */
internal class MiniGeometry(val width: Float) {
    val height = 80f
    val capsuleX = 64f
    val capsuleY = 8f
    val capsuleWidth = width - 128f
    val capsuleHeight = 64f
    val buttonY = 16f
    val replayX = 8f
    val skipX = width - 56f
    val pauseX = capsuleX + capsuleWidth - 48f
    val pauseY = 16f
    val railWidth = capsuleWidth - 122f
    val textWidth = capsuleWidth - 97f
    val skipBridgeX = width - 69f
    val replayBridgeX = 43f

    /** Skip: the capsule travels to the skip button (Figma 286 at 388). */
    fun skipTravel(figma: Float): Float = figma * (width - 102f) / 286f

    /** Skip: a width keyframe, the 260 resting width taking the space there is (12 stays 12, the size it is absorbed at). */
    fun skipWidth(figma: Float): Float = 12f + (figma - 12f) * (capsuleWidth - 12f) / 248f

    /** Replay: a width keyframe (12 stays 12, the size it is absorbed at and born from). */
    fun replayWidth(figma: Float): Float = 12f + (figma - 12f) * (capsuleWidth - 12f) / 248f
}

/** The capsule: the outgoing song, or the incoming one. */
internal data class CapsulePose(
    val visible: Boolean = true,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val radius: Float = 32f,
    val background: Color = MiniColors.Capsule,
    val alpha: Float = 1f,
    /** A transform scale around the centre (frames 04 and 05 only); dimensions are animated otherwise. */
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val glowBlur: Float = 0f,
    val glowAlpha: Float = 0f,
    val artScale: Float = 1f,
    val artX: Float = 0f,
    val artY: Float = 0f,
    val artAlpha: Float = 1f,
    val artBlur: Float = 0f,
    val titleX: Float = 0f,
    val titleY: Float = 0f,
    val titleAlpha: Float = 1f,
    val titleBlur: Float = 0f,
    val artistX: Float = 0f,
    val artistY: Float = 0f,
    val artistAlpha: Float = 1f,
    val artistBlur: Float = 0f,
    val railX: Float = 0f,
    val railY: Float = 0f,
    val railScaleY: Float = 1f,
    val railScaleX: Float = 1f,
    val railAlpha: Float = 1f,
    /** Figma's own progress width (2..90 dp) when the timeline animates it; null shows the real position. */
    val progressFigma: Float? = null,
    /** The restart position mark's sideways stretch (frame 04's restarted song). */
    val restartScaleX: Float = 1f,
)

/** The skip button preparing the next song (frame 06): pressure ring, orbit and core. */
internal data class PreparePose(
    val ringScale: Float,
    val ringAlpha: Float,
    val spinDegrees: Float,
    val spinScale: Float,
    val spinAlpha: Float,
    val coreScale: Float,
    val coreAlpha: Float,
)

internal data class ButtonPose(
    /** The whole button's sideways nudge. */
    val offsetX: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val background: Color = MiniColors.Button,
    val iconX: Float = 0f,
    val iconAlpha: Float = 1f,
    val iconRotationDegrees: Float = 0f,
    val fill: Color,
    val stroke: Color = MiniColors.Icon,
    /** The left button only: its previous-track glyph (frame 07), shown before the replay arrow takes over. */
    val previousAlpha: Float = 0f,
    val previousX: Float = 0f,
)

internal data class MiniPose(
    val outlineAlpha: Float = 0f,
    val trailAlpha: Float = 0f,
    /** Which trail: the replay one is drawn against the right, the skip one against the left. */
    val trailIsReplay: Boolean = false,
    val bridgeAlpha: Float = 0f,
    val bridgeX: Float = 0f,
    val bridgeShiftX: Float = 0f,
    val bridgeScaleX: Float = 1f,
    val bridgeScaleY: Float = 1f,
    val replay: ButtonPose,
    val skip: ButtonPose,
    val outgoing: CapsulePose,
    val incoming: CapsulePose?,
    val pauseAlpha: Float = 1f,
    val pauseScale: Float = 1f,
    val pauseX: Float = 0f,
    val prepare: PreparePose? = null,
)

internal object MiniColors {
    val Player = Color(0xFF17211E)
    val PlayerEdge = Color(0xFF334A3F)
    val Button = Color(0xFF24372E)
    val Icon = Color(0xFFBDFFD9)
    /** The skip triangle's fill at rest, as frame 01 has it. */
    val SkipFillAtRest = Color(0xFF000000)
    val Capsule = Color(0xFFC5E8D5)
    val Title = Color(0xFF132D23)
    val Artist = Color(0xFF526E60)
    val Rail = Color(0xFF9ABDAB)
    val Progress = Color(0xFF132D23)
    val Bridge = Color(0xFFBDFFD9)
    val Glow = Color(0xFFA6FFCC)
}

/**
 * Every layer at [t] ms into [mode]'s Figma timeline. [leftMorphMs] is where
 * the left button is in frame 07 (0: previous track, past 5.4 s: replay);
 * [previousRun] says the replay timeline is taking the song back to the one
 * before, so the left button keeps its previous-track glyph through it.
 */
internal fun miniPose(
    mode: MiniMode,
    t: Float,
    geometry: MiniGeometry,
    extraSpinDegrees: Float = 0f,
    leftMorphMs: Float = LEFT_MORPH_REPLAY_MS,
    previousRun: Boolean = false,
): MiniPose {
    val g = geometry
    val restCapsule = CapsulePose(x = g.capsuleX, y = g.capsuleY, width = g.capsuleWidth, height = g.capsuleHeight)
    val restReplay = leftButton(leftMorphMs)
    val restSkip = ButtonPose(fill = MiniColors.SkipFillAtRest)
    return when (mode) {
        MiniMode.Rest -> MiniPose(replay = restReplay, skip = restSkip, outgoing = restCapsule, incoming = null)
        MiniMode.Skip -> skipPose(MiniMotionData.SKIP, t, g, restCapsule, restReplay)
        MiniMode.SkipCompleted -> skipCompletedPose(MiniMotionData.SKIP_COMPLETED, t, g, restCapsule, restReplay)
        MiniMode.Replay -> replayPose(MiniMotionData.REPLAY, t, g, restCapsule, restSkip, previousRun)
        MiniMode.AutoNext -> autoNextPose(MiniMotionData.AUTO_NEXT, t, g, restCapsule, restReplay, extraSpinDegrees)
        MiniMode.ReplayCompleted -> {
            val tl = MiniMotionData.REPLAY_COMPLETED
            MiniPose(
                replay = restReplay.copy(
                    scaleX = tl.number("replayButton", Prop.ScaleX, t, 1f),
                    scaleY = tl.number("replayButton", Prop.ScaleY, t, 1f),
                ),
                skip = restSkip,
                outgoing = restCapsule.copy(
                    scaleX = tl.number("capsule", Prop.ScaleX, t, 1f),
                    scaleY = tl.number("capsule", Prop.ScaleY, t, 1f),
                ),
                incoming = null,
            )
        }
    }
}

/** Frame 07's turn from previous track to replay begins at 5 s into its timeline (75.758 %)... */
internal const val LEFT_MORPH_FROM_MS = 5_000f
/** ...and has settled by 5.36 s (81.212 %): the pulse back to 1. */
internal const val LEFT_MORPH_TO_MS = 5_360f
/** Any time from here on is the replay arrow at rest. */
internal const val LEFT_MORPH_REPLAY_MS = 6_600f

/**
 * The left button at rest, [ms] into frame 07: the previous-track glyph
 * fading and sliding 6 dp left, the replay arrow coming in from 6 dp right of
 * centre turning from 24° to upright, and the button's pulse to 1.065.
 */
internal fun leftButton(ms: Float): ButtonPose {
    val tl = MiniMotionData.PREVIOUS_TO_REPLAY
    val t = ms.coerceIn(0f, tl.durationMs)
    return ButtonPose(
        scaleX = tl.number("leftButton", Prop.ScaleX, t, 1f),
        scaleY = tl.number("leftButton", Prop.ScaleY, t, 1f),
        // The arrow's frame sits 6 dp right of the button's centre and slides back by 6.
        iconX = 6f + tl.number("replayIcon", Prop.TranslateX, t, 0f),
        iconAlpha = tl.number("replayIcon", Prop.Opacity, t, 1f),
        iconRotationDegrees = Math.toDegrees(tl.number("replayIcon", Prop.Rotate, t, 0f).toDouble()).toFloat(),
        fill = Color.Transparent,
        previousAlpha = tl.number("previousGlyph", Prop.Opacity, t, 0f),
        previousX = tl.number("previousGlyph", Prop.TranslateX, t, 0f),
    )
}

private fun skipButton(tl: Timeline, t: Float) = ButtonPose(
    scaleX = tl.number("skipButton", Prop.ScaleX, t, 1f),
    scaleY = tl.number("skipButton", Prop.ScaleY, t, 1f),
    background = tl.color("skipButton", Prop.Background, t, MiniColors.Button),
    iconX = tl.number("skipIcon", Prop.TranslateX, t, 0f),
    iconAlpha = tl.number("skipIcon", Prop.Opacity, t, 1f),
    fill = tl.color("skipTriangle", Prop.Fill, t, MiniColors.SkipFillAtRest),
    stroke = tl.color("skipTriangle", Prop.Stroke, t, MiniColors.Icon),
)

/** The next song in a skip: born where the outgoing one is absorbed, unfolding leftward. */
private fun skipIncoming(tl: Timeline, t: Float, g: MiniGeometry) = CapsulePose(
    x = g.capsuleX + g.skipTravel(tl.number("incoming", Prop.TranslateX, t, 0f)),
    y = g.capsuleY + tl.number("incoming", Prop.TranslateY, t, 0f),
    width = g.skipWidth(tl.number("incoming", Prop.Width, t, 260f)),
    height = tl.number("incoming", Prop.Height, t, 64f),
    radius = tl.number("incoming", Prop.Radius, t, 32f),
    background = tl.color("incoming", Prop.Background, t, MiniColors.Capsule),
    alpha = tl.number("incoming", Prop.Opacity, t, 1f),
    glowBlur = tl.number("incoming", Prop.ShadowBlur, t, 0f),
    glowAlpha = tl.number("incoming", Prop.ShadowAlpha, t, 0f),
    artScale = tl.number("inArt", Prop.ScaleX, t, 1f),
    artX = tl.number("inArt", Prop.TranslateX, t, 0f),
    artY = tl.number("inArt", Prop.TranslateY, t, 0f),
    artAlpha = tl.number("inArt", Prop.Opacity, t, 1f),
    artBlur = tl.number("inArt", Prop.Blur, t, 0f),
    titleX = tl.number("inTitle", Prop.TranslateX, t, 0f),
    titleY = tl.number("inTitle", Prop.TranslateY, t, 0f),
    titleAlpha = tl.number("inTitle", Prop.Opacity, t, 1f),
    titleBlur = tl.number("inTitle", Prop.Blur, t, 0f),
    artistX = tl.number("inArtist", Prop.TranslateX, t, 0f),
    artistY = tl.number("inArtist", Prop.TranslateY, t, 0f),
    artistAlpha = tl.number("inArtist", Prop.Opacity, t, 1f),
    artistBlur = tl.number("inArtist", Prop.Blur, t, 0f),
    railY = tl.number("inRail", Prop.TranslateY, t, 0f),
    railScaleX = tl.number("inRail", Prop.ScaleX, t, 1f),
    railAlpha = tl.number("inRail", Prop.Opacity, t, 1f),
    progressFigma = tl.number("inProgress", Prop.Width, t, 4f),
)

/** The absorption bridge between the capsule and the button it goes into (frames 02 and 04). */
private fun MiniPose.withBridge(tl: Timeline, t: Float, x: Float) = copy(
    bridgeAlpha = tl.number("bridge", Prop.Opacity, t, 0f),
    bridgeX = x,
    bridgeShiftX = tl.number("bridge", Prop.TranslateX, t, 0f),
    bridgeScaleX = tl.number("bridge", Prop.ScaleX, t, 1f),
    bridgeScaleY = tl.number("bridge", Prop.ScaleY, t, 1f),
)

private fun skipPose(tl: Timeline, t: Float, g: MiniGeometry, rest: CapsulePose, restReplay: ButtonPose) = MiniPose(
    outlineAlpha = tl.number("outline", Prop.Opacity, t, 0f),
    trailAlpha = tl.number("trail", Prop.Opacity, t, 0f),
    trailIsReplay = false,
    replay = restReplay,
    skip = skipButton(tl, t),
    outgoing = rest.copy(
        x = g.capsuleX + g.skipTravel(tl.number("capsule", Prop.TranslateX, t, 0f)),
        y = g.capsuleY + tl.number("capsule", Prop.TranslateY, t, 0f),
        width = g.skipWidth(tl.number("capsule", Prop.Width, t, 260f)),
        height = tl.number("capsule", Prop.Height, t, 64f),
        radius = tl.number("capsule", Prop.Radius, t, 32f),
        background = tl.color("capsule", Prop.Background, t, MiniColors.Capsule),
        alpha = tl.number("capsule", Prop.Opacity, t, 1f),
        artScale = tl.number("art", Prop.ScaleX, t, 1f),
        artY = tl.number("art", Prop.TranslateY, t, 0f),
        artAlpha = tl.number("art", Prop.Opacity, t, 1f),
        titleX = tl.number("title", Prop.TranslateX, t, 0f),
        titleY = tl.number("title", Prop.TranslateY, t, 0f),
        titleAlpha = tl.number("title", Prop.Opacity, t, 1f),
        artistX = tl.number("artist", Prop.TranslateX, t, 0f),
        artistY = tl.number("artist", Prop.TranslateY, t, 0f),
        artistAlpha = tl.number("artist", Prop.Opacity, t, 1f),
        railX = tl.number("rail", Prop.TranslateX, t, 0f),
        railY = tl.number("rail", Prop.TranslateY, t, 0f),
        railAlpha = tl.number("rail", Prop.Opacity, t, 1f),
    ),
    incoming = skipIncoming(tl, t, g),
    pauseAlpha = tl.number("pause", Prop.Opacity, t, 1f),
    pauseX = tl.number("pause", Prop.TranslateX, t, 0f),
).withBridge(tl, t, g.skipBridgeX)

private fun skipCompletedPose(tl: Timeline, t: Float, g: MiniGeometry, rest: CapsulePose, restReplay: ButtonPose) = MiniPose(
    replay = restReplay,
    skip = skipButton(tl, t),
    // The song before is already gone: this timeline starts from the birth point.
    outgoing = rest.copy(visible = false, alpha = 0f),
    incoming = skipIncoming(tl, t, g),
    pauseAlpha = tl.number("pause", Prop.Opacity, t, 1f),
    pauseScale = tl.number("pause", Prop.ScaleX, t, 1f),
)

private fun replayPose(tl: Timeline, t: Float, g: MiniGeometry, rest: CapsulePose, restSkip: ButtonPose, previousRun: Boolean) = MiniPose(
    outlineAlpha = tl.number("outline", Prop.Opacity, t, 0f),
    trailAlpha = tl.number("trail", Prop.Opacity, t, 0f),
    trailIsReplay = true,
    replay = ButtonPose(
        offsetX = tl.number("replayButton", Prop.TranslateX, t, 0f),
        scaleX = tl.number("replayButton", Prop.ScaleX, t, 1f),
        scaleY = tl.number("replayButton", Prop.ScaleY, t, 1f),
        background = tl.color("replayButton", Prop.Background, t, MiniColors.Button),
        // Back to the song before: the button keeps its previous-track glyph, which doesn't spin.
        iconAlpha = if (previousRun) 0f else tl.number("replayIcon", Prop.Opacity, t, 1f),
        iconRotationDegrees = Math.toDegrees(tl.number("replayIcon", Prop.Rotate, t, 0f).toDouble()).toFloat(),
        fill = Color.Transparent,
        stroke = tl.color("replayVector", Prop.Stroke, t, MiniColors.Icon),
        previousAlpha = if (previousRun) tl.number("replayIcon", Prop.Opacity, t, 1f) else 0f,
    ),
    skip = restSkip,
    // The song is drawn into the replay button, its content sliding out to the left.
    outgoing = rest.copy(
        x = g.capsuleX + tl.number("capsule", Prop.TranslateX, t, 0f),
        y = g.capsuleY + tl.number("capsule", Prop.TranslateY, t, 0f),
        width = g.replayWidth(tl.number("capsule", Prop.Width, t, 260f)),
        height = tl.number("capsule", Prop.Height, t, 64f),
        radius = tl.number("capsule", Prop.Radius, t, 32f),
        background = tl.color("capsule", Prop.Background, t, MiniColors.Capsule),
        alpha = tl.number("capsule", Prop.Opacity, t, 1f),
        artScale = tl.number("art", Prop.ScaleX, t, 1f),
        artX = tl.number("art", Prop.TranslateX, t, 0f),
        artY = tl.number("art", Prop.TranslateY, t, 0f),
        artAlpha = tl.number("art", Prop.Opacity, t, 1f),
        titleX = tl.number("title", Prop.TranslateX, t, 0f),
        titleY = tl.number("title", Prop.TranslateY, t, 0f),
        titleAlpha = tl.number("title", Prop.Opacity, t, 1f),
        artistX = tl.number("artist", Prop.TranslateX, t, 0f),
        artistY = tl.number("artist", Prop.TranslateY, t, 0f),
        artistAlpha = tl.number("artist", Prop.Opacity, t, 1f),
        railX = tl.number("rail", Prop.TranslateX, t, 0f),
        railY = tl.number("rail", Prop.TranslateY, t, 0f),
        railAlpha = tl.number("rail", Prop.Opacity, t, 1f),
        progressFigma = tl.number("progress", Prop.Width, t, 90f),
    ),
    // The song restarted (or the one before): born where the old one went into the button, growing back into place.
    incoming = CapsulePose(
        x = g.capsuleX + tl.number("incoming", Prop.TranslateX, t, 0f),
        y = g.capsuleY + tl.number("incoming", Prop.TranslateY, t, 0f),
        width = g.replayWidth(tl.number("incoming", Prop.Width, t, 260f)),
        height = tl.number("incoming", Prop.Height, t, 64f),
        radius = tl.number("incoming", Prop.Radius, t, 32f),
        background = tl.color("incoming", Prop.Background, t, MiniColors.Capsule),
        alpha = tl.number("incoming", Prop.Opacity, t, 1f),
        glowBlur = tl.number("incoming", Prop.ShadowBlur, t, 0f),
        glowAlpha = tl.number("incoming", Prop.ShadowAlpha, t, 0f),
        artScale = tl.number("inArt", Prop.ScaleX, t, 1f),
        artX = tl.number("inArt", Prop.TranslateX, t, 0f),
        artY = tl.number("inArt", Prop.TranslateY, t, 0f),
        artAlpha = tl.number("inArt", Prop.Opacity, t, 1f),
        artBlur = tl.number("inArt", Prop.Blur, t, 0f),
        titleX = tl.number("inTitle", Prop.TranslateX, t, 0f),
        titleY = tl.number("inTitle", Prop.TranslateY, t, 0f),
        titleAlpha = tl.number("inTitle", Prop.Opacity, t, 1f),
        titleBlur = tl.number("inTitle", Prop.Blur, t, 0f),
        artistX = tl.number("inArtist", Prop.TranslateX, t, 0f),
        artistY = tl.number("inArtist", Prop.TranslateY, t, 0f),
        artistAlpha = tl.number("inArtist", Prop.Opacity, t, 1f),
        artistBlur = tl.number("inArtist", Prop.Blur, t, 0f),
        railY = tl.number("inRail", Prop.TranslateY, t, 0f),
        railScaleX = tl.number("inRail", Prop.ScaleX, t, 1f),
        railAlpha = tl.number("inRail", Prop.Opacity, t, 1f),
        progressFigma = tl.number("inProgress", Prop.Width, t, 2f),
        restartScaleX = tl.number("restartMark", Prop.ScaleX, t, 1f),
    ),
    pauseAlpha = tl.number("pause", Prop.Opacity, t, 1f),
    pauseX = tl.number("pause", Prop.TranslateX, t, 0f),
).withBridge(tl, t, g.replayBridgeX)

/**
 * Frame 06: the skip button prepares the next song while the capsule stays
 * where it is; then the capsule flips over: "Song capsule flip" squeezes it
 * to 0.022 of its height and back, and at the edge-on moment its front (this
 * song) gives way to its back (the next). Figma also keys a rotateX on the
 * two faces, but its own render of the frame (the exported video) shows only
 * the squeeze, upright both sides: that is what is drawn.
 */
private fun autoNextPose(
    tl: Timeline,
    t: Float,
    g: MiniGeometry,
    rest: CapsulePose,
    restReplay: ButtonPose,
    extraSpinDegrees: Float,
): MiniPose {
    val squeeze = tl.number("flip", Prop.ScaleY, t, 1f)
    return MiniPose(
        outlineAlpha = tl.number("outline", Prop.Opacity, t, 0f),
        trailAlpha = tl.number("trail", Prop.Opacity, t, 0f),
        trailIsReplay = false,
        bridgeAlpha = tl.number("bridge", Prop.Opacity, t, 0f),
        bridgeX = g.skipBridgeX,
        replay = restReplay,
        skip = skipButton(tl, t).copy(offsetX = tl.number("skipButton", Prop.TranslateX, t, 0f)),
        // Figma fills the rail to the end (126 -> 138) as the song ends: the song's own position does that here.
        outgoing = rest.copy(alpha = tl.number("capsule", Prop.Opacity, t, 1f), scaleY = squeeze),
        incoming = rest.copy(
            alpha = tl.number("incoming", Prop.Opacity, t, 0f),
            scaleY = squeeze,
            progressFigma = tl.number("inProgress", Prop.Width, t, 4f),
        ),
        prepare = PreparePose(
            ringScale = tl.number("ring", Prop.ScaleX, t, 1f),
            ringAlpha = tl.number("ring", Prop.Opacity, t, 0f),
            spinDegrees = Math.toDegrees(tl.number("preparing", Prop.Rotate, t, 0f).toDouble()).toFloat() + extraSpinDegrees,
            spinScale = tl.number("preparing", Prop.ScaleX, t, 1f),
            spinAlpha = tl.number("preparing", Prop.Opacity, t, 0f),
            coreScale = tl.number("core", Prop.ScaleX, t, 1f),
            coreAlpha = tl.number("core", Prop.Opacity, t, 1f),
        ),
    )
}

/**
 * Frame 06 holds here while the next song isn't playing yet: from 45.7% to
 * 61.8% of its 2.8 s the ring, the core and the button pulse through one
 * full cycle and come back to where they started, so the cycle repeats
 * without a seam (the orbit keeps turning: see [autoNextLoopSpinDegrees]).
 */
internal val AUTO_NEXT_HOLD_FROM_MS = 0.457f * 2800f
internal val AUTO_NEXT_HOLD_TO_MS = 0.618f * 2800f

/** How far the orbit turns in one hold cycle, carried over so it keeps turning. */
internal fun autoNextLoopSpinDegrees(): Float {
    val tl = MiniMotionData.AUTO_NEXT
    val a = tl.number("preparing", Prop.Rotate, AUTO_NEXT_HOLD_FROM_MS, 0f)
    val b = tl.number("preparing", Prop.Rotate, AUTO_NEXT_HOLD_TO_MS, 0f)
    return Math.toDegrees((b - a).toDouble()).toFloat()
}

/** The Figma timeline behind [mode] (Rest has none). */
internal fun timelineOf(mode: MiniMode): Timeline? = when (mode) {
    MiniMode.Rest -> null
    MiniMode.Skip -> MiniMotionData.SKIP
    MiniMode.SkipCompleted -> MiniMotionData.SKIP_COMPLETED
    MiniMode.Replay -> MiniMotionData.REPLAY
    MiniMode.ReplayCompleted -> MiniMotionData.REPLAY_COMPLETED
    MiniMode.AutoNext -> MiniMotionData.AUTO_NEXT
}

/**
 * The moment the song changes in [mode]: the outgoing capsule fully gone and
 * the incoming one not yet shown. The playback command and the metadata
 * switch both happen here, where nothing of either song is visible.
 */
internal fun handoffMs(mode: MiniMode): Float = when (mode) {
    MiniMode.Skip -> MiniMotionData.SKIP.goneFrom("capsule")
    MiniMode.Replay -> MiniMotionData.REPLAY.goneFrom("capsule")
    // The capsule is edge-on: the front (this song) gives way to the back (the next).
    MiniMode.AutoNext -> MiniMotionData.AUTO_NEXT.goneFrom("capsule")
    else -> 0f
}
