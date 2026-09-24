package com.harmony.feature.library

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.harmony.core.model.Song
import com.harmony.core.ui.component.Artwork
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialSwipeToQueue
import com.harmony.core.ui.component.SwipeDownToDismissState
import com.harmony.core.ui.component.VinylAlbumCover
import com.harmony.core.ui.component.formatDuration
import com.harmony.core.ui.component.glassFill
import com.harmony.core.ui.component.glassRim
import com.harmony.core.ui.component.rememberDismissCallback
import com.harmony.core.ui.component.rememberSwipeDownToDismissState
import com.harmony.core.ui.component.swipeDownToDismiss

/** Another album by the same artist, for the "More by" shelf. */
@Immutable
internal data class OtherAlbum(
    val id: Long,
    val title: String,
    val year: Int?,
    val artworkUri: String?,
    val songCount: Int,
)

/** What the page shows. Null [songs] means "still loading"; empty means the album is gone. */
@Immutable
internal data class AlbumDetailUi(
    val songs: List<Song>?,
    val nowPlayingId: Long? = null,
    val isPlaying: Boolean = false,
    val moreByArtist: List<OtherAlbum> = emptyList(),
)

/** Everything the page can ask for. Plain lambdas so tests and previews can pass no-ops. */
@Immutable
internal class AlbumDetailActions(
    val onBack: () -> Unit = {},
    val onPlay: (list: List<Song>, index: Int) -> Unit = { _, _ -> },
    val onShuffle: (List<Song>) -> Unit = {},
    val onPlayNext: (Song) -> Unit = {},
    val onAddToPlaylist: (List<Long>) -> Unit = {},
    val onDelete: (List<Song>) -> Unit = {},
    val onPlayAlbumNext: (List<Song>) -> Unit = {},
    val onAddAlbumToQueue: (List<Song>) -> Unit = {},
    val onOpenArtist: (String) -> Unit = {},
    val onOpenAlbum: (Long) -> Unit = {},
)

internal object AlbumDetailTags {
    const val PAGE = "album_page"
    const val LIST = "album_list"
    const val HERO = "album_hero"
    const val COVER = "album_cover"
    const val TITLE = "album_title"
    const val ARTIST = "album_artist"
    const val QUALITY = "album_quality"
    const val TOP_BAR = "album_top_bar"
    const val COMPACT_TITLE = "album_compact_title"
    const val BACK = "album_back"
    const val MORE = "album_more"
    const val PLAY = "album_play"
    const val SHUFFLE = "album_shuffle"
    const val LOADING = "album_loading"
    const val MISSING = "album_missing"
    const val ABOUT = "album_about"
    const val MORE_BY = "album_more_by"
    fun row(id: Long) = "album_track_$id"
    fun gap(disc: Int?, from: Int) = "album_gap_${disc ?: 0}_$from"
    fun disc(disc: Int) = "album_disc_$disc"
    fun other(id: Long) = "album_other_$id"
}

private val PageRadius = 28.dp
private val PageInset = 10.dp
private val Side = 22.dp
private val NumberColumn = 40.dp

/**
 * Text in the accent colour. Harmony's amber is a fill colour: as text on the
 * near-white light field it is about 2:1, so in light mode it is pulled
 * towards the ink until it reads. Dark mode keeps the accent as is.
 */
@Composable
private fun accentText(palette: EditorialPalette): Color =
    if (palette.field.luminance() > 0.5f) lerp(palette.accent, palette.ink, 0.5f) else palette.accent

/**
 * Album detail: one glass page over the amber field, laid out like a record
 * sleeve's back cover.
 *
 * - The hero is the record itself, sleeve and disc; the disc turns while
 *   this album is playing.
 * - Title, a link to the artist, the running time and a quality line
 *   (lossless and resolution, from what the scanner read).
 * - The track list is numbered like the sleeve, split by disc, with a quiet
 *   line wherever the numbering says a track is missing from the library.
 *   Rows only mention an artist when it differs from the album's, and only
 *   mention quality when a track differs from the rest.
 * - "About this album" collects the release facts the tags provide, then
 *   a shelf of the artist's other albums.
 *
 * The top bar and the swipe-down-to-go-back gesture work exactly as on the
 * playlist page.
 */
@Composable
internal fun AlbumDetailContent(
    ui: AlbumDetailUi,
    palette: EditorialPalette,
    actions: AlbumDetailActions,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
    dismissState: SwipeDownToDismissState = rememberSwipeDownToDismissState(),
) {
    val onDismiss = rememberDismissCallback(actions.onBack)
    val requestBack: () -> Unit = { dismissState.requestDismiss(onDismiss) }

    val songs = ui.songs
    val ordered = remember(songs) { songs?.let(AlbumDetailFormat::ordered) }
    val headline = remember(songs) { songs?.takeIf { it.isNotEmpty() }?.let(AlbumDetailFormat::headline) }
    val sections = remember(songs) { songs?.let(AlbumDetailFormat::sections).orEmpty() }
    val quality = remember(songs) { AlbumDetailFormat.quality(songs.orEmpty()) }
    val details = remember(songs, headline, sections, quality) {
        if (songs.isNullOrEmpty() || headline == null) emptyList()
        else AlbumDetailFormat.details(songs, headline, sections, quality)
    }
    val albumPlaying = ui.nowPlayingId != null && songs?.any { it.id == ui.nowPlayingId } == true

    var heroHeight by remember { mutableIntStateOf(1) }
    var topBarHeight by remember { mutableIntStateOf(0) }
    val collapse by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else {
                val travel = (heroHeight - topBarHeight).coerceAtLeast(1)
                (listState.firstVisibleItemScrollOffset / (travel * 0.72f)).coerceIn(0f, 1f)
            }
        }
    }
    val atTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(palette.field)
            .onSizeChanged { dismissState.pageHeightPx = it.height.toFloat() }
            .testTag(AlbumDetailTags.PAGE),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val offset = dismissState.offsetPx
                    translationY = offset
                    val lift = if (size.height > 0f) (offset / (size.height * 0.5f)).coerceIn(0f, 1f) else 0f
                    val scale = 1f - 0.06f * lift
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    alpha = 1f - 0.25f * lift
                },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = PageInset)
                    .clip(RoundedCornerShape(topStart = PageRadius, topEnd = PageRadius))
                    .background(
                        Brush.verticalGradient(
                            listOf(glassFill(palette, 0.78f), glassFill(palette, 0.58f)),
                            endY = 1400f,
                        ),
                    )
                    .border(
                        1.dp,
                        Brush.verticalGradient(listOf(glassRim(palette), Color.Transparent), endY = 600f),
                        RoundedCornerShape(topStart = PageRadius, topEnd = PageRadius),
                    )
                    .testTag(AlbumDetailTags.LIST),
                contentPadding = PaddingValues(bottom = bottomPadding + 20.dp),
            ) {
                item(key = "hero", contentType = "hero") {
                    Column(Modifier.onSizeChanged { heroHeight = it.height.coerceAtLeast(1) }) {
                        Hero(
                            songs = songs,
                            headline = headline,
                            quality = quality,
                            palette = palette,
                            spinning = albumPlaying && ui.isPlaying,
                            topInset = with(LocalDensity.current) { topBarHeight.toDp() },
                            onOpenArtist = actions.onOpenArtist,
                            modifier = Modifier
                                .testTag(AlbumDetailTags.HERO)
                                .swipeDownToDismiss(dismissState, onDismiss, canStart = { atTop }),
                        )
                        if (songs == null || songs.isNotEmpty()) {
                            ActionRow(
                                enabled = !songs.isNullOrEmpty(),
                                palette = palette,
                                onPlay = { ordered?.takeIf { it.isNotEmpty() }?.let { actions.onPlay(it, 0) } },
                                onShuffle = { ordered?.takeIf { it.isNotEmpty() }?.let(actions.onShuffle) },
                            )
                            Spacer(Modifier.height(18.dp))
                        }
                    }
                }

                when {
                    songs == null -> items(count = 6, key = { "skeleton-$it" }, contentType = { "skeleton" }) { index ->
                        SkeletonTrack(
                            palette = palette,
                            widthFraction = 0.5f + (index % 3) * 0.14f,
                            modifier = if (index == 0) Modifier.testTag(AlbumDetailTags.LOADING) else Modifier,
                        )
                    }

                    songs.isEmpty() -> item(key = "missing") {
                        MissingPanel(palette = palette, onBack = requestBack)
                    }

                    else -> {
                        val all = ordered.orEmpty()
                        sections.forEach { section ->
                            section.disc?.let { disc ->
                                item(key = "disc-$disc", contentType = "disc") {
                                    DiscHeader(section, palette, Modifier.testTag(AlbumDetailTags.disc(disc)))
                                }
                            }
                            section.rows.forEach { row ->
                                when (row) {
                                    is AlbumRow.Track -> item(key = row.song.id, contentType = "track") {
                                        val song = row.song
                                        TrackRow(
                                            number = row.number,
                                            title = song.title,
                                            credit = headline?.let { AlbumDetailFormat.trackCredit(song, it) },
                                            note = AlbumDetailFormat.rowNote(song, quality),
                                            durationMs = song.durationMs,
                                            palette = palette,
                                            current = song.id == ui.nowPlayingId,
                                            playing = song.id == ui.nowPlayingId && ui.isPlaying,
                                            onClick = { actions.onPlay(all, all.indexOf(song).coerceAtLeast(0)) },
                                            onPlayNext = { actions.onPlayNext(song) },
                                            onAddToPlaylist = { actions.onAddToPlaylist(listOf(song.id)) },
                                            onDelete = { actions.onDelete(listOf(song)) },
                                            modifier = Modifier.testTag(AlbumDetailTags.row(song.id)),
                                        )
                                    }
                                    is AlbumRow.Missing -> item(key = "gap-${section.disc ?: 0}-${row.from}", contentType = "gap") {
                                        MissingTrackRow(row, palette, Modifier.testTag(AlbumDetailTags.gap(section.disc, row.from)))
                                    }
                                }
                            }
                        }
                        if (details.isNotEmpty()) {
                            item(key = "about", contentType = "about") {
                                AboutCard(details, palette, Modifier.testTag(AlbumDetailTags.ABOUT))
                            }
                        }
                        if (ui.moreByArtist.isNotEmpty() && headline?.artist != null) {
                            item(key = "more-by", contentType = "more-by") {
                                MoreByShelf(
                                    artist = headline.artist,
                                    albums = ui.moreByArtist,
                                    palette = palette,
                                    onOpenAlbum = actions.onOpenAlbum,
                                    onOpenArtist = headline.artistLink?.let { link -> { actions.onOpenArtist(link) } },
                                    modifier = Modifier.testTag(AlbumDetailTags.MORE_BY),
                                )
                            }
                        }
                    }
                }
            }

            TopBar(
                title = headline?.title.orEmpty(),
                palette = palette,
                collapse = { collapse },
                hasSongs = !songs.isNullOrEmpty(),
                artistLink = headline?.artistLink,
                onBack = requestBack,
                onPlayNext = { ordered?.let(actions.onPlayAlbumNext) },
                onAddToQueue = { ordered?.let(actions.onAddAlbumToQueue) },
                onAddToPlaylist = { ordered?.let { list -> actions.onAddToPlaylist(list.map { it.id }) } },
                onOpenArtist = { headline?.artistLink?.let(actions.onOpenArtist) },
                onDelete = { ordered?.let(actions.onDelete) },
                modifier = Modifier
                    .onSizeChanged { topBarHeight = it.height }
                    .swipeDownToDismiss(dismissState, onDismiss),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

@Composable
private fun TopBar(
    title: String,
    palette: EditorialPalette,
    collapse: () -> Float,
    hasSongs: Boolean,
    artistLink: String?,
    onBack: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenArtist: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val barFill = glassFill(palette, 0.86f).compositeOver(palette.field).copy(alpha = 0.97f)
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = PageInset)
            .clip(RoundedCornerShape(topStart = PageRadius, topEnd = PageRadius))
            .drawBehind {
                val a = collapse()
                if (a > 0f) {
                    drawRect(barFill.copy(alpha = barFill.alpha * a))
                    drawRect(
                        palette.line.copy(alpha = palette.line.alpha * a),
                        topLeft = Offset(0f, size.height - 1.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()),
                    )
                }
            }
            .testTag(AlbumDetailTags.TOP_BAR),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                palette = palette,
                onClick = onBack,
                modifier = Modifier.testTag(AlbumDetailTags.BACK),
            )
            Text(
                title,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .graphicsLayer { alpha = ((collapse() - 0.55f) / 0.45f).coerceIn(0f, 1f) }
                    .testTag(AlbumDetailTags.COMPACT_TITLE),
            )
            if (hasSongs) {
                var open by remember { mutableStateOf(false) }
                Box {
                    GlassIconButton(
                        icon = Icons.Rounded.MoreHoriz,
                        contentDescription = "More album actions",
                        palette = palette,
                        onClick = { open = true },
                        modifier = Modifier.testTag(AlbumDetailTags.MORE),
                    )
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        MenuItem("Play next", Icons.Rounded.SkipNext) { open = false; onPlayNext() }
                        MenuItem("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { open = false; onAddToQueue() }
                        MenuItem("Add to playlist", Icons.AutoMirrored.Rounded.PlaylistAdd) { open = false; onAddToPlaylist() }
                        if (artistLink != null) {
                            MenuItem("Go to artist", Icons.Rounded.Person) { open = false; onOpenArtist() }
                        }
                        MenuItem("Delete album from phone", Icons.Rounded.DeleteOutline, danger = true) { open = false; onDelete() }
                    }
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(label, color = tint) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint) },
        onClick = onClick,
    )
}

/** 48dp touch target, 42dp glass disc. */
@Composable
private fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    palette: EditorialPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(glassFill(palette, 0.9f))
                .border(1.dp, palette.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = palette.ink, modifier = Modifier.size(22.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Hero
// ---------------------------------------------------------------------------

@Composable
private fun Hero(
    songs: List<Song>?,
    headline: AlbumHeadline?,
    quality: AlbumQuality,
    palette: EditorialPalette,
    spinning: Boolean,
    topInset: Dp,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = topInset, start = Side, end = Side),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .drawBehind {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(palette.accent.copy(alpha = 0.22f), Color.Transparent),
                            center = center,
                            radius = size.height * 0.8f,
                        ),
                        radius = size.height * 0.8f,
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // At large font sizes the text below needs the height more than
            // the artwork does, so the record gives some of it back.
            val shrink = if (LocalDensity.current.fontScale > 1.3f) 0.8f else 1f
            val width = min(maxWidth * 0.94f, 360.dp) * shrink
            if (songs == null) {
                Box(Modifier.width(width).height(width * SLEEVE)) {
                    SkeletonBlock(palette, Modifier.size(width * SLEEVE), RoundedCornerShape(8.dp))
                }
            } else if (songs.isNotEmpty()) {
                HeroRecord(
                    artworkUri = songs.firstNotNullOfOrNull { it.artworkUri },
                    title = headline?.title,
                    palette = palette,
                    spinning = spinning,
                    modifier = Modifier
                        .width(width)
                        .height(width * SLEEVE)
                        .testTag(AlbumDetailTags.COVER),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        if (headline == null) {
            if (songs == null) {
                SkeletonBlock(palette, Modifier.width(90.dp).height(12.dp), RoundedCornerShape(6.dp))
                Spacer(Modifier.height(10.dp))
                SkeletonBlock(palette, Modifier.width(210.dp).height(28.dp), RoundedCornerShape(8.dp))
                Spacer(Modifier.height(10.dp))
                SkeletonBlock(palette, Modifier.width(120.dp).height(16.dp), RoundedCornerShape(6.dp))
            }
            Spacer(Modifier.height(20.dp))
            return@Column
        }

        Text(
            AlbumDetailFormat.eyebrow(headline),
            fontSize = 11.sp,
            letterSpacing = 1.8.sp,
            fontWeight = FontWeight.SemiBold,
            color = accentText(palette),
        )
        Spacer(Modifier.height(6.dp))
        val long = headline.title.length > 24
        Text(
            headline.title,
            fontSize = if (long) 24.sp else 29.sp,
            lineHeight = if (long) 29.sp else 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            color = palette.ink,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .semantics { heading() }
                .testTag(AlbumDetailTags.TITLE),
        )
        headline.artist?.let { artist ->
            ArtistLink(
                name = artist,
                palette = palette,
                onClick = headline.artistLink?.let { link -> { onOpenArtist(link) } },
                modifier = Modifier.testTag(AlbumDetailTags.ARTIST),
            )
        }
        Text(
            AlbumDetailFormat.summary(songs.orEmpty(), headline),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            color = palette.muted,
            textAlign = TextAlign.Center,
        )
        if (quality.label != null || quality.lossless == Lossless.ALL) {
            Spacer(Modifier.height(12.dp))
            QualityPill(quality, palette, Modifier.testTag(AlbumDetailTags.QUALITY))
        }
        Spacer(Modifier.height(22.dp))
    }
}

/** The artist under the title; a link when there is an artist page to open. */
@Composable
private fun ArtistLink(name: String, palette: EditorialPalette, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Open artist", role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (onClick != null) accentText(palette) else palette.ink,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onClick != null) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = accentText(palette),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * "LOSSLESS · FLAC · 24-bit / 96 kHz", "Mixed quality" or "320 kbps", as
 * one quiet capsule. One text run, so at large font sizes it wraps onto a
 * second line instead of cutting the resolution off.
 */
@Composable
private fun QualityPill(quality: AlbumQuality, palette: EditorialPalette, modifier: Modifier = Modifier) {
    val accent = accentText(palette)
    val text = buildAnnotatedString {
        if (quality.lossless == Lossless.ALL) {
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.1.sp)) {
                append("LOSSLESS")
            }
            if (quality.label != null) append("  ·  ")
        }
        quality.label?.let(::append)
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(palette.accent.copy(alpha = 0.14f))
            .border(1.dp, palette.accent.copy(alpha = 0.28f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
        Text(
            text,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            color = palette.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false),
        )
    }
}

/** Sleeve height as a fraction of the record's full width (sleeve plus the disc showing). */
private const val SLEEVE = 0.74f
private val VINYL = Color(0xFF17151C)

/**
 * The album as a record: the sleeve, with the disc drawn part-way out.
 * While the album plays the disc slides a little further out and turns,
 * the way the player shows it; paused, it rests.
 */
@Composable
private fun HeroRecord(
    artworkUri: String?,
    title: String?,
    palette: EditorialPalette,
    spinning: Boolean,
    modifier: Modifier = Modifier,
) {
    val reveal by animateFloatAsState(if (spinning) 1f else 0f, tween(600), label = "record-reveal")
    // One turn every 7 s. Driven frame by frame rather than as a repeating
    // animation so pausing leaves the disc where it stopped, and through the
    // infinite-animation clock so tests and battery saver can suspend it.
    val angle = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(spinning) {
        if (!spinning) return@LaunchedEffect
        var last = -1L
        while (true) {
            withInfiniteAnimationFrameMillis { now ->
                if (last >= 0) angle.floatValue = (angle.floatValue + (now - last) * 360f / 7_000f) % 360f
                last = now
            }
        }
    }
    BoxWithConstraints(modifier) {
        val sleeve = maxHeight
        val disc = sleeve * 0.94f
        // Pinned to the right edge while playing, tucked in a little at rest.
        val slide = (maxWidth - disc) - maxWidth * 0.06f * (1f - reveal)
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = slide)
                .size(disc)
                .graphicsLayer { rotationZ = angle.floatValue },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                drawCircle(color = VINYL, radius = r, center = c)
                val grooves = 18
                for (i in 0 until grooves) {
                    val t = i / (grooves - 1f)
                    drawCircle(
                        color = Color.White.copy(alpha = 0.09f),
                        radius = r * (0.42f + 0.55f * t * t),
                        center = c,
                        style = Stroke(width = r * 0.009f),
                    )
                }
                // A sheen that stays put while the disc turns under it.
                rotate(-angle.floatValue, c) {
                    drawCircle(
                        brush = Brush.sweepGradient(
                            0f to Color.Transparent,
                            0.12f to Color.White.copy(alpha = 0.10f),
                            0.25f to Color.Transparent,
                            0.62f to Color.Transparent,
                            0.75f to Color.White.copy(alpha = 0.06f),
                            0.88f to Color.Transparent,
                            center = c,
                        ),
                        radius = r,
                        center = c,
                    )
                }
            }
            val label = disc * 0.36f
            Artwork(
                artworkUri = artworkUri,
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center).size(label),
                cornerRadius = label / 2,
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(label)
                    .clip(CircleShape)
                    .background(VINYL.copy(alpha = 0.22f)),
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(disc * 0.045f)
                    .clip(CircleShape)
                    .background(palette.field),
            )
        }
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .size(sleeve)
                .shadow(18.dp, RoundedCornerShape(10.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .border(1.dp, palette.line, RoundedCornerShape(10.dp)),
        ) {
            Artwork(
                artworkUri = artworkUri,
                contentDescription = title?.let { "Cover of $it" },
                modifier = Modifier.fillMaxSize().padding(1.dp),
                cornerRadius = 9.dp,
            )
            // Printed-card sheen along the top edge of the sleeve.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(1.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.16f),
                            0.18f to Color.Transparent,
                        ),
                    ),
            )
        }
    }
}

/** Play and Shuffle side by side while both labels fit; stacked at large font sizes. */
@Composable
private fun ActionRow(enabled: Boolean, palette: EditorialPalette, onPlay: () -> Unit, onShuffle: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Side)) {
        val fontScale = LocalDensity.current.fontScale
        val needed = 12.dp + (52.dp + 60.dp * fontScale) * 2
        if (maxWidth >= needed) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayButton(enabled, palette, onPlay, Modifier.weight(1f))
                ShuffleButton(enabled, palette, onShuffle, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PlayButton(enabled, palette, onPlay, Modifier.fillMaxWidth())
                ShuffleButton(enabled, palette, onShuffle, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PlayButton(enabled: Boolean, palette: EditorialPalette, onClick: () -> Unit, modifier: Modifier) {
    ActionButton(
        label = "Play",
        icon = Icons.Rounded.PlayArrow,
        container = palette.accent,
        content = palette.onAccent,
        rim = null,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
            .shadow(if (enabled) 10.dp else 0.dp, RoundedCornerShape(50), spotColor = palette.accent, ambientColor = palette.accent)
            .testTag(AlbumDetailTags.PLAY),
    )
}

@Composable
private fun ShuffleButton(enabled: Boolean, palette: EditorialPalette, onClick: () -> Unit, modifier: Modifier) {
    ActionButton(
        label = "Shuffle",
        icon = Icons.Rounded.Shuffle,
        container = palette.accent.copy(alpha = 0.14f).compositeOver(glassFill(palette, 0.9f)),
        content = palette.ink,
        rim = glassRim(palette),
        enabled = enabled,
        onClick = onClick,
        modifier = modifier.testTag(AlbumDetailTags.SHUFFLE),
    )
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    rim: Color?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(container.copy(alpha = if (enabled) container.alpha else container.alpha * 0.45f))
            .then(if (rim != null) Modifier.border(1.dp, rim, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) content else content.copy(alpha = 0.5f)
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(
            label,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Track list
// ---------------------------------------------------------------------------

@Composable
private fun DiscHeader(section: DiscSection, palette: EditorialPalette, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = Side, end = Side, top = 18.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Album, contentDescription = null, tint = accentText(palette), modifier = Modifier.size(18.dp))
        Text(
            "DISC ${section.disc}",
            fontSize = 12.sp,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.Bold,
            color = accentText(palette),
            modifier = Modifier.padding(start = 8.dp),
        )
        Text(
            "  ·  " + AlbumDetailFormat.discLabel(section.copy(disc = null)),
            fontSize = 12.sp,
            color = palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * One track: its number (or a moving level meter while it plays), title,
 * the artist only when it isn't the album's, a note only when its quality
 * differs from the rest, and the duration.
 */
@Composable
private fun TrackRow(
    number: Int,
    title: String,
    credit: String?,
    note: String?,
    durationMs: Long,
    palette: EditorialPalette,
    current: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = accentText(palette)
    val restFill = glassFill(palette, 0.7f).compositeOver(palette.field)
    val fill = if (current) palette.accent.copy(alpha = 0.14f).compositeOver(restFill) else restFill
    val shape = RoundedCornerShape(16.dp)
    EditorialSwipeToQueue(
        palette = palette,
        onQueue = onPlayNext,
        icon = Icons.AutoMirrored.Rounded.QueueMusic,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(fill)
                .clickable(onClickLabel = "Play", onClick = onClick)
                .heightIn(min = 60.dp)
                .padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(NumberColumn), contentAlignment = Alignment.Center) {
                if (current) {
                    LevelMeter(active = playing, color = accent, modifier = Modifier.semantics { contentDescription = if (playing) "Now playing" else "Paused" })
                } else {
                    Text(
                        number.toString(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.muted,
                        textAlign = TextAlign.Center,
                        style = LocalTabularNums,
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
                Text(
                    title,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (current) accent else palette.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (credit != null || note != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        if (credit != null) {
                            Text(
                                credit,
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                                color = palette.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (note != null) {
                            Text(
                                note,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = accent,
                                maxLines = 1,
                                modifier = Modifier
                                    .padding(start = if (credit != null) 8.dp else 0.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(palette.accent.copy(alpha = 0.14f))
                                    .padding(horizontal = 7.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
            Text(
                formatDuration(durationMs),
                fontSize = 13.sp,
                color = palette.muted,
                maxLines = 1,
                style = LocalTabularNums,
            )
            TrackMenu(title, palette, onPlayNext, onAddToPlaylist, onDelete)
        }
    }
}

private val LocalTabularNums = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")

@Composable
private fun TrackMenu(
    title: String,
    palette: EditorialPalette,
    onPlayNext: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = { open = true })
                .semantics { contentDescription = "More options for $title" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = palette.muted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem("Play next", Icons.Rounded.SkipNext) { open = false; onPlayNext() }
            MenuItem("Add to playlist", Icons.AutoMirrored.Rounded.PlaylistAdd) { open = false; onAddToPlaylist() }
            MenuItem("Delete from phone", Icons.Rounded.DeleteOutline, danger = true) { open = false; onDelete() }
        }
    }
}

/** Three bars that move while the track plays and rest, lowered, while it is paused. */
@Composable
private fun LevelMeter(active: Boolean, color: Color, modifier: Modifier = Modifier) {
    if (active) {
        val transition = rememberInfiniteTransition(label = "meter")
        val a by transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "bar-a")
        val b by transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(520, delayMillis = 180, easing = LinearEasing), RepeatMode.Reverse), label = "bar-b")
        val c by transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(520, delayMillis = 360, easing = LinearEasing), RepeatMode.Reverse), label = "bar-c")
        MeterBars({ listOf(a, b, c) }, color, modifier)
    } else {
        MeterBars({ PausedBars }, color, modifier)
    }
}

private val PausedBars = listOf(0.45f, 0.75f, 0.55f)

@Composable
private fun MeterBars(heights: () -> List<Float>, color: Color, modifier: Modifier) {
    Canvas(modifier.size(18.dp)) {
        val barW = size.width / 5f
        heights().forEachIndexed { i, f ->
            val h = size.height * f
            drawRoundRect(
                color = color,
                topLeft = Offset(barW * (i * 2f), size.height - h),
                size = androidx.compose.ui.geometry.Size(barW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f),
            )
        }
    }
}

/** A quiet line where the numbering says tracks exist that the library doesn't have. */
@Composable
private fun MissingTrackRow(row: AlbumRow.Missing, palette: EditorialPalette, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .heightIn(min = 40.dp)
            .drawBehind {
                // Dashed outline: present in the numbering, absent from the phone.
                drawRoundRect(
                    color = palette.line,
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                )
            }
            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (row.from == row.to) "${row.from}" else "${row.from}–${row.to}",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = palette.muted.copy(alpha = palette.muted.alpha * 0.8f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            style = LocalTabularNums,
            modifier = Modifier.width(NumberColumn),
        )
        Text(
            AlbumDetailFormat.missingShort(row),
            fontSize = 13.sp,
            lineHeight = 17.sp,
            color = palette.muted,
            modifier = Modifier.padding(start = 4.dp).semantics { contentDescription = AlbumDetailFormat.missingLabel(row) },
        )
    }
}

// ---------------------------------------------------------------------------
// About and more by the artist
// ---------------------------------------------------------------------------

@Composable
private fun AboutCard(details: List<Pair<String, String>>, palette: EditorialPalette, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 26.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(glassFill(palette, 0.9f).compositeOver(palette.field))
            .border(1.dp, palette.line, RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            "About this album",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = palette.ink,
            modifier = Modifier.semantics { heading() }.padding(bottom = 6.dp),
        )
        details.forEachIndexed { index, (label, value) ->
            if (index > 0) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line.copy(alpha = palette.line.alpha * 0.7f)))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = palette.muted,
                    modifier = Modifier.widthIn(min = 104.dp, max = 132.dp).padding(end = 12.dp),
                )
                Text(
                    value,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.ink,
                    style = LocalTabularNums,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MoreByShelf(
    artist: String,
    albums: List<OtherAlbum>,
    palette: EditorialPalette,
    onOpenAlbum: (Long) -> Unit,
    onOpenArtist: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(top = 26.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = Side, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "More by $artist",
                fontSize = 17.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold,
                color = palette.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (onOpenArtist != null) {
                Text(
                    "See artist",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentText(palette),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(role = Role.Button, onClick = onOpenArtist)
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            items(albums, key = { it.id }) { album ->
                Column(
                    Modifier
                        .width(148.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClickLabel = "Open album", onClick = { onOpenAlbum(album.id) })
                        .padding(6.dp)
                        .testTag(AlbumDetailTags.other(album.id)),
                ) {
                    VinylAlbumCover(
                        artworkUri = album.artworkUri,
                        albumName = album.title,
                        palette = palette,
                        modifier = Modifier.width(136.dp).height(104.dp),
                    )
                    Text(
                        album.title,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        listOfNotNull(album.year?.toString(), AlbumDetailFormat.songCount(album.songCount)).joinToString("  ·  "),
                        fontSize = 12.sp,
                        color = palette.muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// States
// ---------------------------------------------------------------------------

@Composable
private fun MissingPanel(palette: EditorialPalette, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 28.dp)
            .testTag(AlbumDetailTags.MISSING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(palette.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Album, contentDescription = null, tint = accentText(palette), modifier = Modifier.size(30.dp))
        }
        Text(
            "This album isn't in your library",
            fontSize = 19.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Bold,
            color = palette.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Its songs were deleted or moved, or a rescan grouped them differently.",
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = palette.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(18.dp))
        ActionButton(
            label = "Go back",
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            container = palette.accent,
            content = palette.onAccent,
            rim = null,
            enabled = true,
            onClick = onBack,
            modifier = Modifier.widthIn(min = 160.dp),
        )
    }
}

@Composable
private fun SkeletonTrack(palette: EditorialPalette, widthFraction: Float, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBlock(palette, Modifier.size(18.dp), RoundedCornerShape(5.dp))
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            SkeletonBlock(palette, Modifier.fillMaxWidth(widthFraction).height(14.dp), RoundedCornerShape(6.dp))
        }
        SkeletonBlock(palette, Modifier.width(34.dp).height(12.dp), RoundedCornerShape(6.dp))
    }
}

@Composable
private fun SkeletonBlock(palette: EditorialPalette, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape) {
    val pulse = rememberInfiniteTransition(label = "skeleton")
    val alpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "skeleton-alpha",
    )
    Box(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .clip(shape)
            .background(palette.line.copy(alpha = 0.55f)),
    )
}
