package com.harmony.playback.service.session

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.core.net.toUri
import com.harmony.core.media.artwork.ArtworkProvider
import com.harmony.core.model.Album
import com.harmony.core.model.Artist
import com.harmony.core.model.Song

/**
 * The Android Auto browse tree.
 *
 * Auto is a driving interface, so the shape is deliberately different from
 * the phone app's. Three rules drive every choice here:
 *
 *  - **Depth costs attention.** Anything more than two taps from the root is
 *    unusable at speed, so the top level is the four things people actually
 *    reach for, and every branch bottoms out in playable songs immediately.
 *  - **Start playing, don't browse.** Each list node is itself playable
 *    where that makes sense, so "Albums > Rumours" plays the album rather
 *    than making you pick a track.
 *  - **Shuffle first.** The single most common in-car action is "just play
 *    something", so it's the first item, not buried in a menu.
 *
 * Media ids are strings, so structure is encoded as "type/arg". Parsing is
 * centralised in [parse] rather than scattered through the callback.
 */
@OptIn(UnstableApi::class)
object BrowseTree {

    const val ROOT = "harmony_root"

    // ---- Android Auto content style -------------------------------------
    //
    // These extras are how a media app influences the car's own rendering —
    // the only presentation control the platform offers, since apps cannot
    // draw on the head unit at all.
    //
    // CATEGORY_GRID is the important one: Auto crops artwork to a CIRCLE for
    // category grid items. That is as close to the phone's record view as
    // the platform permits, and it suits album art, which is square and
    // survives a circular crop.
    //
    // Raw string keys rather than MediaConstants: these are the documented
    // wire format and stable across Media3 versions, whereas the constant
    // names have moved between releases.
    const val EXTRA_CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
    const val EXTRA_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    const val EXTRA_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"

    const val STYLE_LIST = 1
    const val STYLE_GRID = 2
    const val STYLE_CATEGORY_LIST = 3
    /** Circular artwork. */
    const val STYLE_CATEGORY_GRID = 4

    /** Marks a node whose children should render as circular tiles. */
    fun circularGridExtras(): Bundle = Bundle().apply {
        putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
        putInt(EXTRA_BROWSABLE_HINT, STYLE_CATEGORY_GRID)
        // Songs stay a list: a grid of identical circles gives no way to read
        // a track name at a glance, and track names are what you scan for.
        putInt(EXTRA_PLAYABLE_HINT, STYLE_LIST)
    }

    /** Marks a node whose children are rows — the default for song lists. */
    fun listExtras(): Bundle = Bundle().apply {
        putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
        putInt(EXTRA_BROWSABLE_HINT, STYLE_CATEGORY_LIST)
        putInt(EXTRA_PLAYABLE_HINT, STYLE_LIST)
    }

    // Top-level nodes.
    const val SHUFFLE_ALL = "shuffle_all"
    const val RECENT = "recent"
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val PLAYLISTS = "playlists"
    const val SONGS = "songs"

    // Prefixes for parameterised nodes.
    private const val ALBUM = "album"
    private const val ARTIST = "artist"
    private const val PLAYLIST = "playlist"
    private const val SMART = "smart"
    private const val LETTER = "letter"

    fun albumId(id: Long) = "$ALBUM/$id"
    fun artistId(name: String) = "$ARTIST/$name"
    fun playlistId(id: Long) = "$PLAYLIST/$id"
    fun smartId(type: String) = "$SMART/$type"
    /**
     * A song's media id is the BARE row id, with no "song/" prefix.
     *
     * This is not a style choice. PlaybackConnection — what the phone UI
     * binds to — caches songs keyed by `song.id.toString()` and resolves the
     * current track with `songCache[mediaItem.mediaId]`. Prefixing the id
     * makes every one of those lookups miss, so a track started from
     * Android Auto plays but shows as nothing playing in the app.
     *
     * The bare form is the older, established scheme; the browse tree is
     * what has to match it.
     */
    fun songId(id: Long) = id.toString()
    fun letterId(letter: String) = "$LETTER/$letter"

    sealed interface Node {
        data object Root : Node
        data object ShuffleAll : Node
        data object Recent : Node
        data object Albums : Node
        data object Artists : Node
        data object Playlists : Node
        data object Songs : Node
        data class OneLetter(val letter: String) : Node
        data class OneAlbum(val id: Long) : Node
        data class OneArtist(val name: String) : Node
        data class OnePlaylist(val id: Long) : Node
        data class OneSmart(val type: String) : Node
        data class OneSong(val id: Long) : Node
        data object Unknown : Node
    }

    fun parse(mediaId: String): Node {
        if (mediaId == ROOT) return Node.Root
        // substringAfter with a limit of one split: artist names contain
        // slashes ("AC/DC"), so only the FIRST separator is structural.
        // A bare number is a song — see [songId] for why songs carry no
        // prefix. Checked first so a numeric id can never be mistaken for a
        // prefixed node.
        mediaId.toLongOrNull()?.let { return Node.OneSong(it) }

        val type = mediaId.substringBefore('/')
        val arg = mediaId.substringAfter('/', "")
        return when (type) {
            SHUFFLE_ALL -> Node.ShuffleAll
            RECENT -> Node.Recent
            ALBUMS -> Node.Albums
            ARTISTS -> Node.Artists
            PLAYLISTS -> Node.Playlists
            SONGS -> Node.Songs
            LETTER -> if (arg.isNotEmpty()) Node.OneLetter(arg) else Node.Unknown
            ALBUM -> arg.toLongOrNull()?.let(Node::OneAlbum) ?: Node.Unknown
            ARTIST -> if (arg.isNotEmpty()) Node.OneArtist(arg) else Node.Unknown
            PLAYLIST -> arg.toLongOrNull()?.let(Node::OnePlaylist) ?: Node.Unknown
            SMART -> if (arg.isNotEmpty()) Node.OneSmart(arg) else Node.Unknown
            else -> Node.Unknown
        }
    }

    // ------------------------------------------------------------ item builders

    fun browsable(
        id: String,
        title: String,
        subtitle: String? = null,
        artworkUri: String? = null,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        playable: Boolean = false,
        extras: Bundle? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artworkUri?.toUri())
                .setExtras(extras)
                .setIsBrowsable(true)
                // Browsable AND playable: tapping the row plays it, the
                // chevron opens it. Auto renders both affordances, which is
                // what keeps "play this album" to a single tap.
                .setIsPlayable(playable)
                .setMediaType(mediaType)
                .build()
        )
        .build()

    /**
     * @param vinyl composite the cover onto a record. Used for the
     *   now-playing view, where the image is large enough for the record to
     *   read; list rows get the plain cover, which is legible at thumbnail
     *   size where a record's label would not be.
     */
    fun playable(
        song: Song,
        vinyl: Boolean = false,
        artworkBytes: ByteArray? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(songId(song.id))
        .setUri(song.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setExtras(com.harmony.playback.service.player.ReplayGainMetadata.from(song))
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                .setSubtitle(song.artist)
                // Through the provider, NOT song.artworkUri: that's a
                // file:// path into this app's private storage, which the
                // Auto host cannot read — which is why artwork came up
                // blank in the car.
                // Bytes when we have them, URI otherwise. Bytes need no
                // permission and no provider, so they're the reliable path
                // for the item that's actually playing; a URI is the only
                // sane option for a list, where bytes would exceed the
                // binder transaction limit.
                .setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .setArtworkUri(carArtwork(song, vinyl))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()

    /**
     * The artwork URI to hand the Auto host, which is a different process.
     *
     * Two sources, in order:
     *  1. art extracted from the file during scanning, served through
     *     [ArtworkProvider] — `song.artworkUri` itself is a file:// path
     *     into this app's private storage and is unreadable to the host;
     *  2. failing that, MediaStore's own album art, which is public and
     *     needs no provider. Plenty of libraries have covers sitting in
     *     MediaStore for tracks with nothing embedded, so this recovers
     *     art that would otherwise show as a blank tile.
     *
     * The MediaStore fallback can't be composited into a record — it's
     * served by the system, not by us — so those tracks show a plain
     * cover even in the now-playing view. A real cover beats a styled
     * placeholder.
     */
    private fun carArtwork(song: Song, vinyl: Boolean): Uri? = when {
        song.artworkUri != null ->
            if (vinyl) ArtworkProvider.vinylUri(song.id, song.artworkUri).toUri()
            else ArtworkProvider.artUri(song.id, song.artworkUri).toUri()
        song.albumId > 0 ->
            "content://media/external/audio/albumart/${song.albumId}".toUri()
        else -> null
    }

    fun albumItem(album: Album): MediaItem = browsable(
        id = albumId(album.id),
        title = album.name,
        subtitle = album.albumArtist ?: "${album.songCount} songs",
        // MediaStore's copy, not album.artworkUri: that's a file:// path
        // into private storage and the Auto host can't read it. MediaStore's
        // album art is public and keyed by the same album id.
        artworkUri = "content://media/external/audio/albumart/${album.id}",
        mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
        playable = true,
        // Its own children are tracks, so a list.
        extras = listExtras(),
    )

    fun artistItem(artist: Artist): MediaItem = browsable(
        id = artistId(artist.name),
        title = artist.name,
        subtitle = "${artist.songCount} songs",
        mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
        playable = true,
        extras = listExtras(),
    )
}
