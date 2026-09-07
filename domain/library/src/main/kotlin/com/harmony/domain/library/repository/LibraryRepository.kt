package com.harmony.domain.library.repository

import com.harmony.core.model.Album
import com.harmony.core.model.Artist
import com.harmony.core.model.Song
import kotlinx.coroutines.flow.Flow

/**
 * Read side of the library. Every observe* method is a cold Room flow that
 * re-emits on any relevant table change — this is what makes the whole UI
 * update automatically after a background scan without any event bus.
 */
interface LibraryRepository {
    fun observeSongs(): Flow<List<Song>>

    /**
     * Live library counts for the Home dashboard. Backed by COUNT(*) queries
     * rather than counting an observed list, so showing "1,306 songs" does
     * not cost a full table map on every library change.
     */
    fun observeSongCount(): Flow<Int>
    fun observeAlbumCount(): Flow<Int>
    fun observeArtistCount(): Flow<Int>

    /**
     * Tracks Harmony downloaded itself (Soulseek / SpotiFLAC), newest first.
     * Backed by the download_records provenance table, so this survives a
     * metadata rescan that rebuilds the song rows.
     */
    fun observeRecentDownloads(limit: Int): Flow<List<Song>>

    /** Paged variant for the 20k-song list; observeSongs stays for small consumers. */
    fun observeSongsPaged(): Flow<androidx.paging.PagingData<Song>>

    /** DB-side random pick honoring an exclusion set; never loads the table. */
    suspend fun randomSongId(exclude: Set<Long>): Long?
    fun observeAlbums(): Flow<List<Album>>
    fun observeArtists(): Flow<List<Artist>>
    fun observeSongsByAlbum(albumId: Long): Flow<List<Song>>
    fun observeSongsByArtist(artistName: String): Flow<List<Song>>
    fun observeSongsInFolder(folderPrefix: String): Flow<List<Song>>

    suspend fun songById(id: Long): Song?

    /** The song as its file's tags describe it, ignoring any user edits. */
    suspend fun originalSongById(id: Long): Song?
    suspend fun songsByIds(ids: List<Long>): List<Song>

    /** First-seen timestamps for Smart Shuffle's small new-library bonus. */
    suspend fun dateAddedByIds(ids: List<Long>): Map<Long, Long>

    /** Matches against title, artist, album, genre, composer, and folder path. */
    fun search(query: String): Flow<SearchResults>
}

data class SearchResults(
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
)
