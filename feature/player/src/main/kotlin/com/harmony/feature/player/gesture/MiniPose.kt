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

    /** Replay: a width keyframe (3 stays 3, the size it is absorbed at). */
    fun replayWidth(figma: Float): Float = 3f + (figma - 3f) * (capsuleWidth - 3f) / 257f
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
    val railScaleX: Float = 1f,
    val railAlpha: Float = 1f,
    /** Figma's own progress width (2..90 dp) when the timeline animates it; null shows the real position. */
    val progressFigma: Float? = null,
)

internal data class ButtonPose(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val background: Color = MiniColors.Button,
    val iconX: Float = 0f,
    val iconAlpha: Float = 1f,
    val iconRotationDegrees: Float = 0f,
    val fill: Color,
    val stroke: Color = MiniColors.Icon,
)

internal data class MiniPose(
    val outlineAlpha: Float = 0f,
    val trailAlpha: Float = 0f,
    /** Which trail: the replay one is drawn against the right, the skip one against the left. */
    val trailIsReplay: Boolean = false,
    val bridgeAlpha: Float = 0f,
    val bridgeX: Float = 0f,
    val replay: ButtonPose,
    val skip: ButtonPose,
    val outgoing: CapsulePose,
    val incoming: CapsulePose?,
    val pauseAlpha: Float = 1f,
    val pauseScale: Float = 1f,
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

/** Every layer at [t] ms into [mode]'s Figma timeline. */
internal fun miniPose(mode: MiniMode, t: Float, geometry: MiniGeometry): MiniPose {
    val g = geometry
    val restCapsule = CapsulePose(x = g.capsuleX, y = g.capsuleY, width = g.capsuleWidth, height = g.capsuleHeight)
    val restReplay = ButtonPose(fill = Color.Transparent)
    val restSkip = ButtonPose(fill = MiniColors.SkipFillAtRest)
    return when (mode) {
        MiniMode.Rest -> MiniPose(replay = restReplay, skip = restSkip, outgoing = restCapsule, incoming = null)
        MiniMode.Skip -> skipPose(MiniMotionData.SKIP, t, g, restCapsule, restReplay)
        MiniMode.SkipCompleted -> skipCompletedPose(MiniMotionData.SKIP_COMPLETED, t, g, restCapsule, restReplay)
        MiniMode.Replay -> replayPose(MiniMotionData.REPLAY, t, g, restCapsule, restSkip)
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
    artAlpha = tl.number("inArt", Prop.Opacity, t, 1f),
    artBlur = tl.number("inArt", Prop.Blur, t, 0f),
    titleX = tl.number("inTitle", Prop.TranslateX, t, 0f),
    titleAlpha = tl.number("inTitle", Prop.Opacity, t, 1f),
    titleBlur = tl.number("inTitle", Prop.Blur, t, 0f),
    artistX = tl.number("inArtist", Prop.TranslateX, t, 0f),
    artistAlpha = tl.number("inArtist", Prop.Opacity, t, 1f),
    artistBlur = tl.number("inArtist", Prop.Blur, t, 0f),
    railScaleX = tl.number("inRail", Prop.ScaleX, t, 1f),
    railAlpha = tl.number("inRail", Prop.Opacity, t, 1f),
    progressFigma = tl.number("inProgress", Prop.Width, t, 4f),
)

private fun skipPose(tl: Timeline, t: Float, g: MiniGeometry, rest: CapsulePose, restReplay: ButtonPose) = MiniPose(
    outlineAlpha = tl.number("outline", Prop.Opacity, t, 0f),
    trailAlpha = tl.number("trail", Prop.Opacity, t, 0f),
    trailIsReplay = false,
    bridgeAlpha = tl.number("bridge", Prop.Opacity, t, 0f),
    bridgeX = g.skipBridgeX,
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
        artAlpha = tl.number("art", Prop.Opacity, t, 1f),
        titleX = tl.number("title", Prop.TranslateX, t, 0f),
        titleAlpha = tl.number("title", Prop.Opacity, t, 1f),
        artistX = tl.number("artist", Prop.TranslateX, t, 0f),
        artistAlpha = tl.number("artist", Prop.Opacity, t, 1f),
        railX = tl.number("rail", Prop.TranslateX, t, 0f),
        railAlpha = tl.number("rail", Prop.Opacity, t, 1f),
    ),
    incoming = skipIncoming(tl, t, g),
    pauseAlpha = tl.number("pause", Prop.Opacity, t, 1f),
)

private fun skipCompletedPose(tl: Timeline, t: Float, g: MiniGeometry, rest: CapsulePose, restReplay: ButtonPose) = MiniPose(
    replay = restReplay,
    skip = skipButton(tl, t),
    // The song before is already gone: this timeline starts from the birth point.
    outgoing = rest.copy(visible = false, alpha = 0f),
    incoming = skipIncoming(tl, t, g),
    pauseAlpha = tl.number("pause", Prop.Opacity, t, 1f),
    pauseScale = tl.number("pause", Prop.ScaleX, t, 1f),
)

private fun replayPose(tl: Timeline, t: Float, g: MiniGeometry, rest: CapsulePose, restSkip: ButtonPose) = MiniPose(
    outlineAlpha = tl.number("outline", Prop.Opacity, t, 0f),
    trailAlpha = tl.number("trail", Prop.Opacity, t, 0f),
    trailIsReplay = true,
    bridgeAlpha = tl.number("bridge", Prop.Opacity, t, 0f),
    bridgeX = g.replayBridgeX,
    replay = ButtonPose(
        scaleX = tl.number("replayButton", Prop.ScaleX, t, 1f),
        scaleY = tl.number("replayButton", Prop.ScaleY, t, 1f),
        background = tl.color("replayButton", Prop.Background, t, MiniColors.Button),
        iconRotationDegrees = Math.toDegrees(tl.number("replayIcon", Prop.Rotate, t, 0f).toDouble()).toFloat(),
        fill = Color.Transparent,
        stroke = tl.color("replayVector", Prop.Stroke, t, MiniColors.Icon),
    ),
    skip = restSkip,
    outgoing = rest.copy(
        x = g.capsuleX + tl.number("capsule", Prop.TranslateX, t, 0f),
        y = g.capsuleY + tl.number("capsule", Prop.TranslateY, t, 0f),
        width = g.replayWidth(tl.number("capsule", Prop.Width, t, 260f)),
        height = tl.number("capsule", Prop.Height, t, 64f),
        background = tl.color("capsule", Prop.Background, t, MiniColors.Capsule),
        alpha = tl.number("capsule", Prop.Opacity, t, 1f),
        artScale = tl.number("art", Prop.ScaleX, t, 1f),
        artAlpha = tl.number("art", Prop.Opacity, t, 1f),
        titleX = tl.number("title", Prop.TranslateX, t, 0f),
        titleAlpha = tl.number("title", Prop.Opacity, t, 1f),
        artistX = tl.number("artist", Prop.TranslateX, t, 0f),
        artistAlpha = tl.number("artist", Prop.Opacity, t, 1f),
        railX = tl.number("rail", Prop.TranslateX, t, 0f),
        railAlpha = tl.number("rail", Prop.Opacity, t, 1f),
        progressFigma = tl.number("progress", Prop.Width, t, 90f),
    ),
    // The same song, restarted: at the resting place, settling in from a little to the left.
    incoming = rest.copy(
        x = g.capsuleX + tl.number("incoming", Prop.TranslateX, t, 0f),
        alpha = tl.number("incoming", Prop.Opacity, t, 1f),
        scaleX = tl.number("incoming", Prop.ScaleX, t, 1f),
        scaleY = tl.number("incoming", Prop.ScaleY, t, 1f),
        artAlpha = tl.number("inArt", Prop.Opacity, t, 1f),
        titleY = tl.number("inTitle", Prop.TranslateY, t, 0f),
        titleAlpha = tl.number("inTitle", Prop.Opacity, t, 1f),
        artistY = tl.number("inArtist", Prop.TranslateY, t, 0f),
        artistAlpha = tl.number("inArtist", Prop.Opacity, t, 1f),
        railAlpha = tl.number("inRail", Prop.Opacity, t, 1f),
        progressFigma = 2f,
    ),
    pauseAlpha = tl.number("pause", Prop.Opacity, t, 1f),
)

/** The Figma timeline behind [mode] (Rest has none). */
internal fun timelineOf(mode: MiniMode): Timeline? = when (mode) {
    MiniMode.Rest -> null
    MiniMode.Skip -> MiniMotionData.SKIP
    MiniMode.SkipCompleted -> MiniMotionData.SKIP_COMPLETED
    MiniMode.Replay -> MiniMotionData.REPLAY
    MiniMode.ReplayCompleted -> MiniMotionData.REPLAY_COMPLETED
}

/**
 * The moment the song changes in [mode]: the outgoing capsule fully gone and
 * the incoming one not yet shown. The playback command and the metadata
 * switch both happen here, where nothing of either song is visible.
 */
internal fun handoffMs(mode: MiniMode): Float = when (mode) {
    MiniMode.Skip -> MiniMotionData.SKIP.goneFrom("capsule")
    MiniMode.Replay -> MiniMotionData.REPLAY.goneFrom("capsule")
    else -> 0f
}
