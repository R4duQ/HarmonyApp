package com.harmony.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.ui.component.EditorialCard
import com.harmony.core.ui.component.EditorialPalette
import androidx.compose.ui.graphics.Color
import java.util.Locale

// LiveGreen in SoulseekConnectCard.kt is file-private, so it cannot be reused
// here. Same value on purpose: "connected" and "sharing" are both live-state
// indicators and should read identically.
private val SharingGreen = Color(0xFF2E7D4F)

/**
 * Share exactly one file back to the network.
 *
 * Soulseek is reciprocal by convention, and Harmony announced zero shared
 * files at login — accurate, but it is why some peers refuse or park queue
 * requests forever. This lets the share count be honestly non-zero without
 * Harmony ever indexing storage: the file arrives from the system
 * single-document picker, so no directory handle exists and nothing else on
 * the device is readable.
 */
@Composable
fun SoulseekSharingCard(
    sharedFolder: SoulseekSharedFolder?,
    isIndexing: Boolean,
    onPick: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    EditorialCard(palette = palette, modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (sharedFolder != null) SharingGreen else palette.muted),
                )
                Text(
                    when {
                        isIndexing -> "Indexing…"
                        sharedFolder != null -> "Sharing ${sharedFolder.fileCount} files"
                        else -> "Sharing nothing"
                    }.uppercase(Locale.US),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp,
                    color = if (sharedFolder != null) SharingGreen else palette.muted,
                    modifier = Modifier.padding(start = 7.dp),
                )
            }

            if (sharedFolder == null) {
                Text(
                    "Give something back",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.ink,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    "Soulseek runs on reciprocity, and Harmony currently tells the server you " +
                        "share nothing. Some peers refuse transfers on that basis. Pick a folder " +
                        "and the count becomes honest.",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "Harmony reads audio files inside the folder you pick, and nothing outside " +
                        "it. Choosing a folder does grant access to everything under it — so " +
                        "pick your music folder, not your whole storage.",
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 7.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    GatedPill(
                        text = if (isIndexing) "Indexing…" else "Choose a folder",
                        enabled = !isIndexing,
                        onClick = onPick,
                        palette = palette,
                    )
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.ink.copy(alpha = 0.06f))
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                ) {
                    Text(
                        sharedFolder.displayName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${sharedFolder.fileCount} audio files in ${sharedFolder.folderCount} " +
                            "folders · ${formatFileSize(sharedFolder.totalBytes)}",
                        fontSize = 11.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (sharedFolder.truncated) {
                        Text(
                            "Stopped at ${SoulseekShareIndexer.MAX_FILES} files — pick a narrower " +
                                "folder to control exactly what is shared.",
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = palette.muted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                Text(
                    "Peers can browse these files and download them from you. They still won't " +
                        "appear in search results — Harmony doesn't join the distributed search " +
                        "network, so your share is reachable by browsing you directly, not by " +
                        "searching.",
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onClear) { Text("Stop sharing") }
                    TextButton(onClick = onRefresh, enabled = !isIndexing) { Text("Rescan") }
                    GatedPill(
                        text = if (isIndexing) "Indexing…" else "Change folder",
                        enabled = !isIndexing,
                        onClick = onPick,
                        palette = palette,
                    )
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes bytes"
}
