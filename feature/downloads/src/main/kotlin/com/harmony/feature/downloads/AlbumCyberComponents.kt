package com.harmony.feature.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.harmony.core.ui.component.NeonWaveformArt
import com.harmony.core.ui.component.neonGlow
import com.harmony.core.ui.theme.CyberGlassColors

/*
 * Albums in Downloads, in Cyber-Glass: the search card, the album's
 * tracklist with each track's live state, and the collapsible album card in
 * the download queue. Colors are the theme's Cyber-Glass ([CyberGlassColors]):
 * in dark, obsidian #0B0F17, smoked-glass cards #111827 at 0.90, a 1 dp
 * cyan-to-violet rim (#00E5FF → #8B5CF6), cool-grey #94A3B8 secondary text;
 * in light the same design on pearl.
 */

/** Tags for tests and screenshots. */
object AlbumCyberTags {
    const val SEARCH_CARD = "album_search_card"
    const val DOWNLOAD_ALBUM = "album_download_all"
    const val BATCH_CARD = "album_batch_card"
    const val BATCH_TOGGLE = "album_batch_toggle"
    const val MASTER_BUTTON = "album_master_button"
    fun track(id: String) = "album_track_$id"
}

/** The smoked-glass card with the iridescent 1 dp rim. */
@Composable
fun CyberGlassPanel(
    modifier: Modifier = Modifier,
    corner: Dp = 22.dp,
    glowing: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = CyberGlassColors
    val shape = RoundedCornerShape(corner)
    Column(
        modifier
            .then(if (glowing) Modifier.neonGlow(glass.Cyan, spread = 6.dp, intensity = 0.10f * glass.Glow) else Modifier)
            .clip(shape)
            .background(glass.Card.copy(alpha = 0.90f))
            .border(BorderStroke(1.dp, Brush.linearGradient(listOf(glass.Cyan, glass.Violet))), shape),
        content = content,
    )
}

/** The album's artwork: rounded, with a soft neon rim and glow; the waveform mark without one. */
@Composable
fun AlbumArtwork(url: String?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 14.dp) {
    val glass = CyberGlassColors
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .size(size)
            .neonGlow(glass.Cyan, spread = 8.dp, intensity = 0.16f * glass.Glow)
            .clip(shape)
            .border(1.dp, Brush.linearGradient(listOf(glass.Cyan.copy(alpha = 0.75f), glass.Violet.copy(alpha = 0.75f))), shape),
    ) {
        if (url.isNullOrBlank()) NeonWaveformArt(Modifier.size(size), cornerRadius = corner)
        else SubcomposeAsyncImage(
            model = url,
            contentDescription = "Album cover",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size),
            loading = { NeonWaveformArt(Modifier.size(size), cornerRadius = corner) },
            error = { NeonWaveformArt(Modifier.size(size), cornerRadius = corner) },
        )
    }
}

/** "14 TRACKS", "ALBUM": a small outlined capsule. */
@Composable
fun CyberCountPill(text: String, modifier: Modifier = Modifier) {
    val glass = CyberGlassColors
    Text(
        text.uppercase(),
        color = glass.TextSilver,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = modifier
            .clip(CircleShape)
            .background(glass.Chip)
            .border(1.dp, glass.ChipBorder, CircleShape)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** "FLAC", "HI-RES", "MP3 320": the glowing quality badge. */
@Composable
fun CyberQualityBadge(text: String, modifier: Modifier = Modifier) {
    val glass = CyberGlassColors
    val accent = when {
        text.startsWith("HI-RES") -> glass.Violet
        text.startsWith("FLAC") -> glass.Mint
        else -> glass.Sky
    }
    Text(
        text,
        color = accent,
        fontSize = 10.sp,
        fontWeight = FontWeight.ExtraBold,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .neonGlow(accent, spread = 4.dp, intensity = 0.12f * glass.Glow)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** The badge for an output format. */
fun qualityBadgeFor(format: SpotiFlacOutputFormat): String = when (format) {
    SpotiFlacOutputFormat.FLAC_HI_RES_96 -> "HI-RES"
    SpotiFlacOutputFormat.MP3_320 -> "MP3 320"
    else -> "FLAC"
}

/** The glowing neon capsule button ("Download Album", "Pause All"). */
@Composable
fun NeonPillButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prominent: Boolean = true,
    destructive: Boolean = false,
) {
    val glass = CyberGlassColors
    val ink = when {
        prominent -> glass.OnAccent
        destructive -> Color(0xFFFF6B8B)
        else -> glass.Cyan
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = ink,
        modifier = modifier
            .heightIn(min = 44.dp)
            .then(if (prominent && enabled) Modifier.neonGlow(glass.Cyan, spread = 10.dp, intensity = 0.22f * glass.Glow) else Modifier),
    ) {
        Row(
            Modifier
                .then(if (prominent) Modifier.background(glass.SelectedChip, CircleShape) else Modifier.background(glass.Recessed, CircleShape))
                .then(if (prominent) Modifier else Modifier.border(1.dp, ink.copy(alpha = 0.45f), CircleShape))
                .padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, Modifier.size(17.dp), tint = ink.copy(alpha = if (enabled) 1f else 0.45f))
            Spacer(Modifier.width(8.dp))
            Text(text, color = ink.copy(alpha = if (enabled) 1f else 0.45f), fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
        }
    }
}

/** The master progress line: violet into cyan into mint, a white laser spark at its head. */
@Composable
fun LaserProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val glass = CyberGlassColors
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(400), label = "album-progress")
    BoxWithConstraints(modifier.fillMaxWidth().height(height + 6.dp), contentAlignment = Alignment.CenterStart) {
        val width = maxWidth
        Box(Modifier.fillMaxWidth().height(height).clip(CircleShape).background(glass.Sky.copy(alpha = 0.12f)))
        Box(
            Modifier.width(width * animated).height(height).clip(CircleShape)
                .background(Brush.horizontalGradient(glass.ProgressColors)),
        )
        if (animated > 0f && animated < 1f) {
            val spark = height + 4.dp
            Box(
                Modifier
                    .offset(x = (width * animated - spark / 2).coerceAtLeast(0.dp))
                    .size(spark)
                    .neonGlow(glass.Cyan, spread = 6.dp, intensity = 0.6f * glass.Glow)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

/**
 * An album found by search: cover, title, artist (and type), the track
 * count and quality, a glowing Download Album, and a tap to choose tracks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlbumSearchCard(
    edition: AlbumEdition,
    quality: String,
    busy: Boolean,
    loading: Boolean,
    onOpen: () -> Unit,
    onDownloadAll: () -> Unit,
    modifier: Modifier = Modifier,
    queuedLabel: String? = null,
) {
    val glass = CyberGlassColors
    CyberGlassPanel(modifier.fillMaxWidth().testTag(AlbumCyberTags.SEARCH_CARD)) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Button, onClick = onOpen).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumArtwork(edition.cover, 76.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(edition.title, color = glass.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(edition.artist, edition.recordType.replaceFirstChar { it.uppercase() }.takeIf { it.isNotBlank() && it != "Album" })
                    .filterNotNull().joinToString(" · "),
                    color = glass.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp))
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (edition.trackCount > 0) CyberCountPill(if (edition.trackCount == 1) "1 track" else "${edition.trackCount} tracks")
                    CyberQualityBadge(quality)
                    if (edition.explicit) CyberCountPill("E")
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (queuedLabel != null) {
                Text(queuedLabel, color = glass.LiveInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            } else {
                NeonPillButton(
                    text = if (loading) "Loading tracklist…" else "Download Album",
                    icon = Icons.Rounded.Download,
                    onClick = onDownloadAll,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).testTag(AlbumCyberTags.DOWNLOAD_ALBUM),
                )
            }
            Spacer(Modifier.width(10.dp))
            NeonPillButton("Tracks", Icons.Rounded.LibraryMusic, onOpen, enabled = !busy, prominent = false)
        }
    }
}

/** The album's header, master progress and its one main action, for the album sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlbumBatchHeader(
    batch: AlbumBatch,
    quality: String,
    engine: String,
    onMaster: () -> Unit,
    masterEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val glass = CyberGlassColors
    CyberGlassPanel(modifier.fillMaxWidth(), glowing = batch.active) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AlbumArtwork(batch.coverUrl, 96.dp, corner = 16.dp)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(batch.title, color = glass.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(listOf(batch.artist, batch.year).filter { it.isNotBlank() }.joinToString(" · "),
                    color = glass.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CyberCountPill(if (batch.tracks.size == 1) "1 track" else "${batch.tracks.size} tracks")
                    CyberQualityBadge(quality)
                    CyberCountPill(engine)
                }
            }
        }
        BatchProgress(batch, Modifier.padding(horizontal = 16.dp))
        val (label, icon, prominent) = masterAction(batch)
        NeonPillButton(label, icon, onMaster, enabled = masterEnabled, prominent = prominent,
            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(AlbumCyberTags.MASTER_BUTTON))
    }
}

/** "Download All", "Pause All", "Resume", "Retry failed", "Download all again". */
fun masterAction(batch: AlbumBatch): Triple<String, ImageVector, Boolean> = when {
    batch.canPause -> Triple("Pause All", Icons.Rounded.Pause, false)
    batch.canResume -> Triple("Resume", Icons.Rounded.PlayArrow, true)
    batch.canRetryFailed -> Triple("Retry failed (${batch.failed})", Icons.Rounded.Refresh, true)
    batch.canDownload -> Triple(if (batch.completed == 0) "Download All (${batch.total})" else "Download missing (${batch.total - batch.completed})",
        Icons.Rounded.Download, true)
    else -> Triple("Select tracks", Icons.Rounded.Download, true)
}

/** "Downloading (3/14) · 42% · 3.4 MB/s" over the laser bar. */
@Composable
private fun BatchProgress(batch: AlbumBatch, modifier: Modifier = Modifier) {
    val glass = CyberGlassColors
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(batch.statusLabel.uppercase(), color = statusColor(batch.state), fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold, letterSpacing = 0.9.sp, modifier = Modifier.weight(1f))
            val speed = transferSpeedLabel(batch.bytesPerSecond)
            if (speed.isNotEmpty()) Text(speed, color = glass.Sky, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 10.dp))
            Text("${batch.completed}/${batch.total} · ${batch.percent}%", color = glass.TextSecondary, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
        }
        LaserProgressBar(batch.fraction, Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun statusColor(state: AlbumBatchState): Color {
    val glass = CyberGlassColors
    return when (state) {
        AlbumBatchState.FAILED, AlbumBatchState.PARTIAL -> Color(0xFFFF6B8B)
        AlbumBatchState.COMPLETED -> glass.Mint
        AlbumBatchState.PAUSED, AlbumBatchState.READY -> glass.TextSecondary
        else -> glass.LiveInk
    }
}

/**
 * One track in an album: its number, title, length, and its live state
 * (queued, a cyan progress ring, "24D Indexed ✔", Retry). [selectable]
 * shows the checkbox ring before the album is queued.
 */
@Composable
fun AlbumTrackLine(
    row: AlbumTrackRow,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    selectable: Boolean = false,
    onToggle: ((Boolean) -> Unit)? = null,
    controlsEnabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val glass = CyberGlassColors
    val dim = row.phase == AlbumTrackPhase.NOT_SELECTED || row.phase == AlbumTrackPhase.SKIPPED
    Row(
        modifier
            .fillMaxWidth()
            .testTag(AlbumCyberTags.track(row.track.id))
            .then(if (selectable && onToggle != null && row.track.uri == null) Modifier.clickable(role = Role.Checkbox, enabled = controlsEnabled) {
                onToggle(!row.track.selectedForDownload)
            } else Modifier)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectable && row.track.uri == null) {
            SelectRing(row.track.selectedForDownload, Modifier.padding(end = 10.dp).semantics {
                contentDescription = (if (row.track.selectedForDownload) "Selected: " else "Not selected: ") + row.track.title
            })
        }
        Text(row.number, color = glass.TextQuiet, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(34.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(row.track.title, color = if (dim) glass.TextQuiet else glass.TextPrimary, fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val length = "${row.track.durationMs / 60_000}:${((row.track.durationMs / 1_000) % 60).toString().padStart(2, '0')}"
            val line = row.detail?.takeIf { it.isNotBlank() } ?: listOf(
                row.track.artist.takeIf { it.isNotBlank() }, length, phaseNote(row.phase),
            ).filterNotNull().joinToString(" · ")
            Text(line, color = if (row.phase == AlbumTrackPhase.FAILED || row.phase == AlbumTrackPhase.VERIFY) Color(0xFFFF6B8B) else glass.TextSecondary,
                fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        TrackState(row, onRetry, onSkip, controlsEnabled)
        trailing?.invoke()
    }
}

private fun phaseNote(phase: AlbumTrackPhase): String? = when (phase) {
    AlbumTrackPhase.IN_LIBRARY -> "Already on your phone"
    AlbumTrackPhase.SAVED -> "Saved"
    AlbumTrackPhase.SKIPPED -> "Skipped"
    AlbumTrackPhase.NOT_SELECTED -> "Not selected"
    else -> null
}

@Composable
private fun SelectRing(selected: Boolean, modifier: Modifier = Modifier) {
    val glass = CyberGlassColors
    Box(
        modifier
            .size(20.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(glass.SelectedChip) else Modifier.border(1.5.dp, glass.ChipBorder, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(20.dp), tint = glass.OnAccent)
    }
}

@Composable
private fun TrackState(row: AlbumTrackRow, onRetry: () -> Unit, onSkip: () -> Unit, enabled: Boolean) {
    val glass = CyberGlassColors
    when (row.phase) {
        AlbumTrackPhase.DOWNLOADING -> {
            val fraction = row.fraction
            Box(Modifier.size(30.dp).neonGlow(glass.Cyan, spread = 4.dp, intensity = 0.25f * glass.Glow), contentAlignment = Alignment.Center) {
                if (fraction == null) CircularProgressIndicator(Modifier.size(26.dp), color = glass.Cyan, strokeWidth = 3.dp,
                    trackColor = glass.Cyan.copy(alpha = 0.15f), strokeCap = StrokeCap.Round)
                else {
                    CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(26.dp), color = glass.Cyan, strokeWidth = 3.dp,
                        trackColor = glass.Cyan.copy(alpha = 0.15f), strokeCap = StrokeCap.Round)
                    Text("${(fraction * 100).toInt()}", color = glass.TextSilver, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        AlbumTrackPhase.INDEXING -> BrainChip("24D…", glowing = true)
        AlbumTrackPhase.INDEXED -> BrainChip("24D Indexed ✔", glowing = false)
        AlbumTrackPhase.SAVED, AlbumTrackPhase.IN_LIBRARY ->
            Icon(Icons.Rounded.CheckCircle, phaseNote(row.phase), Modifier.size(22.dp), tint = glass.Mint)
        AlbumTrackPhase.FAILED, AlbumTrackPhase.VERIFY ->
            SmallPill(if (row.phase == AlbumTrackPhase.VERIFY) "Verify" else "Retry",
                if (row.phase == AlbumTrackPhase.VERIFY) Icons.Rounded.VerifiedUser else Icons.Rounded.Refresh,
                onRetry, enabled, accent = Color(0xFFFF6B8B))
        AlbumTrackPhase.SKIPPED -> if (row.canRetry) SmallPill("Add back", Icons.Rounded.Refresh, onRetry, enabled, accent = glass.Cyan)
        AlbumTrackPhase.WAITING -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Schedule, "Queued", Modifier.size(18.dp), tint = glass.TextQuiet)
            if (row.canSkip) Box(
                Modifier.padding(start = 6.dp).size(30.dp).clip(CircleShape)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onSkip),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.SkipNext, "Skip ${row.track.title}", Modifier.size(18.dp), tint = glass.TextSecondary) }
        }
        AlbumTrackPhase.NOT_SELECTED -> Unit
    }
}

/** The mint "24D Indexed ✔" capsule (and "24D…" while it measures). */
@Composable
private fun BrainChip(text: String, glowing: Boolean) {
    val glass = CyberGlassColors
    Text(
        text,
        color = glass.OnAccent,
        fontSize = 10.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier
            .neonGlow(glass.Mint, spread = 4.dp, intensity = (if (glowing) 0.35f else 0.18f) * glass.Glow)
            .clip(CircleShape)
            .background(glass.SelectedChip)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun SmallPill(text: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean, accent: Color) {
    Row(
        Modifier
            .clip(CircleShape)
            .border(1.dp, accent.copy(alpha = 0.5f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(13.dp), tint = accent)
        Spacer(Modifier.width(4.dp))
        Text(text, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * An album in the download queue, as one card instead of a row per track:
 * cover, title, "Downloading (3/14)", the laser bar, speed, Pause / Resume /
 * Retry / Cancel. Tap the arrow to see its tracks.
 */
@Composable
fun AlbumBatchCard(
    batch: AlbumBatch,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetryTrack: (String) -> Unit,
    onSkipTrack: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val glass = CyberGlassColors
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(220), label = "batch-arrow")
    CyberGlassPanel(modifier.fillMaxWidth().testTag(AlbumCyberTags.BATCH_CARD), corner = 20.dp, glowing = batch.active) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(start = 12.dp, top = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumArtwork(batch.coverUrl, 56.dp, corner = 12.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(batch.title, color = glass.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(batch.artist, batch.year).filter { it.isNotBlank() }.joinToString(" · "), color = glass.TextSecondary,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onToggle).testTag(AlbumCyberTags.BATCH_TOGGLE)
                    .semantics { contentDescription = if (expanded) "Hide the tracks of ${batch.title}" else "Show the tracks of ${batch.title}" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(rotation), tint = glass.TextSilver) }
        }
        BatchProgress(batch, Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        if (batch.canPause || batch.canResume || batch.canRetryFailed || batch.canCancel) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (batch.canPause) SmallPill("Pause", Icons.Rounded.Pause, onPause, enabled, glass.Cyan)
                if (batch.canResume) SmallPill("Resume", Icons.Rounded.PlayArrow, onResume, enabled, glass.Mint)
                if (batch.canRetryFailed) SmallPill("Retry ${batch.failed}", Icons.Rounded.Refresh, onResume, enabled, glass.Mint)
                Spacer(Modifier.weight(1f))
                if (batch.canCancel) SmallPill("Cancel", Icons.Rounded.Close, onCancel, enabled, Color(0xFFFF6B8B))
            }
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth().background(glass.Obsidian.copy(alpha = 0.35f)).padding(vertical = 4.dp)) {
                batch.tracks.filter { it.phase != AlbumTrackPhase.NOT_SELECTED }.forEach { row ->
                    AlbumTrackLine(row, onRetry = { onRetryTrack(row.track.id) }, onSkip = { onSkipTrack(row.track.id) },
                        controlsEnabled = enabled && !(row.canRetry && batch.active))
                }
            }
        }
    }
}

/** A failure or a note, in the same glass. */
@Composable
fun CyberNotice(text: String, modifier: Modifier = Modifier, error: Boolean = false, onDismiss: (() -> Unit)? = null) {
    val glass = CyberGlassColors
    val accent = if (error) Color(0xFFFF6B8B) else glass.Cyan
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (error) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = accent)
        Text(text, color = glass.TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
        if (onDismiss != null) Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Dismiss", Modifier.size(16.dp), tint = glass.TextSecondary)
        }
    }
}
