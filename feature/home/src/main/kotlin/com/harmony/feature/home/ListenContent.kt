package com.harmony.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harmony.core.model.MoodFilter
import com.harmony.core.model.SmartPlaylistType
import com.harmony.core.model.Song
import com.harmony.core.ui.component.Artwork
import com.harmony.core.ui.room.RoomButton
import com.harmony.core.ui.room.RoomButtonKind
import com.harmony.core.ui.room.RoomCard
import com.harmony.core.ui.room.RoomChip
import com.harmony.core.ui.room.RoomEmptyState
import com.harmony.core.ui.room.RoomSectionTitle
import com.harmony.core.ui.room.RoomTopBar
import com.harmony.core.ui.theme.Room
import com.harmony.core.ui.theme.RoomType

/**
 * What Listen can do, as plain callbacks, so the screen renders the same in
 * the app, in tests and in design shots. Every one maps to an existing
 * ViewModel function or a navigation (docs/redesign/09-handoff.md §5.2).
 */
class ListenActions(
    val onTogglePlayback: () -> Unit = {},
    val onResume: (Song) -> Unit = {},
    val onOpenNowPlaying: () -> Unit = {},
    val onStartMix: () -> Unit = {},
    val onMood: (MoodFilter) -> Unit = {},
    val onOpenAlbum: (Long) -> Unit = {},
    val onPlayRecent: (Song) -> Unit = {},
    val onOpenSmart: (SmartPlaylistType) -> Unit = {},
    val onOpenPlaylist: (Long) -> Unit = {},
    val onOpenDiscover: () -> Unit = {},
    val onOpenAdd: () -> Unit = {},
    val onOpenLibrary: () -> Unit = {},
)

/** The moods Listen offers first, in the listener's words (docs/redesign/06-ux-copy.md §3). */
internal val ListenMoods: List<Pair<MoodFilter, Int>> = listOf(
    MoodFilter.CALM to R.string.listen_mood_calm,
    MoodFilter.FOCUS to R.string.listen_mood_focus,
    MoodFilter.WORKOUT to R.string.listen_mood_energy,
    MoodFilter.HAPPY to R.string.listen_mood_happy,
    MoodFilter.DARK to R.string.listen_mood_late_night,
    MoodFilter.PARTY to R.string.listen_mood_party,
    MoodFilter.RELAXING to R.string.listen_mood_relaxed,
    MoodFilter.SAD to R.string.listen_mood_melancholy,
    MoodFilter.EPIC to R.string.listen_mood_epic,
    MoodFilter.EMOTIONAL to R.string.listen_mood_emotional,
    MoodFilter.AGGRESSIVE to R.string.listen_mood_intense,
)

private data class SmartTile(val type: SmartPlaylistType, val label: Int, val icon: ImageVector)

private val SmartTiles = listOf(
    SmartTile(SmartPlaylistType.FAVORITES, R.string.listen_smart_favourites, Icons.Rounded.Favorite),
    SmartTile(SmartPlaylistType.MOST_PLAYED, R.string.listen_smart_most_played, Icons.Rounded.TrendingUp),
    SmartTile(SmartPlaylistType.RECENTLY_ADDED, R.string.listen_smart_recently_added, Icons.Rounded.NewReleases),
    SmartTile(SmartPlaylistType.RECENTLY_PLAYED, R.string.listen_smart_recently_played, Icons.Rounded.History),
    SmartTile(SmartPlaylistType.HIGHEST_ENERGY, R.string.listen_smart_high_energy, Icons.Rounded.BatteryChargingFull),
    SmartTile(SmartPlaylistType.LOWEST_ENERGY, R.string.listen_smart_low_energy, Icons.Rounded.SelfImprovement),
)

/**
 * Listen: the start destination. The first thing is sound in one tap
 * (resume, a mix of the whole library, or a mood); then what was played
 * lately, the playlists that build themselves, the listener's own playlists,
 * and a way to find something new.
 *
 * The mini player and the navigation bar are the shell's; [contentPadding]
 * carries their measured height so the last section scrolls clear.
 */
@Composable
fun ListenContent(
    state: HomeUiState,
    actions: ListenActions,
    modifier: Modifier = Modifier,
    mixStarting: Boolean = false,
    moodsReady: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    listState: LazyListState = rememberLazyListState(),
    topActions: @Composable RowScope.() -> Unit = {},
) {
    val r = Room
    LazyColumn(
        modifier.fillMaxSize().background(r.ground).testTag("listen"),
        state = listState,
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item("top") { RoomTopBar(stringResource(R.string.listen_title), actions = topActions) }

        if (state.loaded && state.libraryEmpty) {
            item("empty") {
                RoomEmptyState(
                    title = stringResource(R.string.listen_empty_title),
                    body = stringResource(R.string.listen_empty_body),
                    action = stringResource(R.string.listen_add_music),
                    onAction = actions.onOpenAdd,
                )
            }
            return@LazyColumn
        }

        item("resume") { ResumeCard(state, actions, Modifier.padding(horizontal = 16.dp)) }

        item("start") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RoomSectionTitle(stringResource(R.string.listen_start_something))
                RoomButton(
                    text = stringResource(R.string.listen_mix_my_library),
                    onClick = actions.onStartMix,
                    icon = Icons.Rounded.Shuffle,
                    loading = mixStarting,
                    prominent = true,
                    fillWidth = true,
                )
            }
        }

        item("moods") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ListenMoods.forEach { (mood, label) ->
                        RoomChip(stringResource(label), selected = false, enabled = moodsReady, onClick = { actions.onMood(mood) })
                    }
                }
                if (!moodsReady) {
                    Text(
                        stringResource(R.string.listen_moods_not_ready),
                        style = RoomType.bodySmall,
                        color = r.muted,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }

        if (state.recentAlbums.isNotEmpty() || state.recentlyPlayed.isNotEmpty()) {
            item("recent") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoomSectionTitle(stringResource(R.string.listen_recently_played), Modifier.padding(horizontal = 16.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.recentAlbums.isNotEmpty()) {
                            items(state.recentAlbums, key = { "a${it.id}" }) { album ->
                                CoverTile(album.title, album.artist, album.artworkUri) { actions.onOpenAlbum(album.id) }
                            }
                        } else {
                            items(state.recentlyPlayed, key = { "s${it.id}" }) { song ->
                                CoverTile(song.title, song.artist, song.artworkUri) { actions.onPlayRecent(song) }
                            }
                        }
                    }
                }
            }
        }

        item("smart") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RoomSectionTitle(stringResource(R.string.listen_made_for_you))
                SmartTiles.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { tile -> SmartTileCard(tile, Modifier.weight(1f)) { actions.onOpenSmart(tile.type) } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        if (state.playlists.isNotEmpty()) {
            item("playlists-title") {
                RoomSectionTitle(
                    stringResource(R.string.listen_your_playlists),
                    Modifier.padding(horizontal = 16.dp),
                    action = stringResource(R.string.listen_see_all),
                    onAction = actions.onOpenLibrary,
                )
            }
            items(state.playlists.take(4), key = { "p${it.id}" }) { playlist ->
                PlaylistRow(playlist) { actions.onOpenPlaylist(playlist.id) }
            }
        }

        item("discover") {
            RoomCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), onClick = actions.onOpenDiscover) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = r.amberText, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.listen_new_for_you), style = RoomType.nameM, color = r.ink)
                        Text(stringResource(R.string.listen_new_for_you_body), style = RoomType.bodySmall, color = r.muted)
                    }
                }
            }
        }
    }
}

/**
 * The first card: what is playing (with play/pause), or where the listener
 * stopped (with Resume). Hidden when there is neither.
 */
@Composable
private fun ResumeCard(state: HomeUiState, actions: ListenActions, modifier: Modifier) {
    val r = Room
    val playing = state.nowPlaying
    val song = playing?.song ?: state.resumeSong ?: return
    val title = if (playing != null) {
        stringResource(if (playing.isPlaying) R.string.listen_now_playing else R.string.listen_paused)
    } else {
        stringResource(R.string.listen_pick_up)
    }
    RoomCard(modifier.fillMaxWidth(), onClick = if (playing != null) actions.onOpenNowPlaying else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(
                song.artworkUri,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                cornerRadius = 12.dp,
                fallback = { ArtworkFallback() },
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = RoomType.micro, color = r.amberText)
                Text(song.title, style = RoomType.nameM, color = r.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = RoomType.bodySmall, color = r.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                when {
                    playing != null -> RoomButton(
                        text = stringResource(if (playing.isPlaying) R.string.listen_pause else R.string.listen_play),
                        onClick = actions.onTogglePlayback,
                        icon = if (playing.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        kind = RoomButtonKind.Tonal,
                    )
                    else -> RoomButton(
                        text = stringResource(R.string.listen_resume),
                        onClick = { actions.onResume(song) },
                        icon = Icons.Rounded.PlayArrow,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtworkFallback() {
    Box(Modifier.fillMaxSize().background(Room.raised), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = Room.faint, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun CoverTile(title: String, subtitle: String, artworkUri: String?, onClick: () -> Unit) {
    val r = Room
    Column(
        Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$title, $subtitle" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Artwork(
            artworkUri,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            cornerRadius = 12.dp,
            fallback = { ArtworkFallback() },
        )
        Text(title, style = RoomType.title, color = r.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = RoomType.bodySmall, color = r.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SmartTileCard(tile: SmartTile, modifier: Modifier, onClick: () -> Unit) {
    val r = Room
    RoomCard(modifier.heightIn(min = 72.dp), onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(tile.icon, null, tint = r.amberText, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(tile.label),
                style = RoomType.title,
                color = r.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlaylistRow(playlist: HomePlaylist, onClick: () -> Unit) {
    val r = Room
    val count = pluralStringResource(R.plurals.listen_song_count, playlist.songCount, playlist.songCount)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(r.raised), contentAlignment = Alignment.Center) {
            val cover = playlist.artwork.firstOrNull()
            if (cover != null) {
                Artwork(cover, null, Modifier.fillMaxSize(), cornerRadius = 8.dp, fallback = { ArtworkFallback() })
            } else {
                Icon(Icons.Rounded.QueueMusic, null, tint = r.faint)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = RoomType.title, color = r.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(count, style = RoomType.bodySmall, color = r.muted)
        }
    }
}
