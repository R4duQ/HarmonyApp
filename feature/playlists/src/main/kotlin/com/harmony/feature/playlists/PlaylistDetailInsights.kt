package com.harmony.feature.playlists

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.model.Song
import com.harmony.core.ui.component.Artwork
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.glassFill
import kotlin.math.roundToInt

/*
 * The richer parts of the playlist page: the stat tiles, the "about this
 * playlist" card (sound quality, genres, years), the top artists, the songs
 * header and footer, and the little equalizer that marks the song playing.
 * Everything here is worked out by PlaylistDetailFormat; these only draw.
 */

internal object PlaylistInsightTags {
    const val STATS = "playlist_stats"
    const val ABOUT = "playlist_about"
    const val QUALITY = "playlist_quality"
    const val GENRES = "playlist_genres"
    const val YEARS = "playlist_years"
    const val ARTISTS = "playlist_artists"
    const val SONGS_HEADER = "playlist_songs_header"
    const val FOOTER = "playlist_footer"
    const val NOW_PLAYING = "playlist_now_playing"
}

private val CardShape = RoundedCornerShape(22.dp)

/** A soft card on the glass page: a touch of the accent over the glass, with a hairline. */
private fun Modifier.insightCard(palette: EditorialPalette): Modifier = this
    .clip(CardShape)
    .background(palette.accent.copy(alpha = 0.06f).compositeOver(glassFill(palette, 0.72f)))
    .border(1.dp, palette.line.copy(alpha = palette.line.alpha * 0.8f), CardShape)

@Composable
private fun Eyebrow(text: String, palette: EditorialPalette, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.6.sp,
        fontWeight = FontWeight.SemiBold,
        color = palette.muted,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// Now playing
// ---------------------------------------------------------------------------

/** "Playing from this playlist · Afterglow Street", when the song playing is in it. */
@Composable
internal fun NowPlayingChip(song: Song, palette: EditorialPalette, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(palette.accent.copy(alpha = 0.14f))
            .border(1.dp, palette.accent.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(start = 12.dp, end = 14.dp, top = 7.dp, bottom = 7.dp)
            .testTag(PlaylistInsightTags.NOW_PLAYING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayingBars(palette.accent, Modifier.size(14.dp))
        Text(
            "Playing from this playlist",
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.accent,
            modifier = Modifier.padding(start = 8.dp),
            maxLines = 1,
        )
        Text(
            "  ·  ${song.title}",
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = palette.ink.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** Three bars bouncing out of step: the song that's playing. */
@Composable
internal fun PlayingBars(color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "playing")
    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0.9f, 0.3f, infiniteRepeatable(tween(410, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(0.4f, 0.95f, infiniteRepeatable(tween(610, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    Canvas(modifier.semantics { contentDescription = "Now playing" }) {
        val w = size.width / 5f
        listOf(a, b, c).forEachIndexed { i, h ->
            val barH = size.height * h
            drawRoundRect(
                color,
                topLeft = Offset(w * (i * 2), size.height - barH),
                size = Size(w, barH),
                cornerRadius = CornerRadius(w / 2),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Stat tiles
// ---------------------------------------------------------------------------

/** Songs, length, artists, albums: four tiles in a row. */
@Composable
internal fun StatTiles(songs: List<Song>, palette: EditorialPalette, modifier: Modifier = Modifier) {
    val stats = remember(songs) { PlaylistDetailFormat.stats(songs) }
    Row(
        modifier.fillMaxWidth().testTag(PlaylistInsightTags.STATS),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatTile("${stats.songs}", if (stats.songs == 1) "song" else "songs", palette, Modifier.weight(1f))
        StatTile(PlaylistDetailFormat.compactDuration(stats.totalMs), "length", palette, Modifier.weight(1f))
        StatTile("${stats.artists}", if (stats.artists == 1) "artist" else "artists", palette, Modifier.weight(1f))
        StatTile("${stats.albums}", if (stats.albums == 1) "album" else "albums", palette, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(value: String, label: String, palette: EditorialPalette, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(palette.accent.copy(alpha = 0.07f).compositeOver(glassFill(palette, 0.72f)))
            .border(1.dp, palette.line.copy(alpha = palette.line.alpha * 0.8f), RoundedCornerShape(18.dp))
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            fontSize = 19.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            color = palette.ink,
            maxLines = 1,
        )
        Text(
            label.uppercase(),
            fontSize = 9.sp,
            lineHeight = 12.sp,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.muted,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// About this playlist
// ---------------------------------------------------------------------------

/**
 * Sound quality, genres and years in one card, each only when the tags have
 * something to say. Nothing at all to say: no card.
 */
@Composable
internal fun AboutCard(songs: List<Song>, palette: EditorialPalette, modifier: Modifier = Modifier) {
    val mix = remember(songs) { PlaylistDetailFormat.qualityMix(songs).filter { it.first != PlaylistDetailFormat.Quality.UNKNOWN } }
    val qualityLine = remember(songs) { PlaylistDetailFormat.qualitySummary(songs) }
    val genres = remember(songs) { PlaylistDetailFormat.genres(songs) }
    val years = remember(songs) { PlaylistDetailFormat.years(songs) }
    if (mix.isEmpty() && genres.isEmpty() && years == null) return

    Column(modifier.fillMaxWidth().insightCard(palette).padding(16.dp).testTag(PlaylistInsightTags.ABOUT)) {
        Text(
            "About this playlist",
            fontSize = 16.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Bold,
            color = palette.ink,
        )
        var first = true
        @Composable
        fun section(content: @Composable () -> Unit) {
            Spacer(Modifier.height(if (first) 12.dp else 14.dp))
            if (!first) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line.copy(alpha = palette.line.alpha * 0.6f)))
                Spacer(Modifier.height(14.dp))
            }
            first = false
            content()
        }
        if (mix.isNotEmpty()) section { QualitySection(mix, qualityLine, palette) }
        if (genres.isNotEmpty()) section { GenreSection(genres, songs.size, palette) }
        if (years != null) section { YearSection(years, palette) }
    }
}

private fun qualityColor(q: PlaylistDetailFormat.Quality, palette: EditorialPalette): Color = when (q) {
    PlaylistDetailFormat.Quality.HI_RES -> palette.accent
    PlaylistDetailFormat.Quality.LOSSLESS -> palette.accent.copy(alpha = 0.5f).compositeOver(palette.ink.copy(alpha = 0.25f))
    PlaylistDetailFormat.Quality.LOSSY -> palette.muted.copy(alpha = 0.55f)
    PlaylistDetailFormat.Quality.UNKNOWN -> palette.line
}

@Composable
private fun QualitySection(
    mix: List<Pair<PlaylistDetailFormat.Quality, Int>>,
    summary: String?,
    palette: EditorialPalette,
) {
    Column(Modifier.testTag(PlaylistInsightTags.QUALITY)) {
        Eyebrow("Sound quality", palette)
        if (summary != null) {
            Text(
                summary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                color = palette.ink,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        // One bar, split by how many songs are of each quality.
        val total = mix.sumOf { it.second }.coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
            mix.forEachIndexed { i, (q, n) ->
                Box(
                    Modifier
                        .weight(n.toFloat() / total)
                        .height(10.dp)
                        .padding(end = if (i < mix.lastIndex) 2.dp else 0.dp)
                        .background(qualityColor(q, palette)),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            mix.forEach { (q, n) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(qualityColor(q, palette)))
                    Text(
                        "${q.label} $n",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(start = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GenreSection(genres: List<Pair<String, Int>>, total: Int, palette: EditorialPalette) {
    Column(Modifier.testTag(PlaylistInsightTags.GENRES)) {
        Eyebrow("Genres", palette)
        Spacer(Modifier.height(8.dp))
        val top = genres.first().second.toFloat()
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            genres.forEach { (name, n) ->
                // The most common genre is the strongest chip; the rest fade with their share.
                val strength = 0.08f + 0.22f * (n / top)
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(palette.accent.copy(alpha = strength))
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.ink, maxLines = 1)
                    Text(
                        " ${(n * 100f / total).roundToInt()}%",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = palette.muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun YearSection(spread: PlaylistDetailFormat.YearSpread, palette: EditorialPalette) {
    Column(Modifier.testTag(PlaylistInsightTags.YEARS)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Years", palette, Modifier.weight(1f))
            Text(
                PlaylistDetailFormat.yearsCaption(spread),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = palette.muted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(10.dp))
        val most = spread.bars.maxOf { it.second }.coerceAtLeast(1)
        val peakBar = spread.bars.maxBy { it.second }.first
        val dim = palette.accent.copy(alpha = 0.32f)
        val empty = palette.line.copy(alpha = palette.line.alpha * 0.7f)
        Canvas(Modifier.fillMaxWidth().height(46.dp)) {
            val n = spread.bars.size
            val gap = if (n > 16) 2.dp.toPx() else 4.dp.toPx()
            val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
            spread.bars.forEachIndexed { i, (year, count) ->
                val h = if (count == 0) 2.dp.toPx() else (size.height * count / most).coerceAtLeast(4.dp.toPx())
                drawRoundRect(
                    when {
                        count == 0 -> empty
                        year == peakBar -> palette.accent
                        else -> dim
                    },
                    topLeft = Offset(i * (w + gap), size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(minOf(w / 2, 3.dp.toPx())),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("${spread.bars.first().first}", fontSize = 10.sp, lineHeight = 12.sp, color = palette.muted, modifier = Modifier.weight(1f))
            Text("${spread.to}", fontSize = 10.sp, lineHeight = 12.sp, color = palette.muted)
        }
    }
}

// ---------------------------------------------------------------------------
// Top artists
// ---------------------------------------------------------------------------

@Composable
internal fun TopArtists(songs: List<Song>, palette: EditorialPalette, modifier: Modifier = Modifier) {
    val artists = remember(songs) { PlaylistDetailFormat.topArtists(songs) }
    if (artists.size < 2) return
    Column(modifier.fillMaxWidth().testTag(PlaylistInsightTags.ARTISTS)) {
        Eyebrow("Most here", palette, Modifier.padding(start = 4.dp))
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            artists.forEach { a ->
                Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .border(2.dp, palette.accent.copy(alpha = 0.35f), CircleShape)
                            .padding(3.dp),
                    ) {
                        Artwork(a.artworkUri, contentDescription = null, modifier = Modifier.size(58.dp), cornerRadius = 29.dp)
                    }
                    Text(
                        a.name,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        if (a.songs == 1) "1 song" else "${a.songs} songs",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = palette.muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Songs header, footer, row details
// ---------------------------------------------------------------------------

@Composable
internal fun SongsHeader(songs: List<Song>, editable: Boolean, palette: EditorialPalette, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().testTag(PlaylistInsightTags.SONGS_HEADER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Songs", fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = palette.ink)
        Text(
            "  ${songs.size}",
            fontSize = 14.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.accent,
            modifier = Modifier.weight(1f),
        )
        if (editable && songs.size > 1) {
            Icon(Icons.Rounded.DragHandle, contentDescription = null, tint = palette.muted, modifier = Modifier.size(14.dp))
            Text(
                " Drag to reorder",
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = palette.muted,
            )
        }
    }
}

/** Under the last song: the whole playlist in one line, and when it was made. */
@Composable
internal fun PlaylistFooter(songs: List<Song>, createdAt: Long?, palette: EditorialPalette, modifier: Modifier = Modifier) {
    val summary = remember(songs) { PlaylistDetailFormat.summary(songs) }
    val quality = remember(songs) { PlaylistDetailFormat.qualitySummary(songs) }
    val created = remember(createdAt) { createdAt?.let(PlaylistDetailFormat::created) }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 22.dp)
            .testTag(PlaylistInsightTags.FOOTER),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.width(36.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(palette.accent.copy(alpha = 0.45f)))
        Spacer(Modifier.height(12.dp))
        Text(summary, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = palette.ink, textAlign = TextAlign.Center)
        listOfNotNull(quality, created).forEach {
            Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = palette.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** The small pill at a row's end: HI-RES in the accent, LOSSLESS in ink, a lossy file's bitrate plainly. */
@Composable
internal fun QualityBadge(song: Song, palette: EditorialPalette, modifier: Modifier = Modifier) {
    val text = remember(song) { PlaylistDetailFormat.badge(song) } ?: return
    val quality = remember(song) { PlaylistDetailFormat.quality(song) }
    val (fill, ink, rim) = when (quality) {
        PlaylistDetailFormat.Quality.HI_RES -> Triple(palette.accent.copy(alpha = 0.16f), palette.accent, palette.accent.copy(alpha = 0.45f))
        PlaylistDetailFormat.Quality.LOSSLESS -> Triple(palette.ink.copy(alpha = 0.06f), palette.ink.copy(alpha = 0.75f), palette.line)
        else -> Triple(Color.Transparent, palette.muted, palette.line)
    }
    Text(
        text,
        fontSize = 8.sp,
        lineHeight = 10.sp,
        letterSpacing = 0.6.sp,
        fontWeight = FontWeight.Bold,
        color = ink,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(fill)
            .border(1.dp, rim, RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}
