package com.harmony.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.harmony.core.database.entity.AlbumEntity
import com.harmony.core.database.entity.ArtistEntity
import com.harmony.core.database.entity.SongEntity
import kotlinx.coroutines.flow.Flow

/**
 * SQLite binds at most 999 variables per statement by default, and a full
 * library exceeds that in a single IN clause. Top-level rather than in a
 * companion object: SongDao is an interface, and a private companion there is
 * needless friction for one constant.
 */
private const val SQLITE_VARIABLE_CHUNK = 900

// SQLite's built-in NOCASE/LIKE only folds ASCII case; it does not consider
// Romanian diacritics equivalent. These expressions mirror
// SearchTextNormalizer.foldForSearch for persisted metadata, including the
// older cedilla spellings still found in some tags.
private const val FOLDED_SONG_TITLE = "LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(title, 'ă', 'a'), 'Ă', 'a'), 'â', 'a'), 'Â', 'a'), 'î', 'i'), 'Î', 'i'), 'ș', 's'), 'Ș', 's'), 'ş', 's'), 'Ş', 's'), 'ț', 't'), 'Ț', 't'), 'ţ', 't'), 'Ţ', 't'))"
private const val FOLDED_SONG_ARTIST = "LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(artist, 'ă', 'a'), 'Ă', 'a'), 'â', 'a'), 'Â', 'a'), 'î', 'i'), 'Î', 'i'), 'ș', 's'), 'Ș', 's'), 'ş', 's'), 'Ş', 's'), 'ț', 't'), 'Ț', 't'), 'ţ', 't'), 'Ţ', 't'))"
private const val FOLDED_SONG_ALBUM = "LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(album, 'ă', 'a'), 'Ă', 'a'), 'â', 'a'), 'Â', 'a'), 'î', 'i'), 'Î', 'i'), 'ș', 's'), 'Ș', 's'), 'ş', 's'), 'Ş', 's'), 'ț', 't'), 'Ț', 't'), 'ţ', 't'), 'Ţ', 't'))"

// The ESCAPE clauses matter: :q is raw user input, so without them a typed
// '%' matches every song in the library and a typed '_' matches any single
// character. LibraryRepositoryImpl escapes both, plus the backslash itself,
// before binding. This is a plain (escaped) Kotlin string literal, so the
// four source characters below are the ONE backslash SQLite needs: its
// ESCAPE argument must be a single character or the query throws.
private const val LIKE_ESCAPE = " ESCAPE '\\' "

private const val DIACRITIC_INSENSITIVE_SONG_SEARCH =
    "SELECT * FROM songs_effective " +
        "WHERE " + FOLDED_SONG_TITLE + " LIKE '%' || :q || '%'" + LIKE_ESCAPE +
        "OR " + FOLDED_SONG_ARTIST + " LIKE '%' || :q || '%'" + LIKE_ESCAPE +
        "OR " + FOLDED_SONG_ALBUM + " LIKE '%' || :q || '%'" + LIKE_ESCAPE +
        "ORDER BY CASE WHEN " + FOLDED_SONG_TITLE + " LIKE :q || '%'" + LIKE_ESCAPE + "THEN 0 ELSE 1 END, " +
        "title COLLATE NOCASE LIMIT :limit"

@Dao
interface SongDao {

    // -- Reads ---------------------------------------------------------------

    @Query("SELECT * FROM songs_effective ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<SongEntity>>

    /**
     * Counts for the Home dashboard.
     *
     * A COUNT(*) rather than observeAll().size: the dashboard only needs the
     * number, and mapping 20k rows into domain objects on every library
     * change to then throw all of them away is exactly the "repeated library
     * scan" the dashboard is supposed to avoid. Room re-emits these on any
     * write to the underlying tables, so the tiles still update themselves.
     */
    @Query("SELECT COUNT(*) FROM songs")
    fun observeSongCount(): Flow<Int>

    /**
     * Recently Added, ordered by when Harmony actually gained the track.
     *
     * The scan mapper stores dateAdded as System.currentTimeMillis() and the
     * scanner preserves it for songs it has seen before, so it means "first
     * seen". download_records.downloadedAt is in the same millisecond units,
     * which is what lets MAX() compare them directly.
     *
     * The LEFT JOIN is what connects downloads to this list: a track fetched
     * from Soulseek or SpotiFLAC is ranked by the moment the download engine
     * finished it, rather than waiting on whenever the library scan happened
     * to notice the file. Songs with no download record fall back to
     * dateAdded through the COALESCE, so nothing is excluded.
     */
    @Query(
        """
        SELECT s.* FROM songs_effective s
        LEFT JOIN download_records d ON d.uri = s.uri
        ORDER BY MAX(s.dateAdded, COALESCE(d.downloadedAt, 0)) DESC
        LIMIT :limit
        """
    )
    fun observeRecentlyAdded(limit: Int): Flow<List<SongEntity>>

    /**
     * Only tracks Harmony downloaded itself, newest first.
     *
     * An INNER JOIN, so this is exactly the set with provenance in
     * download_records — the Soulseek and SpotiFLAC arrivals.
     */
    @Query(
        """
        SELECT s.* FROM songs_effective s
        JOIN download_records d ON d.uri = s.uri
        ORDER BY d.downloadedAt DESC
        LIMIT :limit
        """
    )
    fun observeRecentDownloads(limit: Int): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs_effective WHERE albumId = :albumId ORDER BY discNumber, trackNumber")
    fun observeByAlbum(albumId: Long): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs_effective WHERE artist = :artistName OR albumArtist = :artistName ORDER BY album, discNumber, trackNumber")
    fun observeByArtist(artistName: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE uri LIKE :folderPrefix || '%' ORDER BY uri")
    fun observeInFolder(folderPrefix: String): Flow<List<SongEntity>>

    /**
     * The song as the FILE describes it, with no user edits applied.
     *
     * Needed by the tag editor: comparing a typed value against the
     * effective title (which already includes the edit) makes a second save
     * look like "no change" and delete the override.
     */
    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun rawById(id: Long): SongEntity?

    @Query("SELECT * FROM songs_effective WHERE id = :id")
    suspend fun byId(id: Long): SongEntity?

    @Query("SELECT * FROM songs_effective WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<SongEntity>

    /**
     * FTS-backed search. :match must be a sanitized MATCH expression
     * (see FtsQuery.sanitize in :data:library) — raw user input contains
     * MATCH syntax characters that would throw.
     */
    @Query(
        """
        SELECT s.* FROM songs_effective s
        JOIN songs_fts f ON s.id = f.rowid
        WHERE songs_fts MATCH :match
        ORDER BY s.title COLLATE NOCASE
        LIMIT :limit
        """
    )
    fun search(match: String, limit: Int): Flow<List<SongEntity>>

    /**
     * Substring search across the same columns FTS indexes.
     *
     * FTS4 tokenises on word boundaries, so MATCH only ever finds token
     * PREFIXES: "eatles" will never reach "Beatles", and "alori" will never
     * reach "Calorifer". That's a fine trade for a fast primary index but a
     * poor one for a person typing a fragment they half-remember, so this
     * runs alongside it and catches the infixes.
     *
     * LIKE '%q%' can't use an index and is a full scan, which is exactly why
     * FTS exists here. It stays acceptable because the caller only merges it
     * in behind the FTS hits and caps the result — the scan is bounded work
     * on a debounced keystroke, not per character.
     *
     * Ordering puts titles that START with the query first: someone typing
     * "love" wants "Love Story" above "Cheap Thrills (Love Mix)".
     */
    @Query(DIACRITIC_INSENSITIVE_SONG_SEARCH)
    fun searchLike(q: String, limit: Int): Flow<List<SongEntity>>

    // The view, so a renamed song shows its new name in the paged list too.
    @Query("SELECT * FROM songs_effective ORDER BY title COLLATE NOCASE")
    fun pagingSource(): androidx.paging.PagingSource<Int, SongEntity>

    /** Small random sample for the shuffle fallback path — replaces loading the whole table. */
    @Query("SELECT id FROM songs ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomIds(limit: Int): List<Long>

    // -- Scan bookkeeping ----------------------------------------------------

    @Query("SELECT uri, fileSizeBytes, lastModified FROM songs")
    suspend fun scanKeys(): List<ScanKeyRow>

    data class ScanKeyRow(val uri: String, val fileSizeBytes: Long, val lastModified: Long)

    @Query("SELECT id, fileHash FROM songs")
    suspend fun allIdsAndHashes(): List<IdHashRow>

    data class IdHashRow(val id: Long, val fileHash: String)

    // -- Writes --------------------------------------------------------------

    // @Upsert, NOT @Insert(REPLACE). REPLACE is implemented as DELETE then
    // INSERT, and both playlist_songs and favorites cascade from songs — so
    // re-scanning a song used to silently drop it from every playlist and
    // from favourites. @Upsert updates the row in place, leaving the
    // referencing rows alone.
    @Upsert
    suspend fun upsertSongs(songs: List<SongEntity>)

    @Upsert
    suspend fun upsertAlbums(albums: List<AlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtists(artists: List<ArtistEntity>)

    @Query("DELETE FROM songs WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    @Query("DELETE FROM download_records WHERE uri IN (:uris)")
    suspend fun deleteDownloadRecords(uris: List<String>)

    @Transaction
    suspend fun deleteFilesAndPrune(uris: List<String>) {
        uris.chunked(400).forEach { chunk ->
            deleteByUris(chunk)
            deleteDownloadRecords(chunk)
        }
        pruneEmptyAlbums()
        pruneEmptyArtists()
    }

    /** Remove album/artist rows that no longer have any songs. */
    @Query("DELETE FROM albums WHERE id NOT IN (SELECT DISTINCT albumId FROM songs)")
    suspend fun pruneEmptyAlbums()

    @Query("DELETE FROM artists WHERE name NOT IN (SELECT DISTINCT artist FROM songs) AND name NOT IN (SELECT DISTINCT albumArtist FROM songs WHERE albumArtist IS NOT NULL)")
    suspend fun pruneEmptyArtists()

    data class IdDateRow(val id: Long, val dateAdded: Long)

    @Query("SELECT id, dateAdded FROM songs WHERE id IN (:ids)")
    suspend fun existingDateAdded(ids: List<Long>): List<IdDateRow>

    @Transaction
    suspend fun upsertBatch(
        songs: List<SongEntity>,
        albums: List<AlbumEntity>,
        artists: List<ArtistEntity>,
    ) {
        upsertAlbums(albums)
        insertArtists(artists)

        // Preserve dateAdded for songs already in the library.
        //
        // The scan mapper sets dateAdded = now for every track it produces,
        // because at that point it has no idea whether the song is new. @Upsert
        // then updates the row in place, so a rescan stamped EVERY song with the
        // current time and "Recently Added" collapsed to "whatever order the
        // scanner happened to emit". Only genuinely new rows should get today's
        // date; an existing row keeps the moment it first appeared.
        //
        // Chunked because SQLite caps bound variables at 999 by default and a
        // full library exceeds that in one IN clause.
        //
        // List operations, not Sequence: Sequence.flatMap takes a plain
        // non-inline lambda, so calling the suspend existingDateAdded inside it
        // does not compile. List.flatMap IS inline, which is what makes a
        // suspend call legal in the lambda body.
        val firstSeen = songs.map { it.id }
            .chunked(SQLITE_VARIABLE_CHUNK)
            .flatMap { chunk -> existingDateAdded(chunk) }
            .associate { it.id to it.dateAdded }

        upsertSongs(
            if (firstSeen.isEmpty()) {
                songs
            } else {
                songs.map { song ->
                    firstSeen[song.id]?.let { song.copy(dateAdded = it) } ?: song
                }
            },
        )
    }
}
