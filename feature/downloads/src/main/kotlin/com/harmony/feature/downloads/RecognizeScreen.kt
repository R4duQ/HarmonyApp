package com.harmony.feature.downloads

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.harmony.core.ui.component.EditorialPill
import com.harmony.core.ui.component.EditorialSectionLabel
import com.harmony.core.ui.component.GlassCard
import com.harmony.core.ui.component.LocalFloatingChromeHeight
import com.harmony.core.ui.component.bluePalette
import java.util.concurrent.TimeUnit

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
    RecognizeContent(state, bluePalette(), actions)
}

@Composable
fun RecognizeContent(state: RecognizeUiState, palette: EditorialPalette, actions: RecognizeActions) {
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.field)
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalFloatingChromeHeight.current),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = palette.ink)
            }
            Text("Recognize", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = palette.ink)
        }

        Spacer(Modifier.height(28.dp))
        ListenButton(state.phase, palette, actions.onListen)
        Spacer(Modifier.height(22.dp))
        StatusText(state.phase, palette)

        (state.phase as? ListenPhase.Found)?.let { found ->
            Spacer(Modifier.height(20.dp))
            ResultCard(found.song, palette, actions)
        }

        if (state.history.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            EditorialSectionLabel("Recently recognised", palette, Modifier.fillMaxWidth().padding(start = 22.dp, bottom = 8.dp))
            GlassCard(palette = palette, modifier = Modifier.padding(horizontal = 20.dp)) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    state.history.forEach { song -> HistoryRow(song, palette, actions) }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ListenButton(phase: ListenPhase, palette: EditorialPalette, onListen: () -> Unit) {
    val listening = phase as? ListenPhase.Listening
    val busy = listening != null || phase == ListenPhase.Identifying
    val transition = rememberInfiniteTransition(label = "listen")
    val ripple by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "listen-ripple",
    )
    // The button breathes with what the microphone hears.
    val level by animateFloatAsState(((listening?.level ?: 0f) * 6f).coerceIn(0f, 1f), tween(120), label = "listen-level")
    val progress = listening?.progress ?: if (phase == ListenPhase.Identifying) 1f else 0f

    Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
        if (busy) {
            Canvas(Modifier.fillMaxSize()) {
                val base = size.minDimension * 0.30f
                for (k in 0 until 3) {
                    val t = (ripple + k / 3f) % 1f
                    drawCircle(palette.accent.copy(alpha = (1f - t) * 0.28f), radius = base + (size.minDimension / 2 - base) * t)
                }
            }
        }
        Canvas(Modifier.size(196.dp)) {
            drawArc(palette.ink.copy(alpha = 0.12f), 0f, 360f, false, style = Stroke(5.dp.toPx()))
            if (progress > 0f) {
                drawArc(palette.accent, -90f, 360f * progress, false, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Box(
            Modifier
                .size(168.dp)
                .scale(1f + level * 0.08f)
                .clip(CircleShape)
                .background(palette.accent)
                .clickable(onClick = onListen)
                .semantics { contentDescription = if (busy) "Stop listening" else "Listen" }
                .testTag(RecognizeTags.LISTEN),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (listening != null) Icons.Rounded.Close else Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = palette.onAccent,
                modifier = Modifier.size(if (listening != null) 44.dp else 72.dp),
            )
        }
    }
}

@Composable
private fun StatusText(phase: ListenPhase, palette: EditorialPalette) {
    val (title, detail) = when (phase) {
        ListenPhase.Idle -> "Tap to recognize" to "Hold your phone near the music. Harmony listens for up to 12 seconds."
        is ListenPhase.Listening -> "Listening…" to "Keep the music playing. Tap to stop."
        ListenPhase.Identifying -> "Identifying…" to "Matching the sound with Shazam."
        is ListenPhase.Found -> "Found it" to ""
        ListenPhase.NoMatch -> "No match" to "Try again closer to the speaker, or when the song has vocals or a clear melody."
        is ListenPhase.Failed -> "Couldn't recognize" to phase.message
    }
    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = palette.ink, textAlign = TextAlign.Center)
    if (detail.isNotBlank()) {
        Text(
            detail, fontSize = 13.sp, lineHeight = 18.sp, color = palette.muted, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 36.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ResultCard(song: RecognizedSong, palette: EditorialPalette, actions: RecognizeActions) {
    GlassCard(palette = palette, modifier = Modifier.padding(horizontal = 20.dp).testTag(RecognizeTags.RESULT)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.coverUrl.ifBlank { null }, song.title, Modifier.size(92.dp), cornerRadius = 10.dp)
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(song.title, fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, color = palette.ink,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, fontSize = 14.sp, color = palette.ink, modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val meta = listOf(song.album, song.year).filter(String::isNotBlank).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(meta, fontSize = 12.sp, color = palette.muted, modifier = Modifier.padding(top = 2.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorialPill("Download", Icons.Rounded.Download, { actions.onDownload(song) }, palette)
                TextButton(onClick = { actions.onSearchLibrary(song) }) {
                    Icon(Icons.Rounded.Search, null, tint = palette.ink, modifier = Modifier.size(16.dp))
                    Text("My library", color = palette.ink, modifier = Modifier.padding(start = 4.dp))
                }
                if (song.songLink.isNotBlank()) {
                    TextButton(onClick = { actions.onOpenLink(song) }) {
                        Icon(Icons.Rounded.Link, null, tint = palette.ink, modifier = Modifier.size(16.dp))
                        Text("Links", color = palette.ink, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(song: RecognizedSong, palette: EditorialPalette, actions: RecognizeActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { actions.onShow(song) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.coverUrl.ifBlank { null }, null, Modifier.size(46.dp), cornerRadius = 8.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(song.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(song.artist, ago(song.recognizedAt)).filter(String::isNotBlank).joinToString(" · "),
                fontSize = 12.sp, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { actions.onDownload(song) }) {
            Icon(Icons.Rounded.Download, contentDescription = "Download ${song.title}", tint = palette.ink)
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
