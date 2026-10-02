package com.harmony.feature.downloads

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.ui.component.Artwork
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialSectionLabel
import com.harmony.core.ui.component.GlassCard
import com.harmony.core.ui.component.LocalFloatingChromeHeight
import com.harmony.core.ui.component.lavenderPalette
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What the Recognize page can ask of its host. */
class RecognizeActions(
    val onBack: () -> Unit = {},
    val onListen: () -> Unit = {},
    val onDownload: (RecognizedSong) -> Unit = {},
    val onSearchLibrary: (RecognizedSong) -> Unit = {},
    val onOpenLink: (RecognizedSong) -> Unit = {},
    val onShow: (RecognizedSong) -> Unit = {},
    val onForget: (RecognizedSong) -> Unit = {},
)

internal object RecognizeTags {
    const val LISTEN = "recognize_listen"
    const val RESULT = "recognize_result"
    const val HISTORY = "recognize_history"
}

/**
 * "What's this song?": listens through the microphone, names the song with
 * Shazam, and hands it to SpotiFLAC or the library in one tap.
 */
@Composable
fun RecognizeScreen(
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    onSearchLibrary: (String) -> Unit,
    viewModel: RecognizeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.listen() else viewModel.permissionDenied()
    }
    val actions = remember(viewModel) {
        RecognizeActions(
            onBack = onBack,
            onListen = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) viewModel.listen() else permission.launch(Manifest.permission.RECORD_AUDIO)
            },
            onDownload = { song -> viewModel.download(song); onOpenDownloads() },
            onSearchLibrary = { song -> onSearchLibrary(song.title) },
            onOpenLink = { song ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(song.songLink))) }
            },
            onShow = viewModel::show,
            onForget = viewModel::forget,
        )
    }
    RecognizeContent(state, lavenderPalette(), actions)
}

/**
 * The Recognize stage's own colours. The stage is always a deep, saturated
 * night sky, in light and dark mode alike: it is the one place in Harmony
 * meant to feel like a show, and white type on it reads the same either way.
 */
internal object Stage {
    val Night = Color(0xFF120A2E)
    val Deep = Color(0xFF2B1166)
    val Violet = Color(0xFF7C3AED)
    val Magenta = Color(0xFFEC4899)
    val Cyan = Color(0xFF22D3EE)
    val Blue = Color(0xFF3B82F6)
    val Amber = Color(0xFFFBBF24)
    val Ink = Color.White
    val Soft = Color.White.copy(alpha = 0.76f)
    val Faint = Color.White.copy(alpha = 0.14f)
    val Rim = Color.White.copy(alpha = 0.26f)

    /** The orb's ring of colour, closed so the sweep has no seam. */
    val Spectrum = listOf(Cyan, Blue, Violet, Magenta, Amber, Cyan)

    /** For "no match" and errors: the same sphere, warmer. */
    val Warm = listOf(Amber, Magenta, Violet, Magenta, Amber)
}

/** Where the five Shazam attempts fall on the 20-second listen (4, 8, 12, 16, 20 s). */
private val Checkpoints = listOf(0.2f, 0.4f, 0.6f, 0.8f, 1f)
private const val LISTEN_SECONDS = 20

@Composable
fun RecognizeContent(state: RecognizeUiState, palette: EditorialPalette, actions: RecognizeActions) {
    val scroll = rememberScrollState()
    val found = (state.phase as? ListenPhase.Found)?.song
    // A song picked from the history further down shows up on the stage.
    LaunchedEffect(found) { if (found != null) scroll.animateScrollTo(0) }
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.field)
            .verticalScroll(scroll)
            .padding(bottom = LocalFloatingChromeHeight.current),
    ) {
        RecognizeStage(state.phase, actions)
        if (state.history.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            HistoryCarousel(state.history, palette, actions)
        } else if (found == null) {
            Spacer(Modifier.height(24.dp))
            TipsCard(palette)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RecognizeStage(phase: ListenPhase, actions: RecognizeActions) {
    val found = (phase as? ListenPhase.Found)?.song
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
            .background(Brush.verticalGradient(listOf(Stage.Deep, Stage.Night))),
    ) {
        AuroraBackdrop(Modifier.matchParentSize(), excited = phase is ListenPhase.Listening || phase == ListenPhase.Identifying)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, end = 18.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = actions.onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Stage.Ink)
                }
                Text("Recognize", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Stage.Ink, modifier = Modifier.weight(1f))
                StageChip(Icons.Rounded.GraphicEq, "with Shazam")
            }
            AnimatedContent(
                targetState = found,
                transitionSpec = { (fadeIn(tween(320)) + scaleIn(tween(320), initialScale = 0.94f)) togetherWith fadeOut(tween(160)) },
                contentKey = { song -> song?.let { it.title + "|" + it.artist + "|" + it.recognizedAt } },
                label = "recognize-stage",
            ) { song ->
                if (song == null) ListenStage(phase, actions.onListen) else ResultStage(song, actions)
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

/** Slow blobs of light drifting over the night: violet, magenta, cyan and blue. */
@Composable
private fun AuroraBackdrop(modifier: Modifier, excited: Boolean) {
    val drift = rememberInfiniteTransition(label = "aurora")
    val t by drift.animateFloat(
        0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "aurora-t",
    )
    val glow by animateFloatAsState(if (excited) 1f else 0.75f, tween(900), label = "aurora-glow")
    Canvas(modifier) {
        val a = 2 * PI.toFloat() * t
        val w = size.width
        val h = size.height
        fun blob(color: Color, cx: Float, cy: Float, r: Float, alpha: Float) = drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = alpha * glow), Color.Transparent), Offset(cx, cy), r),
            radius = r, center = Offset(cx, cy),
        )
        blob(Stage.Violet, w * (0.18f + 0.08f * sin(a)), h * (0.18f + 0.05f * cos(a)), w * 0.9f, 0.85f)
        blob(Stage.Magenta, w * (0.95f + 0.06f * cos(a)), h * (0.34f + 0.07f * sin(a)), w * 0.72f, 0.60f)
        blob(Stage.Cyan, w * (0.06f + 0.07f * cos(a + 1f)), h * (0.80f + 0.05f * sin(a + 1f)), w * 0.70f, 0.40f)
        blob(Stage.Blue, w * (0.75f + 0.05f * sin(a + 2f)), h * (0.98f + 0.03f * cos(a)), w * 0.62f, 0.42f)
    }
}

@Composable
private fun ListenStage(phase: ListenPhase, onListen: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(6.dp))
        ListenOrb(phase, onListen)
        StatusText(phase)
        Spacer(Modifier.height(16.dp))
        when (phase) {
            is ListenPhase.Listening -> AttemptTrack(phase.progress)
            ListenPhase.Identifying -> AttemptTrack(1f)
            ListenPhase.NoMatch, is ListenPhase.Failed ->
                StagePill(Icons.Rounded.Refresh, "Try again", primary = true, onClick = onListen)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StageChip(Icons.Rounded.Timer, "Up to 20 s")
                StageChip(Icons.Rounded.Groups, "Live & loud")
                StageChip(Icons.Rounded.Lock, "No account")
            }
        }
    }
}

/**
 * The listen button: a glossy sphere of colour inside a ring of bars that
 * move with what the microphone hears, and a progress ring that marks the
 * five moments Harmony asks Shazam.
 */
@Composable
private fun ListenOrb(phase: ListenPhase, onListen: () -> Unit) {
    val listening = phase as? ListenPhase.Listening
    val identifying = phase == ListenPhase.Identifying
    val busy = listening != null || identifying
    val trouble = phase is ListenPhase.Failed || phase == ListenPhase.NoMatch

    val motion = rememberInfiniteTransition(label = "orb")
    val spin by motion.animateFloat(
        0f, 360f, infiniteRepeatable(tween(if (identifying) 1_400 else 9_000, easing = LinearEasing)), label = "orb-spin",
    )
    val wave by motion.animateFloat(
        0f, 2 * PI.toFloat(), infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "orb-wave",
    )
    val ripple by motion.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2_200, easing = LinearEasing)), label = "orb-ripple",
    )
    val level by animateFloatAsState((listening?.level ?: 0f).coerceIn(0f, 1f), tween(110), label = "orb-level")
    val progress by animateFloatAsState(
        listening?.progress ?: if (identifying) 1f else 0f, tween(if (busy) 120 else 400), label = "orb-progress",
    )
    val press by animateFloatAsState(if (busy) 0.95f + level * 0.07f else 1f, spring(dampingRatio = 0.55f), label = "orb-press")
    val colors = if (trouble) Stage.Warm else Stage.Spectrum

    Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val orb = 84.dp.toPx()

            // Halos: ripples while busy, one soft glow at rest.
            if (busy) {
                for (k in 0 until 3) {
                    val p = (ripple + k / 3f) % 1f
                    drawCircle(
                        Stage.Magenta.copy(alpha = (1 - p) * 0.22f),
                        radius = orb + (size.minDimension / 2 - orb) * p, center = center,
                    )
                }
            }
            drawCircle(
                Brush.radialGradient(listOf(Stage.Violet.copy(alpha = 0.6f), Color.Transparent), center, orb * 1.9f),
                radius = orb * 1.9f, center = center,
            )

            // Bars around the orb: the live level while listening, a slow breath otherwise.
            val bars = 72
            val inner = orb + 24.dp.toPx()
            for (i in 0 until bars) {
                val angle = 2 * PI.toFloat() * i / bars - PI.toFloat() / 2
                val shimmer = 0.5f + 0.5f * sin(wave * 2 + i * 0.9f) * cos(wave + i * 0.37f)
                val energy = if (busy) 0.12f + level * (0.35f + 0.65f * shimmer) else 0.10f + 0.12f * shimmer
                val length = 4.dp.toPx() + energy * 30.dp.toPx()
                val dir = Offset(cos(angle), sin(angle))
                val colour = colors[(i * (colors.size - 1)) / bars]
                drawLine(
                    colour.copy(alpha = if (busy) 0.95f else 0.6f),
                    center + dir * inner, center + dir * (inner + length),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                )
            }

            // Progress ring with a dot at each Shazam attempt.
            val ringRadius = orb + 12.dp.toPx()
            drawCircle(Stage.Faint, radius = ringRadius, center = center, style = Stroke(5.dp.toPx()))
            if (progress > 0f) {
                rotate(-90f, center) {
                    drawArc(
                        Brush.sweepGradient(colors, center), 0f, 360f * progress, false,
                        topLeft = center - Offset(ringRadius, ringRadius),
                        size = Size(ringRadius * 2, ringRadius * 2),
                        style = Stroke(5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
            if (busy) {
                for (c in Checkpoints) {
                    val angle = 2 * PI.toFloat() * c - PI.toFloat() / 2
                    val at = center + Offset(cos(angle), sin(angle)) * ringRadius
                    drawCircle(if (progress >= c) Color.White else Stage.Rim, radius = 4.dp.toPx(), center = at)
                }
            }
        }

        // The sphere itself.
        Box(
            Modifier
                .size(168.dp)
                .scale(press)
                .shadow(28.dp, CircleShape, ambientColor = Stage.Magenta, spotColor = Stage.Violet)
                .clip(CircleShape)
                .drawBehind {
                    rotate(spin) { drawCircle(Brush.sweepGradient(colors)) }
                    // Gloss: a soft highlight top-left and depth bottom-right.
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.42f), Color.Transparent),
                            Offset(size.width * 0.30f, size.height * 0.24f), size.width * 0.42f,
                        ),
                    )
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Color.Transparent, Stage.Night.copy(alpha = 0.42f)),
                            Offset(size.width * 0.4f, size.height * 0.35f), size.width * 0.82f,
                        ),
                    )
                }
                .border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.45f)), CircleShape)
                .clickable(onClick = onListen)
                .semantics { contentDescription = if (busy) "Stop listening" else "Listen" }
                .testTag(RecognizeTags.LISTEN),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (listening != null) Icons.Rounded.Stop else Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(if (listening != null) 54.dp else 78.dp),
            )
        }
    }
}

@Composable
private fun StatusText(phase: ListenPhase) {
    val (title, detail) = when (phase) {
        ListenPhase.Idle -> "Tap to recognize" to "Point your phone at the music. Harmony listens for up to 20 seconds."
        is ListenPhase.Listening -> "Listening…" to "Keep the music playing. Tap to stop."
        ListenPhase.Identifying -> "Identifying…" to "Matching the sound with Shazam."
        is ListenPhase.Found -> "Found it" to ""
        ListenPhase.NoMatch -> "No match" to "Try again closer to the speaker, or during a part with vocals or a clear melody."
        is ListenPhase.Failed -> "Couldn't recognize" to phase.message
    }
    Text(title, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Stage.Ink, textAlign = TextAlign.Center)
    if (detail.isNotBlank()) {
        Text(
            detail, fontSize = 14.sp, lineHeight = 20.sp, color = Stage.Soft, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 380.dp).padding(horizontal = 32.dp, vertical = 6.dp),
        )
    }
}

/** Five segments, one per Shazam attempt, and the seconds heard so far. */
@Composable
private fun AttemptTrack(progress: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            var start = 0f
            Checkpoints.forEachIndexed { i, end ->
                val fill = ((progress - start) / (end - start)).coerceIn(0f, 1f)
                Box(Modifier.width(40.dp).height(6.dp).clip(CircleShape).background(Stage.Faint)) {
                    if (fill > 0f) {
                        Box(
                            Modifier.fillMaxWidth(fill).height(6.dp).clip(CircleShape)
                                .background(Brush.horizontalGradient(listOf(Stage.Spectrum[i], Stage.Spectrum[i + 1]))),
                        )
                    }
                }
                start = end
            }
        }
        val seconds = (progress * LISTEN_SECONDS).toInt().coerceIn(0, LISTEN_SECONDS)
        Text(
            "0:%02d / 0:%02d".format(seconds, LISTEN_SECONDS),
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = Stage.Soft,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ResultStage(song: RecognizedSong, actions: RecognizeActions) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag(RecognizeTags.RESULT),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(290.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Brush.radialGradient(listOf(Stage.Magenta.copy(alpha = 0.6f), Color.Transparent)))
            }
            Artwork(
                song.coverUrl.ifBlank { null }, song.title,
                Modifier
                    .size(228.dp)
                    .shadow(30.dp, RoundedCornerShape(26.dp), ambientColor = Stage.Magenta, spotColor = Stage.Violet)
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)), RoundedCornerShape(26.dp)),
                cornerRadius = 26.dp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Stage.Amber, modifier = Modifier.size(16.dp))
            Text(
                "FOUND IT", fontSize = 12.sp, letterSpacing = 2.5.sp, fontWeight = FontWeight.Bold, color = Stage.Amber,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Text(
            song.title, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, color = Stage.Ink,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            song.artist, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Stage.Soft,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
        )
        val facts = listOf(song.album, song.year).filter(String::isNotBlank).filter { it != song.title }
        if (facts.isNotEmpty()) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                facts.forEach { StageChip(null, it) }
            }
        }
        Spacer(Modifier.height(22.dp))
        StagePill(Icons.Rounded.Download, "Download", primary = true, wide = true, onClick = { actions.onDownload(song) })
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StagePill(Icons.Rounded.Search, "My library", onClick = { actions.onSearchLibrary(song) })
            if (song.songLink.isNotBlank()) {
                StagePill(Icons.AutoMirrored.Rounded.OpenInNew, "Shazam page", onClick = { actions.onOpenLink(song) })
            }
        }
        Spacer(Modifier.height(6.dp))
        StagePill(Icons.Rounded.GraphicEq, "Listen again", quiet = true, onClick = actions.onListen)
    }
}

/** A small frosted label on the stage. */
@Composable
private fun StageChip(icon: ImageVector?, text: String) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Stage.Faint)
            .border(BorderStroke(1.dp, Stage.Rim), CircleShape)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = Stage.Ink, modifier = Modifier.size(14.dp))
        Text(
            text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Stage.Ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = if (icon != null) 5.dp else 0.dp).widthIn(max = 170.dp),
        )
    }
}

/** A button on the stage: solid white for the main action, frosted for the rest, bare for the quietest. */
@Composable
private fun StagePill(
    icon: ImageVector,
    text: String,
    primary: Boolean = false,
    quiet: Boolean = false,
    wide: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = CircleShape
    val tint = if (primary) Stage.Deep else Stage.Ink
    Row(
        Modifier
            .then(if (wide) Modifier.widthIn(max = 340.dp).fillMaxWidth() else Modifier)
            .then(if (primary) Modifier.shadow(14.dp, shape, ambientColor = Stage.Magenta, spotColor = Stage.Magenta) else Modifier)
            .clip(shape)
            .then(
                when {
                    primary -> Modifier.background(Color.White)
                    quiet -> Modifier
                    else -> Modifier.background(Stage.Faint).border(BorderStroke(1.dp, Stage.Rim), shape)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = if (primary) 15.dp else 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(if (primary) 20.dp else 17.dp))
        Text(
            text, fontSize = if (primary) 16.sp else 14.sp, fontWeight = FontWeight.Bold, color = tint,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun HistoryCarousel(history: List<RecognizedSong>, palette: EditorialPalette, actions: RecognizeActions) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        EditorialSectionLabel("Recently recognised", palette, Modifier.weight(1f))
        Text("${history.size}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.muted)
    }
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 12.dp).testTag(RecognizeTags.HISTORY),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(history, key = { it.title + "|" + it.artist + "|" + it.recognizedAt }) { song ->
            HistoryCard(song, palette, actions)
        }
    }
}

@Composable
private fun HistoryCard(song: RecognizedSong, palette: EditorialPalette, actions: RecognizeActions) {
    Column(Modifier.width(150.dp).clip(RoundedCornerShape(20.dp)).clickable { actions.onShow(song) }) {
        Box {
            Artwork(
                song.coverUrl.ifBlank { null }, song.title,
                Modifier.size(150.dp).border(BorderStroke(1.dp, palette.line), RoundedCornerShape(20.dp)),
                cornerRadius = 20.dp,
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(36.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable { actions.onDownload(song) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Download, contentDescription = "Download ${song.title}", tint = Stage.Violet, modifier = Modifier.size(20.dp))
            }
            val heard = ago(song.recognizedAt)
            if (heard.isNotBlank()) {
                Text(
                    heard, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(Stage.Night.copy(alpha = 0.62f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Text(
            song.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = palette.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp, start = 2.dp),
        )
        Text(
            song.artist, fontSize = 13.sp, color = palette.muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun TipsCard(palette: EditorialPalette) {
    EditorialSectionLabel("For the best match", palette, Modifier.padding(start = 22.dp, bottom = 10.dp))
    GlassCard(palette = palette, modifier = Modifier.padding(horizontal = 20.dp)) {
        Column(Modifier.padding(vertical = 6.dp)) {
            TipRow(Icons.AutoMirrored.Rounded.VolumeUp, Stage.Magenta, "Point the phone at the speaker",
                "The microphone at the bottom hears best facing the sound.", palette)
            TipRow(Icons.Rounded.Groups, Stage.Violet, "Crowds and quiet rooms are fine",
                "Harmony keeps listening and tries again with the latest 12 seconds.", palette)
            TipRow(Icons.Rounded.MusicNote, Stage.Blue, "Only a fingerprint leaves your phone",
                "Never the recording itself.", palette)
        }
    }
}

@Composable
private fun TipRow(icon: ImageVector, tint: Color, title: String, detail: String, palette: EditorialPalette) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.ink)
            Text(detail, fontSize = 12.sp, lineHeight = 16.sp, color = palette.muted)
        }
    }
}

/** "just now", "5 min ago", "3 h ago", "2 d ago". */
internal fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0L) return ""
    val minutes = TimeUnit.MILLISECONDS.toMinutes((now - at).coerceAtLeast(0))
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        else -> "${minutes / (60 * 24)} d ago"
    }
}
