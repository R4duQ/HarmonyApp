package com.harmony.feature.discover.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.feature.discover.model.SwipeRound
import androidx.compose.ui.graphics.luminance
import com.harmony.core.ui.component.GlassCard

@Composable
internal fun RoundRecommendationCard(state: DiscoverUiState, palette: EditorialPalette, busy: Boolean,
    onNext: () -> Unit, onDownload: (String) -> Unit, onOpen: (String) -> Unit,
    onSave: (String) -> Unit, onSeeAlbums: () -> Unit) {
    val result = state.roundRecommendation
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (result != null) "Your album is ready." else "You've explored this selection.",
            color = palette.ink, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (result == null) {
            Text("Every eligible album is marked as listened. You can revisit them in All albums or start another round.", color = palette.muted)
        } else {
            val album = result.album
            // Was a hardcoded near-black card with cream text — Discover's
            // own palette, unrelated to the rest of the app. The album's own
            // colour is kept for the accents, since that genuinely varies
            // per record and is the one thing here that should.
            val paper = palette.ink
            val quiet = palette.muted
            val accent = Color(album.color)
            GlassCard(palette = palette) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        AlbumCover(album, Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp)))
                    }
                    Column {
                        Text(if (result.exploratory) "A NEW DIRECTION" else "CHOSEN FROM YOUR SWIPES",
                            color = accent, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                        Text(album.title, color = paper, fontSize = 28.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold)
                        Text("${album.artist} · ${album.year}", color = accent, fontSize = 15.sp)
                    }
                    HorizontalDivider(color = quiet.copy(alpha = 0.2f))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("WHY THIS FITS YOU", color = accent, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                        Text(result.reason, color = paper, fontSize = 15.sp, lineHeight = 23.sp)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("WHY STAY FOR THE WHOLE ALBUM", color = accent, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                        Text(album.listeningNote, color = paper, fontSize = 15.sp, lineHeight = 23.sp)
                        Text("Listening note by Harmony", color = quiet, fontSize = 12.sp)
                    }
                    val entry = result.supportingSongs.firstOrNull { it.album.id == album.id }?.title ?: album.entryTracks.first()
                    Text("Start with: $entry", color = quiet, fontSize = 13.sp)
                    // Keeps a solid fill in the album's own colour: this is
                    // the card's single primary action, and turning it into
                    // another outline would leave the panel with no focus.
                    Button(onClick = { onDownload(album.id) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accent,
                            contentColor = if (accent.luminance() < 0.5f) Color.White else Color(0xFF171A19),
                        )) {
                        Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(8.dp))
                        Text("Open album · choose tracks")
                    }
                    Column(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onSave(album.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(if (state.roundAlbumSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                null, tint = paper, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text(if (state.roundAlbumSaved) "Saved · tap to remove" else "Save for later", color = paper)
                        }
                        TextButton(onClick = { onOpen(album.shflUrl) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Read on The Shfl", color = paper)
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = paper, modifier = Modifier.size(16.dp))
                        }
                    }
                    Text("Album page on The Shfl · Original listening note by Harmony.",
                        color = quiet, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }
        if (state.hasUnratedSongs) {
            Button(onClick = onNext, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Next ${SwipeRound.SIZE} songs")
            }
            Text("Your earlier choices keep helping. The next round gives your latest tastes more weight.",
                color = palette.muted, fontSize = 12.sp, lineHeight = 18.sp)
        } else {
            Text("You've rated every song in this selection. Your album and choices are saved; keep exploring in Albums.",
                color = palette.muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        TextButton(onClick = onSeeAlbums, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Browse albums", color = palette.ink)
        }
    }
}
