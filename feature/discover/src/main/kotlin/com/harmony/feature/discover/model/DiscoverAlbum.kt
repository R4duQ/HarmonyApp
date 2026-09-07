package com.harmony.feature.discover.model

import com.harmony.core.model.Song

enum class AlbumGenre(val label: String) {
    ROCK("Rock"), ELECTRONIC("Electronic"), HIP_HOP("Hip-hop"), SOUL_POP("Soul"),
    POP("Pop"), INDIE("Indie & alternative"), RNB("R&B"), FUNK("Funk"),
    JAZZ("Jazz"), METAL("Metal"), FOLK("Folk"), COUNTRY("Country"),
    BLUES("Blues"), REGGAE("Reggae & dub"), LATIN("Latin"), AFRICAN("African"),
    WORLD("Worldwide"), CLASSICAL("Classical"), AMBIENT("Ambient"),
}

enum class AlbumShelf(val label: String) {
    FOR_YOU("For you"), ALL("All albums"), SAVED("Saved"),
}

/** A verified public SHFL album link with Harmony's own short listening note. */
data class DiscoverAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val year: Int,
    val genres: Set<AlbumGenre>,
    val entryTracks: List<String>,
    val listeningNote: String,
    val coverUrl: String,
    val color: Long,
    val artistAliases: Set<String> = emptySet(),
    val liveRecording: Boolean = false,
) {
    val shflUrl: String get() = "https://theshfl.com/album/$id"
}

data class AlbumLibraryMatch(
    /** Only tracks with matching album AND artist tags, in disc/track order. */
    val albumSongs: List<Song> = emptyList(),
    /** May be an entry track from a compilation; never pretends to be the album. */
    val entrySong: Song? = null,
    val artistInLibrary: Boolean = false,
)

data class DiscoveryPreferences(
    val saved: Set<String> = emptySet(),
    val familiar: Set<String> = emptySet(),
    val listened: Set<String> = emptySet(),
    val likedSongs: Set<String> = emptySet(),
    val dislikedSongs: Set<String> = emptySet(),
    val round: SwipeRound = SwipeRound(),
    val recommendedAlbums: Set<String> = emptySet(),
)

/** A reveal stays fixed until the listener starts another round or undoes its last vote. */
data class SwipeRound(
    val number: Int = 1,
    val songIds: List<String> = emptyList(),
    val completed: Boolean = false,
    val albumId: String? = null,
) {
    companion object { const val SIZE = 10 }
}

data class ExplainedAlbum(
    val album: DiscoverAlbum,
    val reason: String,
    val supportingSongs: List<TasteSong>,
    val exploratory: Boolean,
)

/** Song choices are separate from album bookmarks and local library favorites. */
data class TasteSong(val album: DiscoverAlbum, val title: String, val index: Int) {
    val id: String get() = "${album.id}:$index"
}

data class AlbumSuggestion(
    val album: DiscoverAlbum,
    val match: AlbumLibraryMatch = AlbumLibraryMatch(),
    val saved: Boolean = false,
    val familiar: Boolean = false,
    val listened: Boolean = false,
) {
    val startingSong: Song? get() = match.entrySong ?: match.albumSongs.firstOrNull()
    val startingTitle: String get() = startingSong?.title ?: album.entryTracks.first()
}
