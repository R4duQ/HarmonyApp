package com.harmony.feature.player

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
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
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.model.OutputForm
import com.harmony.feature.player.neu.NeuAudioDetails
import com.harmony.feature.player.neu.NeuFavoriteButton
import com.harmony.feature.player.neu.NeuFormatPill
import com.harmony.feature.player.neu.NeuGlyph
import com.harmony.feature.player.neu.NeuHeader
import com.harmony.feature.player.neu.NeuIcon
import com.harmony.feature.player.neu.NeuMixButton
import com.harmony.feature.player.neu.NeuPlayButton
import com.harmony.feature.player.neu.NeuPressButton
import com.harmony.feature.player.neu.NeuRecord
import com.harmony.feature.player.neu.NeuTitle
import com.harmony.feature.player.neu.NeuToggleButton
import com.harmony.feature.player.neu.NeuTool
import com.harmony.feature.player.neu.NeuTools
import com.harmony.feature.player.neu.NeuWaveform
import com.harmony.feature.player.neu.asPlayerPalette
import com.harmony.feature.player.neu.audioFields
import com.harmony.feature.player.neu.interStyle
import com.harmony.feature.player.neu.neuPalette
import com.harmony.core.model.PlayerState
import com.harmony.core.model.RepeatMode
import com.harmony.core.model.ShuffleMode
import com.harmony.core.model.Song
import com.harmony.core.ui.component.formatDuration
import com.harmony.domain.playback.AudioLevel
import com.harmony.domain.playback.AudioLevels
import com.harmony.domain.shuffle.model.SmartShuffleStyle
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

/** Widest the portrait player's column grows, in dp (tablets held upright). */
private const val PORTRAIT_MAX_CONTENT_DP = 600

/** Widest the landscape player's two halves grow together, in dp (tablets on their side). */
private const val LANDSCAPE_MAX_CONTENT_DP = 1040

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
    /** Open with the Play on sheet up: Add › From your computer pairs from there. */
    openPlayOn: Boolean = false,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.playerState.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isCurrentFavorite.collectAsStateWithLifecycle()
    val journeyProgress by viewModel.journeyProgress.collectAsStateWithLifecycle()
    val smartStyle by viewModel.smartShuffleStyle.collectAsStateWithLifecycle()
    val outputForms by viewModel.outputForms.collectAsStateWithLifecycle()
    val outputChoice = OutputChoice(
        form = com.harmony.core.model.OutputForms.effective(state.audioOutput, outputForms),
        chosen = state.audioOutput.name?.let { outputForms.containsKey(com.harmony.core.model.OutputForms.key(it)) } == true,
        pick = state.audioOutput.name?.takeIf { OutputIcons.canPickForm(state.audioOutput.type) && it.isNotBlank() }
            ?.let { name -> { form -> viewModel.setOutputForm(name, form) } },
    )

    var showQueue by remember { mutableStateOf(false) }
    var showShuffleSheet by remember { mutableStateOf(false) }
    var showSteer by remember { mutableStateOf(false) }
    val energy by viewModel.energySliderValue.collectAsStateWithLifecycle()
    val mood by viewModel.moodFilter.collectAsStateWithLifecycle()
    val brain by viewModel.brainTelemetry.collectAsStateWithLifecycle()
    var showLyrics by remember { mutableStateOf(false) }
    var showPlayOn by rememberSaveable { mutableStateOf(openPlayOn) }
    val connectState by viewModel.connectState.collectAsStateWithLifecycle()
    val mix by viewModel.mix.collectAsStateWithLifecycle()
    val onlineMix by viewModel.onlineMix.collectAsStateWithLifecycle()
    // A handoff that ended unsure asks where the music should be (Universal Queue, stage 10).
    val unsureHandoff = connectState.unsure
    LaunchedEffect(unsureHandoff) { if (unsureHandoff != null) showPlayOn = true }

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
    val palette = neuPalette().asPlayerPalette()
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // A tablet held upright gets the portrait layout, which spans the whole
    // width on a phone. Across 800dp+ that blows the artwork up and spreads
    // the controls apart, so the column keeps to a phone-like width and the
    // page's tint fills the margins.
    val sidePadding = when {
        landscape && configuration.screenWidthDp > LANDSCAPE_MAX_CONTENT_DP + 48 ->
            ((configuration.screenWidthDp - LANDSCAPE_MAX_CONTENT_DP) / 2).dp
        landscape -> 24.dp
        configuration.screenWidthDp > PORTRAIT_MAX_CONTENT_DP + 40 ->
            ((configuration.screenWidthDp - PORTRAIT_MAX_CONTENT_DP) / 2).dp
        else -> 24.dp
    }

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

    // The frames draw the record at rest whether or not it plays.
    val playScale = 1f
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

    // Large system text: the page scrolls and the record keeps a fixed size
    // (accessibility A4), so the transport is never pushed off screen.
    val largeText = LocalDensity.current.fontScale > 1.3f && !landscape
    val steer = SteerCallbacks(
        steerable = mix != null || state.shuffleMode == ShuffleMode.SMART,
        energy = energy,
        mood = mood,
        onEnergy = viewModel::onEnergySliderChange,
        onMood = viewModel::onMoodFilterSelected,
        onJourney = { viewModel.onStartJourneyToMood(it, 5) },
        onTune = { showShuffleSheet = true },
        onStartMix = viewModel::onStartMix,
    )
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
            // Applied AFTER background so the tint still reaches the screen
            // edge and only the controls move up. Needed again now that
            // NavHost fills the full height and no longer reserves the
            // bottom inset for its content.
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
            .then(if (largeText) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = sidePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NowPlayingBody(
            state = state,
            song = song,
            landscape = landscape,
            palette = palette,
            isFavorite = isFavorite,
            audioInfo = audioInfo,
            output = outputChoice,
            journeyProgress = journeyProgress,
            smartStyle = smartStyle,
            progress = dragFraction ?: animatedProgress,
            dragFraction = dragFraction,
            waveform = waveform,
            playScale = playScale,
            mixOn = mix != null,
            mixStatus = mixStatusFor(mix, onlineMix, upcomingEmpty = state.queueIndex >= state.queue.lastIndex),
            reason = pickReason(brain.nowPlaying?.takeIf { it.trace.trackId == song?.id }),
            steer = steer,
            scrollable = largeText,
            cb = NowPlayingCallbacks(
                onBack = onBack,
                onQueue = { showQueue = true },
                onMixSheet = { showShuffleSheet = true },
                onLyrics = { showLyrics = true },
                onEqualizer = onOpenEqualizer,
                onPlayOn = { showPlayOn = true },
                onToggleFavorite = viewModel::onToggleFavorite,
                onNext = viewModel::onNext,
                onPrevious = viewModel::onPrevious,
                onPlayPause = viewModel::onPlayPause,
                onRepeat = viewModel::cycleRepeatMode,
                onShuffle = viewModel::cycleShuffleMode,
                onScrub = { dragFraction = it },
                onScrubFinished = onSeekCommit,
                onScrubCancel = { dragFraction = null },
                onSteer = { showSteer = true },
            ),
        )
    }

    if (showSteer) SteerSheet(steer, onDismiss = { showSteer = false })

    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }) {
            QueueSheet(viewModel)
        }
    }
    if (showPlayOn) {
        ModalBottomSheet(
            onDismissRequest = { showPlayOn = false },
            // Fully open: a code or an address gets typed in here, above the keyboard.
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            PlayOnSheet(
                connect = connectState,
                phoneOutput = state.audioOutput,
                output = outputChoice,
                actions = viewModel.playOnActions,
                diagnostics = viewModel.diagnosticsSaved.collectAsStateWithLifecycle().value,
            )
        }
    }
    if (showShuffleSheet) {
        ModalBottomSheet(onDismissRequest = { showShuffleSheet = false }) {
            SmartShuffleSheet(viewModel)
        }
    }
    val brainHud by viewModel.brainHudVisible.collectAsStateWithLifecycle()
    if (brainHud) {
        val brain by viewModel.brainTelemetry.collectAsStateWithLifecycle()
        val selfTestRunning by viewModel.brainSelfTestRunning.collectAsStateWithLifecycle()
        ShuffleBrainDebugOverlay(
            telemetry = brain,
            selfTestRunning = selfTestRunning,
            onRunSelfTest = viewModel::runBrainSelfTest,
            onClose = { viewModel.setBrainHudVisible(false) },
        )
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

/** What Now Playing's layout does when tapped; the screen wires these to its ViewModel and sheets. */
internal class NowPlayingCallbacks(
    val onBack: () -> Unit = {},
    val onQueue: () -> Unit = {},
    val onMixSheet: () -> Unit = {},
    val onLyrics: () -> Unit = {},
    val onEqualizer: (() -> Unit)? = null,
    val onPlayOn: () -> Unit = {},
    val onToggleFavorite: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onRepeat: () -> Unit = {},
    val onShuffle: () -> Unit = {},
    val onScrub: (Float) -> Unit = {},
    val onScrubFinished: (Float) -> Unit = {},
    val onScrubCancel: () -> Unit = {},
    /** Opens the steering sheet: Calmer, Livelier, moods, Tune. */
    val onSteer: () -> Unit = {},
)

/**
 * Everything Now Playing draws, portrait and landscape: the top bar, the
 * record, the title, the format card, the output and the transport. It reads
 * plain state and reports taps through [cb], so it renders without a player
 * (the design screenshots draw it with fixed data).
 */
@Composable
internal fun ColumnScope.NowPlayingBody(
    state: PlayerState,
    song: Song?,
    landscape: Boolean,
    @Suppress("UNUSED_PARAMETER") palette: PlayerPalette,
    isFavorite: Boolean,
    audioInfo: MiniPlayerAudioInfo?,
    output: OutputChoice,
    journeyProgress: Float?,
    smartStyle: SmartShuffleStyle,
    progress: Float,
    dragFraction: Float?,
    waveform: FloatArray,
    @Suppress("UNUSED_PARAMETER") playScale: Float,
    cb: NowPlayingCallbacks,
    /** A mix is running: Steer shows it as on. */
    mixOn: Boolean = false,
    /** What the mix is doing with the next song, from the player's real state; null when nothing to say. */
    mixStatus: MixStatusUi? = null,
    /** Why the mix picked this song, in words; null when it did not. */
    reason: String? = null,
    /** Steering the mix; null hides the mix button. */
    steer: SteerCallbacks? = null,
    /** Large system text: the page scrolls and the record takes a fixed height. */
    scrollable: Boolean = false,
) {
    val neu = neuPalette()
    val pp = neu.asPlayerPalette()
    var menuOpen by remember { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val tag = formatTagFor(audioInfo, song?.bitDepth)
    val fields = audioInfo?.let { audioFields(it.format, it.isLossless, it.sampleRateHz, song?.bitDepth, it.bitrateKbps) }.orEmpty()
    val gestureScope = rememberCoroutineScope()
    val still = com.harmony.core.ui.theme.reducedMotion
    // The prototype opens the details as the pill rises: 90 ms down, 70 ms held.
    val openDetails: () -> Unit = {
        gestureScope.launch {
            if (!still) kotlinx.coroutines.delay(160)
            showDetails = true
        }
    }

    // Short phones (a 640dp screen, or a large font) tighten the rhythm so the
    // transport and the tools stay on screen; the record gives way first.
    val config = LocalConfiguration.current
    val short = config.screenHeightDp < 720 || LocalDensity.current.fontScale > 1.2f
    val deviceIcon = OutputIcons.forOutput(state.audioOutput.type, output.form)
    val onComputer = state.audioOutput.type == com.harmony.core.model.AudioOutputType.COMPUTER
    val upcoming = (state.queue.size - state.queueIndex - 1).coerceAtLeast(0)
    val tools = buildList {
        add(NeuTool(if (upcoming > 0) "Queue · $upcoming" else "Queue", NeuGlyph.Queue, null, false, cb.onQueue, "Open the queue"))
        add(NeuTool("Steer", NeuGlyph.Spark, null, mixOn, cb.onSteer, "Steer the mix"))
        val phone = state.audioOutput.type == com.harmony.core.model.AudioOutputType.SPEAKER
        add(NeuTool(state.audioOutput.label, if (phone) NeuGlyph.Phone else null, if (phone) null else deviceIcon, onComputer, cb.onPlayOn, "Choose where to play"))
        cb.onEqualizer?.let { add(NeuTool("Sound", NeuGlyph.Sound, null, false, it, "Open the equalizer")) }
    }

    val header = @Composable {
        NeuHeader(
            albumName = song?.album,
            palette = neu,
            onBack = cb.onBack,
            onMore = { menuOpen = true },
            // In landscape the title sits under the record with no room
            // beside it, so favourite moves up here rather than being dropped.
            trailing = {
                if (landscape) {
                    NeuFavoriteButton(isFavorite, cb.onToggleFavorite, neu)
                    Spacer(Modifier.width(12.dp))
                }
            },
            menu = {
                PlayerMenu(
                    open = menuOpen,
                    onOpenChange = { menuOpen = it },
                    hasLyrics = song?.embeddedLyrics != null,
                    onQueue = cb.onQueue,
                    onShuffleSheet = cb.onMixSheet,
                    onLyrics = cb.onLyrics,
                    onEqualizer = cb.onEqualizer,
                )
            },
        )
    }
    val formatRow = @Composable { modifier: Modifier, centered: Boolean ->
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val cardWidth = maxWidth
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
            ) {
                if (tag != null) Box {
                    NeuFormatPill(tag.first, tag.second, neu, onClick = openDetails)
                    NeuAudioDetails(showDetails, fields, neu, width = cardWidth, anchorHeight = 30.dp, onDismiss = { showDetails = false })
                }
                if (reason != null) {
                    Text(
                        reason,
                        style = interStyle(11f, FontWeight.Medium, neu.muted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = if (tag != null) 12.dp else 0.dp).weight(1f, fill = !centered),
                    )
                }
            }
        }
    }
    val transport = @Composable {
        NeuTransport(state, neu, smartStyle, journeyProgress, cb)
    }
    val mixButton = @Composable {
        if (steer != null) {
            val steering = steer.steerable
            NeuMixButton(
                text = if (steering) "Tune the mix" else "Start a mix from this song",
                palette = neu,
                onClick = if (steering) steer.onTune else steer.onStartMix,
            )
        }
    }

    if (landscape) {
        header()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: the record, as large as the half allows, and the song under it.
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
                    gestureScope = gestureScope,
                    onNext = cb.onNext,
                    onPrevious = cb.onPrevious,
                    onPlayPause = cb.onPlayPause,
                    onQueue = cb.onQueue,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                )
                NeuTitle(
                    title = song?.title ?: "Nothing playing",
                    artist = song?.artist.orEmpty(),
                    palette = neu,
                    centered = true,
                    titleSize = if (short) 19f else 21f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 4.dp),
                )
            }

            Spacer(Modifier.width(20.dp))

            // Right: everything you read and operate, kept to a phone-like width.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .wrapContentWidth()
                    .widthIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                formatRow(Modifier.padding(top = 8.dp), true)
                NeuSeekBlock(progress, waveform, state, dragFraction, neu, cb, channelHeight = 52)
                transport()
                if (mixStatus != null) MixStatusLine(mixStatus, pp, centered = true, modifier = Modifier.padding(vertical = 6.dp))
                NeuTools(tools, neu, Modifier.padding(top = 6.dp, bottom = 8.dp))
            }
        }
    } else {
        header()
        // The record is the centre of the screen and takes whatever height the
        // controls leave; on a short phone it shrinks rather than pushing the
        // transport off screen.
        ArtworkPager(
            song = song,
            isPlaying = state.isPlaying,
            gestureScope = gestureScope,
            onNext = cb.onNext,
            onPrevious = cb.onPrevious,
            onPlayPause = cb.onPlayPause,
            onQueue = cb.onQueue,
            modifier = (if (scrollable) Modifier.height(230.dp) else Modifier.weight(1f).heightIn(min = 110.dp))
                .fillMaxWidth(),
        )
        // Track details: title, artist, favourite, and the format pill (150 dp).
        Box(Modifier.fillMaxWidth().height(if (short) 124.dp else 150.dp)) {
            NeuTitle(
                title = song?.title ?: "Nothing playing",
                artist = song?.artist.orEmpty(),
                palette = neu,
                titleSize = if (short) 22f else 26f,
                modifier = Modifier.padding(top = 13.dp, end = 54.dp),
            )
            NeuFavoriteButton(isFavorite, cb.onToggleFavorite, neu, Modifier.align(Alignment.TopEnd).offset(y = 17.dp))
            formatRow(Modifier.offset(y = if (short) 74.dp else 80.dp), false)
        }
        NeuSeekBlock(progress, waveform, state, dragFraction, neu, cb, channelHeight = if (short) 52 else 60)
        transport()
        Spacer(Modifier.height(if (short) 4.dp else 12.dp))
        if (mixStatus != null) MixStatusLine(mixStatus, pp, centered = true, modifier = Modifier.padding(bottom = 6.dp))
        // "Start a mix from this song" (82 dp); short screens reach it through Steer.
        if (!short && steer != null) {
            Box(Modifier.fillMaxWidth().height(if (mixStatus != null) 68.dp else 82.dp), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.padding(top = if (mixStatus != null) 0.dp else 14.dp)) { mixButton() }
            }
        }
        NeuTools(tools, neu, Modifier.padding(bottom = 4.dp))
    }
}

/** The overflow menu: queue, Smart Shuffle, lyrics when there are some, the equalizer. */
@Composable
private fun PlayerMenu(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    hasLyrics: Boolean,
    onQueue: () -> Unit,
    onShuffleSheet: () -> Unit,
    onLyrics: () -> Unit,
    onEqualizer: (() -> Unit)?,
) {
    DropdownMenu(expanded = open, onDismissRequest = { onOpenChange(false) }) {
        DropdownMenuItem(
            text = { Text("Queue") },
            leadingIcon = { Icon(Icons.Rounded.QueueMusic, contentDescription = null) },
            onClick = { onOpenChange(false); onQueue() },
        )
        DropdownMenuItem(
            text = { Text("Smart Shuffle") },
            leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
            onClick = { onOpenChange(false); onShuffleSheet() },
        )
        if (hasLyrics) {
            DropdownMenuItem(
                text = { Text("Lyrics") },
                leadingIcon = { Icon(Icons.Rounded.Lyrics, contentDescription = null) },
                onClick = { onOpenChange(false); onLyrics() },
            )
        }
        if (onEqualizer != null) {
            DropdownMenuItem(
                text = { Text("Equalizer") },
                leadingIcon = { Icon(Icons.Rounded.Equalizer, contentDescription = null) },
                onClick = { onOpenChange(false); onEqualizer() },
            )
        }
    }
}

/**
 * The waveform channel and the times under it (94 dp in the frame). The
 * music's level, while playing, lifts the bars around the playhead.
 */
@Composable
private fun NeuSeekBlock(
    progress: Float,
    waveform: FloatArray,
    state: PlayerState,
    dragFraction: Float?,
    neu: com.harmony.feature.player.neu.NeuPalette,
    cb: NowPlayingCallbacks,
    channelHeight: Int,
) {
    // Only react while actually playing: paused on a loud peak would
    // otherwise leave the bars frozen mid-swell, which reads as a stuck UI.
    val live = if (state.isPlaying) liveAudioLevel() else AudioLevel()
    val level by animateFloatAsState(
        targetValue = live.level,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 320f),
        label = "waveform-level",
    )
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(4.dp))
        NeuWaveform(
            progress = progress,
            waveform = waveform,
            palette = neu,
            onScrub = cb.onScrub,
            onScrubFinished = cb.onScrubFinished,
            onScrubCancel = cb.onScrubCancel,
            height = channelHeight.dp,
            level = level,
        )
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp)) {
            val shownPosition = dragFraction?.let { (it * state.durationMs).toLong() } ?: state.positionMs
            val time = interStyle(11f, FontWeight.Medium, neu.muted)
            Text(formatDuration(shownPosition), style = time, modifier = Modifier.padding(start = 1.dp))
            Spacer(Modifier.weight(1f))
            Text(formatDuration(state.durationMs), style = time, modifier = Modifier.padding(end = 11.dp))
        }
    }
}

/**
 * Repeat · previous · play/pause in its surround · next · shuffle (114 dp in
 * the frame). Read in the order you use them (accessibility A12): previous,
 * play, next, then the two modes, though repeat and shuffle bracket the row.
 */
@Composable
private fun NeuTransport(
    state: PlayerState,
    neu: com.harmony.feature.player.neu.NeuPalette,
    smartStyle: SmartShuffleStyle,
    journeyProgress: Float?,
    cb: NowPlayingCallbacks,
) {
    val haptics = LocalHapticFeedback.current
    Box(Modifier.fillMaxWidth().height(102.dp).semantics { isTraversalGroup = true }) {
        NeuToggleButton(
            on = state.repeatMode != RepeatMode.OFF,
            glyph = NeuGlyph.Repeat,
            contentDescription = "Repeat mode: ${state.repeatMode}",
            palette = neu,
            onClick = cb.onRepeat,
            badge = if (state.repeatMode == RepeatMode.ONE) "1" else null,
            modifier = Modifier.align(Alignment.CenterStart).semantics { traversalIndex = 4f },
        )
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            NeuPressButton(
                onClick = cb.onPrevious, contentDescription = "Previous",
                width = 54.dp, height = 54.dp, radius = 18.dp, raised = neu.raised, palette = neu,
                pressedShiftX = (-2.5).dp, pressedAlpha = 0.9f,
                modifier = Modifier.semantics { traversalIndex = 0f },
            ) { NeuIcon(NeuGlyph.Previous, neu.ink) }
            Spacer(Modifier.width(9.dp))
            NeuPlayButton(
                playing = state.isPlaying,
                onClick = cb.onPlayPause,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                palette = neu,
                modifier = Modifier.semantics { traversalIndex = 1f },
            )
            Spacer(Modifier.width(9.dp))
            NeuPressButton(
                onClick = cb.onNext, contentDescription = "Next",
                width = 54.dp, height = 54.dp, radius = 18.dp, raised = neu.raised, palette = neu,
                pressedShiftX = 2.5.dp, pressedAlpha = 0.9f,
                modifier = Modifier.semantics { traversalIndex = 2f },
            ) { NeuIcon(NeuGlyph.Next, neu.ink) }
        }
        val mode = state.shuffleMode
        val label = shuffleBadgeLabel(mode, smartStyle, journeyProgress)
        val description = when (mode) {
            ShuffleMode.OFF -> "Shuffle off. Tap to turn on Smart Shuffle"
            ShuffleMode.SMART -> "Smart Shuffle on, ${label.lowercase()} style. Tap to turn off"
            ShuffleMode.RANDOM -> "Random shuffle on. Tap to turn off"
            ShuffleMode.JOURNEY -> "$label. Tap to turn shuffle off"
        } + ", long press for shuffle options."
        NeuToggleButton(
            on = mode != ShuffleMode.OFF,
            glyph = NeuGlyph.Shuffle,
            contentDescription = description,
            palette = neu,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                cb.onShuffle()
            },
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                cb.onMixSheet()
            },
            modifier = Modifier.align(Alignment.CenterEnd).semantics { traversalIndex = 3f },
        )
    }
}

/** What a running mix is doing with the next song, in words, from the player's real state. */
internal class MixStatusUi(val text: String, val working: Boolean)

/**
 * The mix's line under the transport: "Preparing “Numb” · 45% · playback
 * starts by itself when it's ready", "Your library plays on · online songs
 * download on Wi-Fi only". Red while something is being prepared for the
 * music now; muted when the mix has fallen back.
 */
@Composable
internal fun MixStatusLine(status: MixStatusUi, palette: PlayerPalette, centered: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (status.working) Icons.Rounded.CloudDownload else Icons.Rounded.Info,
            contentDescription = null,
            tint = if (status.working) palette.live else palette.muted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            status.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (status.working) palette.ink else palette.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
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
    gestureScope: CoroutineScope,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayPause: () -> Unit = {},
    onQueue: () -> Unit = {},
) {
    val offsetX = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    // The record is a control (accessibility A9): tap to play or pause, and
    // every swipe it answers is also an action a screen reader can pick.
    val playLabel = androidx.compose.ui.res.stringResource(if (isPlaying) R.string.player_pause else R.string.player_play)
    val nextLabel = androidx.compose.ui.res.stringResource(R.string.player_next)
    val previousLabel = androidx.compose.ui.res.stringResource(R.string.player_previous)
    val queueLabel = androidx.compose.ui.res.stringResource(R.string.np_open_queue)
    val cover = song?.album?.let { "Cover of $it" } ?: "Album cover"

    Box(
        modifier = modifier
            .onSizeChanged { width = it.width }
            .semantics {
                contentDescription = cover
                role = Role.Button
                onClick(playLabel) { onPlayPause(); true }
                customActions = listOf(
                    androidx.compose.ui.semantics.CustomAccessibilityAction(nextLabel) { onNext(); true },
                    androidx.compose.ui.semantics.CustomAccessibilityAction(previousLabel) { onPrevious(); true },
                    androidx.compose.ui.semantics.CustomAccessibilityAction(queueLabel) { onQueue(); true },
                )
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { onPlayPause() }) }
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
            },
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
            NeuRecord(
                artworkUri = song?.artworkUri,
                albumName = song?.album,
                palette = neuPalette(),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The format card: the file as it is, set like the fields of a catalogue
 * card under a red header rule (red because this is the file playing now).
 * Format, lossless or not, resolution and bitrate, each a small label over
 * its value in tabular figures. It consumes the same resolver as the mini
 * player, so the two can never report different formats.
 */
@Composable
internal fun NowPlayingAudioQuality(
    info: MiniPlayerAudioInfo?,
    bitDepth: Int?,
    palette: PlayerPalette,
    @Suppress("UNUSED_PARAMETER") centered: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = info,
        transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(140))) },
        label = "now-playing-audio-quality",
        modifier = modifier.fillMaxWidth(),
    ) { resolved ->
        if (resolved == null) {
            Spacer(Modifier.height(0.dp))
        } else {
            val shape = RoundedCornerShape(10.dp)
            val fontScale = LocalDensity.current.fontScale
            val rate = resolved.sampleRateHz?.takeIf { it > 0 }
            val khz = rate?.let { if (it % 1000 == 0) "${it / 1000}" else "%.1f".format(it / 1000.0) }
            val fields = buildList {
                add("Format" to (resolved.format ?: "Audio"))
                add("Quality" to if (resolved.isLossless) "Lossless" else "Lossy")
                when {
                    bitDepth != null && khz != null -> add("Bit/kHz" to "$bitDepth/$khz")
                    khz != null -> add("Rate" to "$khz kHz")
                }
                resolved.bitrateKbps?.takeIf { it > 0 }?.let { add("Kbps" to "$it") }
            }
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .border(1.dp, palette.controlEdge, shape)
                    .semantics(mergeDescendants = true) {
                        contentDescription = fields.joinToString(", ") { (k, v) -> "$k $v" }
                    },
            ) {
              // Too narrow for a labelled field each (a small phone, a large font,
              // half a landscape screen): the same facts on one line.
              val compact = maxWidth / fields.size < 80.dp * fontScale
              Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(palette.live))
                if (compact) {
                    Text(
                        fields.joinToString("  ·  ") { (k, v) -> if (k == "Kbps") "$v kbps" else v },
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.ink,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                } else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    fields.forEachIndexed { i, (label, value) ->
                        if (i > 0) Box(Modifier.fillMaxHeight().width(1.dp).background(palette.controlEdge))
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = palette.muted, maxLines = 1)
                            Text(
                                value,
                                style = MaterialTheme.typography.labelLarge,
                                color = palette.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
              }
            }
        }
    }
}

/** The device kind to draw, whether the listener picked it, and how to pick one (null when it can't apply). */
internal class OutputChoice(val form: OutputForm?, val chosen: Boolean, val pick: ((OutputForm?) -> Unit)?)

/**
 * The music's level and brightness, read from the EQ engine's analyser once
 * per frame into an array allocated once (see AudioLevels), with a fast-rise,
 * slow-fall meter on top.
 */
@Composable
private fun liveAudioLevel(): AudioLevel {
    val bars = remember { FloatArray(AudioLevels.SPECTRUM_BARS) }
    var shown by remember { mutableStateOf(AudioLevel()) }
    DisposableEffect(Unit) {
        AudioLevels.startWatching()
        onDispose { AudioLevels.stopWatching() }
    }
    LaunchedEffect(Unit) {
        var level = 0f
        var brightness = 0f
        while (true) {
            withFrameNanos {
                val now = AudioLevels.levelOf(bars, AudioLevels.poll(bars, null))
                level += (now.level - level) * if (now.level > level) 0.5f else 0.12f
                brightness += (now.brightness - brightness) * 0.2f
                if (kotlin.math.abs(level - shown.level) > 0.004f || kotlin.math.abs(brightness - shown.brightness) > 0.004f) {
                    shown = AudioLevel(level, brightness)
                }
            }
        }
    }
    return shown
}

/** Short, human name for what shuffle is doing — shown under the button. */
private fun shuffleBadgeLabel(
    mode: ShuffleMode,
    smartStyle: SmartShuffleStyle,
    journeyProgress: Float?,
): String = when (mode) {
    ShuffleMode.OFF -> "Off"
    ShuffleMode.RANDOM -> "Random"
    ShuffleMode.JOURNEY -> journeyProgress
        ?.let { "Journey ${(it * 100).toInt().coerceIn(0, 100)}%" }
        ?: "Journey"
    // The sparkle glyph already says "Smart", so a non-default style is
    // named on its own. Kept short on purpose: the pill hangs off the
    // row's last button and must stay inside the screen gutter.
    ShuffleMode.SMART -> when (smartStyle) {
        SmartShuffleStyle.BALANCED -> "Smart"
        SmartShuffleStyle.FAMILIAR -> "Familiar"
        SmartShuffleStyle.DISCOVER -> "Discover"
        SmartShuffleStyle.FLOW -> "Flow"
    }
}
