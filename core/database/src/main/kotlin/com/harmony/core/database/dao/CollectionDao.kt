package com.harmony.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

private const val FOLDED_ARTIST_NAME = "LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(names.name, 'ă', 'a'), 'Ă', 'a'), 'â', 'a'), 'Â', 'a'), 'î', 'i'), 'Î', 'i'), 'ș', 's'), 'Ș', 's'), 'ş', 's'), 'Ş', 's'), 'ț', 't'), 'Ț', 't'), 'ţ', 't'), 'Ţ', 't'))"
private const val FOLDED_ALBUM_NAME = "LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(a.name, 'ă', 'a'), 'Ă', 'a'), 'â', 'a'), 'Â', 'a'), 'î', 'i'), 'Î', 'i'), 'ș', 's'), 'Ș', 's'), 'ş', 's'), 'Ş', 's'), 'ț', 't'), 'Ț', 't'), 'ţ', 't'), 'Ţ', 't'))"
private const val FOLDED_ALBUM_ARTIST = "LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(a.albumArtist, 'ă', 'a'), 'Ă', 'a'), 'â', 'a'), 'Â', 'a'), 'î', 'i'), 'Î', 'i'), 'ș', 's'), 'Ș', 's'), 'ş', 's'), 'Ş', 's'), 'ț', 't'), 'Ț', 't'), 'ţ', 't'), 'Ţ', 't'))"

private const val DIACRITIC_INSENSITIVE_ARTIST_SEARCH =
    """
        SELECT MIN(s.id) AS id, names.name AS name,
               COUNT(DISTINCT s.albumId) AS albumCount,
               COUNT(DISTINCT s.id) AS songCount
        FROM (
            SELECT DISTINCT TRIM(artist) AS name
            FROM songs_effective
            WHERE TRIM(artist) <> ''
            UNION
            SELECT DISTINCT TRIM(albumArtist) AS name
            FROM songs_effective
            WHERE albumArtist IS NOT NULL AND TRIM(albumArtist) <> ''
        ) AS names
        JOIN songs_effective s
          ON TRIM(s.artist) = names.name OR TRIM(COALESCE(s.albumArtist, '')) = names.name
        WHERE """ + FOLDED_ARTIST_NAME + """ LIKE '%' || :q || '%' ESCAPE '\'
        GROUP BY names.name
        ORDER BY names.name COLLATE NOCASE
        LIMIT :limit
    """

private const val DIACRITIC_INSENSITIVE_ALBUM_SEARCH =
    """
        SELECT a.id, a.name, a.albumArtist, a.year, a.artworkUri, COUNT(s.id) AS songCount
        FROM albums a JOIN songs s ON s.albumId = a.id
        WHERE """ + FOLDED_ALBUM_NAME + """ LIKE '%' || :q || '%' ESCAPE '\'
           OR """ + FOLDED_ALBUM_ARTIST + """ LIKE '%' || :q || '%' ESCAPE '\'
        GROUP BY a.id ORDER BY a.name COLLATE NOCASE LIMIT :limit
    """

@Dao
interface CollectionDao {

    data class AlbumRow(
        val id: Long,
        val name: String,
        val albumArtist: String?,
        val year: Int?,
        val artworkUri: String?,
        val songCount: Int,
    )

    @Query(
        """
        SELECT a.id, a.name, a.albumArtist, a.year, a.artworkUri, COUNT(s.id) AS songCount
        FROM albums a JOIN songs s ON s.albumId = a.id
        GROUP BY a.id ORDER BY a.name COLLATE NOCASE
        """
    )
    fun observeAlbums(): Flow<List<AlbumRow>>

    data class ArtistRow(
        val id: Long,
        val name: String,
        val albumCount: Int,
        val songCount: Int,
    )

    /**
     * Build the Artists browser from the effective song rows themselves.
     *
     * Older builds used the auxiliary `artists` table as the left side of
     * this query. That table can legitimately lag behind the songs table
     * when a track arrives through an import/download path before the next
     * full media scan. The visible symptom was especially confusing: Songs
     * contained tracks from more performers while the Artists tab stayed
     * stuck at the smaller, previously-scanned count (for example 8).
     *
     * `songs_effective` is the source of truth the rest of Library already
     * renders from and also includes user tag edits. Deriving the distinct
     * artist names here means the Artists count/list changes in the same DB
     * transaction as the songs and can no longer be capped by a stale
     * denormalized artist row.
     *
     * NOTE: `MIN(s.id)` is NOT a per-artist identity and must not be used as
     * a Compose key. A song joins to both its `artist` and its `albumArtist`
     * name, so when those differ that one song can be the minimum in both
     * groups and two artists come back with the same id. Song ids are FNV-1a
     * hashes of the URI, not sequential, so the group minimum is effectively
     * random and the collision is easy to hit. `ArtistRow.toDomain()` replaces
     * this with a hash of the name; the column is kept only because removing
     * it would mean touching the projection and the row type for no gain.
     */
    @Query(
        """
        SELECT MIN(s.id) AS id, names.name AS name,
               COUNT(DISTINCT s.albumId) AS albumCount,
               COUNT(DISTINCT s.id) AS songCount
        FROM (
            SELECT DISTINCT TRIM(artist) AS name
            FROM songs_effective
            WHERE TRIM(artist) <> ''
            UNION
            SELECT DISTINCT TRIM(albumArtist) AS name
            FROM songs_effective
            WHERE albumArtist IS NOT NULL AND TRIM(albumArtist) <> ''
        ) AS names
        JOIN songs_effective s
          ON TRIM(s.artist) = names.name OR TRIM(COALESCE(s.albumArtist, '')) = names.name
        GROUP BY names.name
        ORDER BY names.name COLLATE NOCASE
        """
    )
    fun observeArtists(): Flow<List<ArtistRow>>

    /** Search uses the same song-derived source as [observeArtists]. */
    @Query(DIACRITIC_INSENSITIVE_ARTIST_SEARCH)
    fun searchArtists(q: String, limit: Int): Flow<List<ArtistRow>>

    @Query(DIACRITIC_INSENSITIVE_ALBUM_SEARCH)
    fun searchAlbums(q: String, limit: Int): Flow<List<AlbumRow>>

    /** See SongDao.observeSongCount for why these are COUNT queries. */
    @Query("SELECT COUNT(*) FROM albums")
    fun observeAlbumCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM artists")
    fun observeArtistCount(): Flow<Int>
}
