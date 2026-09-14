package com.harmony.feature.player

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.model.AudioOutputType
import com.harmony.core.model.PlayerState
import com.harmony.core.model.RepeatMode
import com.harmony.core.model.ShuffleMode
import com.harmony.core.model.Song
import com.harmony.core.ui.component.formatDuration
import com.harmony.domain.playback.AudioLevel
import com.harmony.domain.playback.AudioLevels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The full-screen player.
 *
 * VISUAL: a single soft-lavender field, one hero (the sleeve with the record
 * drawn out behind it), and everything else kept quiet. See [PlayerLook] for
 * why this screen owns its palette instead of following dynamic colors.
 *
 * ORIENTATION: portrait stacks hero over text over controls. Landscape has
 * roughly a third of the vertical room, so the same stack would shrink the
 * record to a coin and crush the controls — instead the screen splits into
 * two halves, each vertically centred in itself: the record with the track
 * name directly beneath it on the left, the transport on the right. The
 * hero and its label stay together, which is the part that matters.
 *
 * Every gesture works in both orientations: swipe the artwork left/right to
 * change track, swipe down anywhere to dismiss, swipe up for the queue.
 *
 * MOTION, unchanged: artwork cross-fades between tracks and "breathes" while
 * paused, the record spins while playing and tucks in when stopped,
 * title/artist slide-fade on track change, play/pause morphs, mode tints
 * animate, favourite pops, the seek bar interpolates between the 500ms
 * position ticks and commits the seek once on release.
 */
/** Fraction of the artwork width a swipe must cross to commit a skip. */
private const val SWIPE_SKIP_FRACTION = 0.22f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit = {},
    /**
     * Equalizer's primary entry point now that it isn't a bottom destination:
     * the place you are most likely to want it is while listening. Null hides
     * the menu item rather than showing a dead one.
     */
    onOpenEqualizer: (() -> Unit)? = null,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.playerState.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isCurrentFavorite.collectAsStateWithLifecycle()
    val journeyProgress by viewModel.journeyProgress.collectAsStateWithLifecycle()

    var showQueue by remember { mutableStateOf(false) }
    var showShuffleSheet by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val song = state.currentSong
    val context = LocalContext.current
    val audioInfo by produceState<MiniPlayerAudioInfo?>(
        initialValue = song?.let { MiniPlayerAudioInfo.initial(it) },
        key1 = song?.id,
        key2 = song?.uri,
    ) {
        // Show the metadata already stored on Song immediately, then refine
        // it off the main thread from the actual audio track/container.
        value = song?.let { MiniPlayerAudioInfo.initial(it) }
        value = song?.let { resolveMiniPlayerAudioInfo(context, it) }
    }
    val palette = playerPalette()
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // ---- YT-Music-style vertical gestures on the whole player ----
    // SWIPE DOWN (or the back button): the page follows your finger and, past
    // a fifth of the screen, slides away back to where you came from.
    // SWIPE UP: opens the queue sheet. The page itself doesn't move on an
    // upward drag — the queue rises over it, matching the sheet's motion.
    val gestureScope = rememberCoroutineScope()
    val dragOffsetY = remember { Animatable(0f) }
    var containerHeight by remember { mutableIntStateOf(1) }
    val upThresholdPx = with(LocalDensity.current) { 80.dp.toPx() }

    // Smoothly interpolate between the 500ms position ticks so the playhead
    // glides instead of stepping. While the user drags, their finger owns the
    // value; the seek commits once on release.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val targetProgress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else 0f
    // Two motions share this value and they want opposite curves, which a
    // single animateFloatAsState cannot express — hence the Animatable.
    //
    // Between position ticks the playhead should advance at a CONSTANT rate:
    // the old tween's default FastOutSlowIn eased in and out inside every
    // 500ms window, so the bar sped up and slowed down twice a second. At
    // this scale that reads as a stutter, not as easing. Linear is the only
    // curve that matches the thing being represented — time passing evenly.
    //
    // A jump is the opposite case: a seek, or a loop back to the start.
    // Crawling linearly across most of the bar looks broken, and snapping is
    // the harshness being complained about, so those settle on a spring
    // instead.
    //
    // A TRACK CHANGE is a third case again, handled before either, because
    // running it through the jump path sweeps the playhead backwards across
    // the whole bar — the new song's zero animated from the old song's
    // position, which reads as the bar rewinding rather than as one track
    // handing over to the next.
    val progressAnim = remember { Animatable(0f) }
    var lastSongId by remember { mutableStateOf(song?.id) }
    LaunchedEffect(song?.id, targetProgress) {
        if (song?.id != lastSongId) {
            lastSongId = song?.id
            // Only carry the outgoing bar to the end when it was ALREADY
            // near the end, i.e. the track finished on its own. A manual
            // skip from the middle should not pretend the rest played —
            // that would show progress the listener never heard.
            if (progressAnim.value > 0.5f) {
                progressAnim.animateTo(
                    1f,
                    tween(durationMillis = 220, easing = LinearEasing),
                )
            }
            // Straight to zero rather than animated: the fill has just run
            // off the right-hand end, so there is nothing on screen to
            // travel back from, and the waveform is morphing to the new
            // track's shape over the same moment.
            progressAnim.snapTo(0f)
            return@LaunchedEffect
        }
        val delta = abs(targetProgress - progressAnim.value)
        if (delta > 0.05f) {
            progressAnim.animateTo(
                targetProgress,
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        } else {
            // Slightly longer than the 500ms tick interval so the animation
            // is still running when the next position arrives. Matching it
            // exactly leaves a gap at the end of each window where the bar
            // sits still, which is the stutter this is meant to remove.
            progressAnim.animateTo(
                targetProgress,
                tween(durationMillis = 560, easing = LinearEasing),
            )
        }
    }
    val animatedProgress = progressAnim.value

    val playScale by animateFloatAsState(
        targetValue = if (state.isPlaying) 1f else 0.97f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "artwork-breathe",
    )
    val favoriteScale by animateFloatAsState(
        targetValue = if (isFavorite) 1.15f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "favorite-pop",
    )
    val favoriteTint by animateColorAsState(
        targetValue = if (isFavorite) palette.accent else palette.muted,
        label = "favorite-tint",
    )
    val shuffleTint by animateColorAsState(
        targetValue = if (state.shuffleMode == ShuffleMode.OFF) palette.muted else palette.accent,
        label = "shuffle-tint",
    )
    val repeatTint by animateColorAsState(
        targetValue = if (state.repeatMode == RepeatMode.OFF) palette.muted else palette.accent,
        label = "repeat-tint",
    )

    val waveform = rememberMorphingWaveform(song?.id)

    val onSeekCommit: (Float) -> Unit = { fraction ->
        if (state.durationMs > 0) {
            viewModel.onSeek((fraction * state.durationMs).toLong())
        }
        // Move the animated value to where the finger left it BEFORE
        // clearing dragFraction. Clearing first hands the bar back to
        // animatedProgress while that still holds the PRE-seek position, so
        // for the few frames until the player reports its new one the
        // playhead snaps backwards and then jumps forward again — the jolt
        // on release. Snapping first means the handover is invisible.
        gestureScope.launch {
            progressAnim.snapTo(fraction)
            dragFraction = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerHeight = it.height }
            .graphicsLayer { translationY = dragOffsetY.value.coerceAtLeast(0f) }
            .background(palette.background)
            // Applied AFTER background, deliberately: this only pushes the
            // column's own content up above the system nav bar, it doesn't
            // shrink what area the background paints — otherwise the strip
            // under the system bar would show through to whatever sits
            // behind this screen instead of this screen's own tint.
            //
            // This screen never sat behind the floating mini player/nav
            // bar — it's a full-screen destination with its own bottom
            // transport controls. It used to get its bottom clearance for
            // free from NavHost's shared padding, but that padding was
            // deliberately dropped so Library and friends could scroll
            // behind the floating chrome; this screen was never meant to
            // lose its own clearance in the process, so it claims its own
            // inset here instead.
            .navigationBarsPadding()
            .pointerInput(Unit) {
                var totalDrag = 0f
                detectVerticalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onVerticalDrag = { change, amount ->
                        totalDrag += amount
                        if (totalDrag > 0f) {
                            change.consume()
                            gestureScope.launch { dragOffsetY.snapTo(totalDrag) }
                        }
                    },
                    onDragEnd = {
                        when {
                            totalDrag > containerHeight * 0.2f -> onBack()
                            totalDrag < -upThresholdPx -> {
                                showQueue = true
                                gestureScope.launch { dragOffsetY.animateTo(0f) }
                            }
                            else -> gestureScope.launch {
                                dragOffsetY.animateTo(
                                    0f,
                                    spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                )
                            }
                        }
                    },
                    onDragCancel = { gestureScope.launch { dragOffsetY.animateTo(0f) } },
                )
            }
            .padding(horizontal = if (landscape) 24.dp else 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PlayerTopBar(
            albumName = song?.album,
            palette = palette,
            onBack = onBack,
            menuOpen = showMenu,
            onMenuOpenChange = { showMenu = it },
            hasLyrics = song?.embeddedLyrics != null,
            onQueue = { showQueue = true },
            onShuffleSheet = { showShuffleSheet = true },
            onLyrics = { showLyrics = true },
            onEqualizer = onOpenEqualizer,
            // In landscape the title block sits under the record with no room
            // beside it, so favourite moves up here rather than being dropped.
            favorite = if (!landscape) null else FavoriteSpec(
                isFavorite = isFavorite,
                tint = favoriteTint,
                scale = favoriteScale,
                onToggle = viewModel::onToggleFavorite,
            ),
        )

        if (landscape) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left half: the hero and its label, as one centred group.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    ArtworkPager(
                        song = song,
                        isPlaying = state.isPlaying,
                        playScale = playScale,
                        gestureScope = gestureScope,
                        onNext = viewModel::onNext,
                        onPrevious = viewModel::onPrevious,
                        modifier = Modifier
                            .fillMaxHeight(0.62f)
                            .aspectRatio(1f / 0.72f, matchHeightConstraintsFirst = true),
                    )
                    TitleBlock(
                        song = song,
                        palette = palette,
                        centered = true,
                        titleSize = 21,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                    )
                    NowPlayingAudioQuality(
                        info = audioInfo,
                        palette = palette,
                        centered = true,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Spacer(Modifier.width(20.dp))

                // Right half: everything you operate.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    MetaRow(state, journeyProgress, palette, centered = true)
                    Spacer(Modifier.height(14.dp))
                    SeekBlock(
                        progress = dragFraction ?: animatedProgress,
                        waveform = waveform,
                        state = state,
                        dragFraction = dragFraction,
                        palette = palette,
                        onScrub = { dragFraction = it },
                        onScrubFinished = onSeekCommit,
                        onScrubCancel = { dragFraction = null },
                        waveformHeight = 44,
                    )
                    Spacer(Modifier.height(16.dp))
                    Transport(
                        state = state,
                        palette = palette,
                        repeatTint = repeatTint,
                        shuffleTint = shuffleTint,
                        onRepeat = viewModel::cycleRepeatMode,
                        onPrevious = viewModel::onPrevious,
                        onPlayPause = viewModel::onPlayPause,
                        onNext = viewModel::onNext,
                        onShuffle = viewModel::cycleShuffleMode,
                        playSize = 64,
                        sideSize = 48,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        } else {
            Spacer(Modifier.weight(1f))
            ArtworkPager(
                song = song,
                isPlaying = state.isPlaying,
                playScale = playScale,
                gestureScope = gestureScope,
                onNext = viewModel::onNext,
                onPrevious = viewModel::onPrevious,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f / 0.72f),
            )
            Spacer(Modifier.weight(1.1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TitleBlock(
                    song = song,
                    palette = palette,
                    centered = false,
                    titleSize = 25,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                FavoriteButton(
                    spec = FavoriteSpec(isFavorite, favoriteTint, favoriteScale, viewModel::onToggleFavorite),
                    palette = palette,
                )
            }
            NowPlayingAudioQuality(
                info = audioInfo,
                palette = palette,
                centered = false,
                modifier = Modifier.padding(top = 8.dp),
            )
            MetaRow(
                state, journeyProgress, palette,
                centered = false,
                modifier = Modifier.padding(top = 7.dp),
            )
            Spacer(Modifier.height(18.dp))
            SeekBlock(
                progress = dragFraction ?: animatedProgress,
                waveform = waveform,
                state = state,
                dragFraction = dragFraction,
                palette = palette,
                onScrub = { dragFraction = it },
                onScrubFinished = onSeekCommit,
                onScrubCancel = { dragFraction = null },
                waveformHeight = 52,
            )
            Spacer(Modifier.height(22.dp))
            Transport(
                state = state,
                palette = palette,
                repeatTint = repeatTint,
                shuffleTint = shuffleTint,
                onRepeat = viewModel::cycleRepeatMode,
                onPrevious = viewModel::onPrevious,
                onPlayPause = viewModel::onPlayPause,
                onNext = viewModel::onNext,
                onShuffle = viewModel::cycleShuffleMode,
                playSize = 72,
                sideSize = 54,
            )
            Spacer(Modifier.height(28.dp))
        }
    }

    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }) {
            QueueSheet(viewModel)
        }
    }
    if (showShuffleSheet) {
        ModalBottomSheet(onDismissRequest = { showShuffleSheet = false }) {
            SmartShuffleSheet(viewModel)
        }
    }
    if (showLyrics && song?.embeddedLyrics != null) {
        ModalBottomSheet(onDismissRequest = { showLyrics = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(song.embeddedLyrics!!, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pieces, shared by both orientations
// ---------------------------------------------------------------------------

/** Bundles the favourite button's state so it can move between slots. */
private data class FavoriteSpec(
    val isFavorite: Boolean,
    val tint: androidx.compose.ui.graphics.Color,
    val scale: Float,
    val onToggle: () -> Unit,
)

@Composable
private fun FavoriteButton(
    spec: FavoriteSpec,
    palette: PlayerPalette,
    size: Int = 44,
) {
    PlayerCircleButton(
        onClick = spec.onToggle,
        contentDescription = if (spec.isFavorite) "Remove from favorites" else "Add to favorites",
        size = size.dp,
        contentColor = spec.tint,
    ) {
        Icon(
            if (spec.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = null,
            tint = spec.tint,
            modifier = Modifier
                .size(20.dp)
                .scale(spec.scale),
        )
    }
}

@Composable
private fun PlayerTopBar(
    albumName: String?,
    palette: PlayerPalette,
    onBack: () -> Unit,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    hasLyrics: Boolean,
    onQueue: () -> Unit,
    onShuffleSheet: () -> Unit,
    onLyrics: () -> Unit,
    onEqualizer: (() -> Unit)?,
    favorite: FavoriteSpec?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerCircleButton(onClick = onBack, contentDescription = "Back — collapse player") {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "NOW PLAYING FROM",
                fontSize = 9.sp,
                lineHeight = 12.sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.Medium,
                color = palette.muted,
            )
            Text(
                albumName ?: "Nothing playing",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (favorite != null) {
            FavoriteButton(spec = favorite, palette = palette, size = 42)
            Spacer(Modifier.width(8.dp))
        }

        Box {
            PlayerCircleButton(
                onClick = { onMenuOpenChange(true) },
                contentDescription = "More options",
            ) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                DropdownMenuItem(
                    text = { Text("Queue") },
                    leadingIcon = { Icon(Icons.Rounded.QueueMusic, contentDescription = null) },
                    onClick = { onMenuOpenChange(false); onQueue() },
                )
                DropdownMenuItem(
                    text = { Text("Smart Shuffle") },
                    leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                    onClick = { onMenuOpenChange(false); onShuffleSheet() },
                )
                if (hasLyrics) {
                    DropdownMenuItem(
                        text = { Text("Lyrics") },
                        leadingIcon = { Icon(Icons.Rounded.Lyrics, contentDescription = null) },
                        onClick = { onMenuOpenChange(false); onLyrics() },
                    )
                }
                if (onEqualizer != null) {
                    DropdownMenuItem(
                        text = { Text("Equalizer") },
                        leadingIcon = { Icon(Icons.Rounded.Equalizer, contentDescription = null) },
                        onClick = { onMenuOpenChange(false); onEqualizer() },
                    )
                }
            }
        }
    }
}

/**
 * The record, with swipe-to-skip scoped to it.
 *
 * Deliberately not page-wide: the seek bar is a horizontal drag too, and a
 * page-wide horizontal gesture would fight it. Confining the swipe to the
 * artwork keeps every other gesture working untouched, and matches where the
 * thumb naturally sits.
 */
@Composable
private fun ArtworkPager(
    song: Song?,
    isPlaying: Boolean,
    playScale: Float,
    gestureScope: CoroutineScope,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offsetX = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }

    Box(
        modifier = modifier
            .onSizeChanged { width = it.width }
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        total += amount
                        gestureScope.launch { offsetX.snapTo(total) }
                    },
                    onDragEnd = {
                        val threshold = width * SWIPE_SKIP_FRACTION
                        gestureScope.launch {
                            when {
                                // Swipe LEFT -> next: the card leaves in the
                                // direction of travel, THEN the track changes,
                                // so the motion reads as cause and effect
                                // rather than a jump cut.
                                total <= -threshold -> {
                                    offsetX.animateTo(-width.toFloat(), tween(160))
                                    onNext()
                                    offsetX.snapTo(0f)
                                }
                                total >= threshold -> {
                                    offsetX.animateTo(width.toFloat(), tween(160))
                                    onPrevious()
                                    offsetX.snapTo(0f)
                                }
                                // Not far enough: spring back, so a hesitant
                                // swipe is obviously cancellable.
                                else -> offsetX.animateTo(
                                    0f,
                                    spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                )
                            }
                        }
                    },
                    onDragCancel = { gestureScope.launch { offsetX.animateTo(0f) } },
                )
            }
            .graphicsLayer {
                translationX = offsetX.value
                // Fade with distance so the card feels like it's being
                // carried away rather than sliding under a mask.
                alpha = 1f - (kotlin.math.abs(offsetX.value) /
                    (width * 1.6f)).coerceIn(0f, 0.65f)
            }
            .scale(playScale),
    ) {
        AnimatedContent(
            targetState = song?.id,
            transitionSpec = {
                (fadeIn(tween(350)) + scaleIn(initialScale = 0.92f, animationSpec = tween(350)))
                    .togetherWith(fadeOut(tween(250)) + scaleOut(targetScale = 1.04f, animationSpec = tween(250)))
            },
            label = "artwork-change",
            modifier = Modifier.fillMaxSize(),
        ) { _ ->
            VinylArtwork(
                artworkUri = song?.artworkUri,
                albumName = song?.album,
                isPlaying = isPlaying,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun TitleBlock(
    song: Song?,
    palette: PlayerPalette,
    centered: Boolean,
    titleSize: Int,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = song?.id,
        transitionSpec = {
            (slideInHorizontally(tween(300)) { it / 6 } + fadeIn(tween(300)))
                .togetherWith(slideOutHorizontally(tween(200)) { -it / 6 } + fadeOut(tween(200)))
        },
        label = "title-change",
        modifier = modifier,
    ) { _ ->
        Column(
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                song?.title ?: "Nothing playing",
                fontSize = titleSize.sp,
                lineHeight = (titleSize + 5).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                color = palette.ink,
                maxLines = if (centered) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            )
            Text(
                song?.artist ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Live codec information for the full player. It deliberately consumes the
 * same resolver as [MiniPlayer], so changing tracks cannot leave the two
 * player surfaces reporting different formats or lossless status.
 */
@Composable
private fun NowPlayingAudioQuality(
    info: MiniPlayerAudioInfo?,
    palette: PlayerPalette,
    centered: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = info,
        transitionSpec = {
            fadeIn(tween(220)).togetherWith(fadeOut(tween(140)))
        },
        label = "now-playing-audio-quality",
        modifier = modifier.fillMaxWidth(),
    ) { resolved ->
        if (resolved == null) {
            Spacer(Modifier.height(0.dp))
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
            ) {
                if (resolved.isLossless) {
                    Row(
                        modifier = Modifier
                            .background(
                                palette.accent.copy(alpha = 0.13f),
                                RoundedCornerShape(50),
                            )
                            .border(
                                1.dp,
                                palette.accent.copy(alpha = 0.36f),
                                RoundedCornerShape(50),
                            )
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.GraphicEq,
                            contentDescription = "Lossless audio",
                            tint = palette.accent,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "LOSSLESS",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.45.sp,
                            color = palette.accent,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    resolved.summary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Quiet status line: where the audio is going, whether a journey is running,
 * and what's next once the crossfade has started — one caption row, so none
 * of it competes with the title.
 */
@Composable
private fun MetaRow(
    state: PlayerState,
    journeyProgress: Float?,
    palette: PlayerPalette,
    centered: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
    ) {
        Icon(
            imageVector = when (state.audioOutput.type) {
                AudioOutputType.BLUETOOTH -> Icons.Rounded.Bluetooth
                AudioOutputType.WIRED -> Icons.Rounded.Headphones
                AudioOutputType.USB -> Icons.Rounded.Usb
                AudioOutputType.HDMI -> Icons.Rounded.Tv
                else -> Icons.Rounded.Speaker
            },
            contentDescription = "Audio output",
            tint = palette.muted,
            modifier = Modifier.size(13.dp),
        )
        Text(
            state.audioOutput.label,
            fontSize = 11.sp,
            color = palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 5.dp),
        )
        AnimatedVisibility(visible = journeyProgress != null) {
            Text(
                " · Journey ${((journeyProgress ?: 0f) * 100).toInt()}%",
                fontSize = 11.sp,
                color = palette.accent,
                maxLines = 1,
            )
        }
        AnimatedVisibility(
            visible = state.upcomingSong != null,
            enter = expandVertically() + fadeIn() + slideInVertically { it / 2 },
            exit = shrinkVertically() + fadeOut(),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            state.upcomingSong?.let { next ->
                Text(
                    " · Next: ${next.title}",
                    fontSize = 11.sp,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SeekBlock(
    progress: Float,
    waveform: FloatArray,
    state: PlayerState,
    dragFraction: Float?,
    palette: PlayerPalette,
    onScrub: (Float) -> Unit,
    onScrubFinished: (Float) -> Unit,
    onScrubCancel: () -> Unit,
    waveformHeight: Int,
) {
    // Collected here rather than inside WaveformSeekBar so the bar stays a
    // pure drawing component that can be previewed and reused without a
    // running audio chain behind it.
    val audio by AudioLevels.current.collectAsStateWithLifecycle()
    // Only react while actually playing: paused on a loud peak would
    // otherwise leave the bars frozen mid-swell, which reads as a stuck UI.
    val live = if (state.isPlaying) audio else AudioLevel()
    // The meter already smooths on the audio side; this second, slower pass
    // is against the display, absorbing the gap between the ~40ms
    // measurement window and the frame rate so motion is continuous rather
    // than stepped.
    val animatedLevel by animateFloatAsState(
        targetValue = live.level,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 320f),
        label = "waveform-level",
    )
    val animatedBrightness by animateFloatAsState(
        targetValue = live.brightness,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 140f),
        label = "waveform-brightness",
    )

    WaveformSeekBar(
        progress = progress,
        waveform = waveform,
        onScrub = onScrub,
        onScrubFinished = onScrubFinished,
        onScrubCancel = onScrubCancel,
        playedColor = palette.accent,
        trackColor = palette.track,
        modifier = Modifier
            .fillMaxWidth()
            .height(waveformHeight.dp),
        level = animatedLevel,
        brightness = animatedBrightness,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val shownPosition = dragFraction?.let { (it * state.durationMs).toLong() }
            ?: state.positionMs
        Text(formatDuration(shownPosition), fontSize = 11.sp, color = palette.muted)
        Text(formatDuration(state.durationMs), fontSize = 11.sp, color = palette.muted)
    }
}

/**
 * Repeat and shuffle bracket the row: they're persistent modes, so they sit
 * outside the three controls that act on the moment.
 */
@Composable
private fun Transport(
    state: PlayerState,
    palette: PlayerPalette,
    repeatTint: androidx.compose.ui.graphics.Color,
    shuffleTint: androidx.compose.ui.graphics.Color,
    onRepeat: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    playSize: Int,
    sideSize: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PlayerCircleButton(
            onClick = onRepeat,
            contentDescription = "Repeat mode: ${state.repeatMode}",
            contentColor = repeatTint,
        ) {
            AnimatedContent(
                targetState = state.repeatMode == RepeatMode.ONE,
                transitionSpec = {
                    (scaleIn(initialScale = 0.7f) + fadeIn())
                        .togetherWith(scaleOut(targetScale = 0.7f) + fadeOut())
                },
                label = "repeat-morph",
            ) { isOne ->
                Icon(
                    if (isOne) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = null,
                    tint = repeatTint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        PlayerCircleButton(
            onClick = onPrevious,
            contentDescription = "Previous",
            size = sideSize.dp,
        ) {
            Icon(
                Icons.Rounded.SkipPrevious,
                contentDescription = null,
                modifier = Modifier.size((sideSize / 2 + 2).dp),
            )
        }

        PlayerCircleButton(
            onClick = onPlayPause,
            contentDescription = if (state.isPlaying) "Pause" else "Play",
            size = playSize.dp,
            containerColor = palette.primaryControl,
            contentColor = palette.onPrimaryControl,
            borderColor = palette.controlEdge,
        ) {
            AnimatedContent(
                targetState = state.isPlaying,
                transitionSpec = {
                    (scaleIn(initialScale = 0.6f, animationSpec = tween(160)) + fadeIn(tween(160)))
                        .togetherWith(scaleOut(targetScale = 0.6f, animationSpec = tween(120)) + fadeOut(tween(120)))
                },
                label = "play-pause-morph",
            ) { playing ->
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = palette.onPrimaryControl,
                    modifier = Modifier.size((playSize * 0.42f).dp),
                )
            }
        }

        PlayerCircleButton(
            onClick = onNext,
            contentDescription = "Next",
            size = sideSize.dp,
        ) {
            Icon(
                Icons.Rounded.SkipNext,
                contentDescription = null,
                modifier = Modifier.size((sideSize / 2 + 2).dp),
            )
        }

        PlayerCircleButton(
            onClick = onShuffle,
            contentDescription = if (state.shuffleMode == ShuffleMode.OFF) {
                "Turn on Smart Shuffle"
            } else {
                "Turn off shuffle (currently ${state.shuffleMode})"
            },
            contentColor = shuffleTint,
        ) {
            Icon(
                Icons.Rounded.Shuffle,
                contentDescription = null,
                tint = shuffleTint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
