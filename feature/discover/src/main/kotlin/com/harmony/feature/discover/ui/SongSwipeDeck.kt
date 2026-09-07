package com.harmony.feature.discover.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.feature.discover.model.SwipeRound
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
internal fun SongSwipeDeck(state: DiscoverUiState, palette: EditorialPalette,
    viewModel: DiscoverViewModel, onSeeAlbums: () -> Unit, onDownload: (String) -> Unit, onOpen: (String) -> Unit) {
    val preview by viewModel.preview.collectAsState()
    val busy by viewModel.ratingBusy.collectAsState()
    val undoId by viewModel.undoSongId.collectAsState()
    var skipped by rememberSaveable(state.genre, state.round.number) { mutableStateOf(arrayListOf<String>()) }
    val song = state.tasteSongs.firstOrNull { it.id !in skipped }
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ROUND ${state.round.number} · FIND YOUR ALBUM", color = palette.muted, fontSize = 10.sp,
                    letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                Text(if (state.round.completed) "${state.round.songIds.size} choices · ${state.roundLikedCount} likes this round"
                    else "${state.round.songIds.size}/${SwipeRound.SIZE} choices · ${SwipeRound.SIZE - state.round.songIds.size} to your album",
                    color = palette.ink, fontSize = 12.sp)
            }
            IconButton(onClick = { skipped = arrayListOf(); viewModel.undoRating() }, enabled = undoId != null && !busy) {
                Icon(Icons.AutoMirrored.Rounded.Undo, "Undo last song choice", tint = palette.ink)
            }
        }
        LinearProgressIndicator(progress = { if (state.round.completed) 1f else state.round.songIds.size.toFloat() / SwipeRound.SIZE },
            modifier = Modifier.fillMaxWidth(), color = palette.ink, trackColor = palette.ink.copy(alpha = 0.1f))
        if (state.round.completed) {
            RoundRecommendationCard(state, palette, busy, onNext = { skipped = arrayListOf(); viewModel.nextRound() },
                onDownload = onDownload, onOpen = onOpen, onSave = viewModel::toggleSaved, onSeeAlbums = onSeeAlbums)
        } else if (song == null) {
            Text("You've tried every song in this selection.", color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Your choices are saved. Explore albums or change the genre for more songs.", color = palette.muted)
            if (skipped.isNotEmpty()) OutlinedButton(onClick = { skipped = arrayListOf() }) { Text("Revisit skipped songs") }
            if (state.canFinishRemaining) Button(onClick = viewModel::finishRemaining, enabled = !busy) {
                Text("Recommend from my available choices")
            }
            if (state.genre != null) OutlinedButton(onClick = { viewModel.selectGenre(null) }) { Text("Continue with all genres") }
            Button(onClick = onSeeAlbums) { Text("See album recommendations") }
        } else key(song.id) {
            val scope = rememberCoroutineScope()
            val movement = remember { Animatable(0f) }
            var drag by remember { mutableFloatStateOf(0f) }
            var leaving by remember { mutableStateOf(false) }
            val threshold = with(LocalDensity.current) { 90.dp.toPx() }
            val rate: (Boolean) -> Unit = { liked ->
                if (!busy && !leaving) {
                    leaving = true
                    viewModel.stopPreview()
                    scope.launch {
                        movement.snapTo(drag)
                        movement.animateTo(if (liked) threshold * 4 else -threshold * 4, tween(180))
                        viewModel.rate(song, liked)?.join()
                        // If persistence fails, this card remains available for another attempt.
                        movement.snapTo(0f); drag = 0f; leaving = false
                    }
                }
            }
            val position = if (leaving) movement.value else drag
            Surface(color = Color(0xFF171A19), shape = RoundedCornerShape(25.dp),
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    translationX = position; rotationZ = position / threshold * 5f
                    alpha = (1f - abs(position) / (threshold * 6)).coerceIn(0.2f, 1f)
                }.pointerInput(song.id, busy, leaving) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount ->
                            if (!busy && !leaving) { change.consume(); drag += amount }
                        },
                        onDragEnd = { if (!busy && !leaving && abs(drag) >= threshold) rate(drag > 0) else drag = 0f },
                        onDragCancel = { drag = 0f },
                    )
                }) {
                Column(Modifier.padding(14.dp)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        AlbumCover(song.album, Modifier.sizeIn(maxWidth = 300.dp, maxHeight = 240.dp)
                            .aspectRatio(1f).clip(RoundedCornerShape(16.dp)))
                        if (abs(position) > threshold / 3) Surface(shape = RoundedCornerShape(10.dp),
                            color = if (position > 0) Color(0xFFBAE8C2) else Color(0xFFF5B8B2),
                            modifier = Modifier.align(if (position > 0) Alignment.TopStart else Alignment.TopEnd).padding(12.dp)) {
                            Text(if (position > 0) "LIKE" else "NOT FOR ME", Modifier.padding(12.dp),
                                fontWeight = FontWeight.Black, color = Color(0xFF171A19))
                        }
                    }
                    Text(song.title, color = Color(0xFFF7F3EB), fontSize = 24.sp, lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
                    Text(song.album.artist, color = Color(song.album.color), fontWeight = FontWeight.SemiBold)
                    Text(song.album.title, color = Color(0xFFBFC4BD), fontSize = 12.sp)
                    val current = preview.takeIf { it.songId == song.id }
                    FilledTonalButton(onClick = { viewModel.playPreview(song) }, enabled = !busy && !leaving,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        if (current?.loading == true) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(if (current?.playing == true) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(when { current?.loading == true -> "Cancel preview"; current?.playing == true -> "Stop preview"; else -> "Listen · up to 30 seconds" })
                    }
                    current?.message?.let { Text(it, color = Color(0xFFBFC4BD), fontSize = 12.sp) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = { rate(false) }, enabled = !busy && !leaving,
                    modifier = Modifier.size(60.dp), shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFF5B8B2), contentColor = Color(0xFF391C1C))) {
                    Icon(Icons.Rounded.Close, "Dislike this song · swipe left", Modifier.size(30.dp))
                }
                TextButton(onClick = { viewModel.stopPreview(); skipped = ArrayList(skipped + song.id) }, enabled = !busy && !leaving) {
                    Text("Skip", color = palette.ink)
                }
                FilledIconButton(onClick = { rate(true) }, enabled = !busy && !leaving,
                    modifier = Modifier.size(60.dp), shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFBAE8C2), contentColor = Color(0xFF12341C))) {
                    Icon(Icons.Rounded.Favorite, "Like this song · swipe right", Modifier.size(28.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("← Not for me", color = palette.muted, fontSize = 12.sp)
                Text("Love it →", color = palette.muted, fontSize = 12.sp)
            }
        }
        if (!state.round.completed) {
            Text("Like or pass on ${SwipeRound.SIZE} songs to reveal one album, with the reasons behind the pick. Skip doesn't count.",
                color = palette.muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}
