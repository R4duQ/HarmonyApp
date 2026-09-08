package com.harmony.data.library

import com.harmony.core.database.dao.CollectionDao
import com.harmony.core.database.dao.SongDao
import com.harmony.core.common.text.SearchTextNormalizer
import com.harmony.core.model.Album
import com.harmony.core.model.Artist
import com.harmony.core.model.Song
import com.harmony.data.library.mapper.toDomain
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.library.repository.SearchResults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import androidx.paging.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val songDao: SongDao,
    private val collectionDao: CollectionDao,
) : LibraryRepository {

    override fun observeSongs(): Flow<List<Song>> =
        songDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeSongCount(): Flow<Int> = songDao.observeSongCount()

    override fun observeAlbumCount(): Flow<Int> = collectionDao.observeAlbumCount()

    override fun observeArtistCount(): Flow<Int> = collectionDao.observeArtistCount()

    override fun observeRecentDownloads(limit: Int): Flow<List<Song>> =
        songDao.observeRecentDownloads(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeAlbums(): Flow<List<Album>> =
        collectionDao.observeAlbums().map { rows -> rows.map { it.toDomain() } }

    override fun observeArtists(): Flow<List<Artist>> =
        collectionDao.observeArtists().map { rows -> rows.map { it.toDomain() } }

    override fun observeSongsByAlbum(albumId: Long): Flow<List<Song>> =
        songDao.observeByAlbum(albumId).map { rows -> rows.map { it.toDomain() } }

    override fun observeSongsByArtist(artistName: String): Flow<List<Song>> =
        songDao.observeByArtist(artistName).map { rows -> rows.map { it.toDomain() } }

    override fun observeSongsInFolder(folderPrefix: String): Flow<List<Song>> =
        songDao.observeInFolder(folderPrefix).map { rows -> rows.map { it.toDomain() } }

    override fun observeSongsPaged(): Flow<androidx.paging.PagingData<Song>> =
        androidx.paging.Pager(
            config = androidx.paging.PagingConfig(
                pageSize = 60,
                prefetchDistance = 120,
                enablePlaceholders = true, // fast-scroll a 20k list without jumps
            ),
            pagingSourceFactory = { songDao.pagingSource() },
        ).flow.map { pagingData -> pagingData.map { it.toDomain() } }

    override suspend fun randomSongId(exclude: Set<Long>): Long? {
        // Two cheap DB-side samples beat materializing 20k rows in RAM.
        repeat(2) {
            songDao.randomIds(50).firstOrNull { it !in exclude }?.let { return it }
        }
        return songDao.randomIds(1).firstOrNull()
    }

    override suspend fun originalSongById(id: Long): Song? =
        songDao.rawById(id)?.toDomain()

    override suspend fun songById(id: Long): Song? = songDao.byId(id)?.toDomain()

    override suspend fun songsByIds(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        // SQLite variable limit is 999; chunk defensively for giant queues.
        val byId = ids.chunked(SQL_CHUNK).flatMap { songDao.byIds(it) }.associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toDomain() } // preserves requested order
    }

    override suspend fun dateAddedByIds(ids: List<Long>): Map<Long, Long> {
        if (ids.isEmpty()) return emptyMap()
        return ids.chunked(SQL_CHUNK)
            .flatMap { songDao.existingDateAdded(it) }
            .associate { it.id to it.dateAdded }
    }

    override fun search(query: String): Flow<SearchResults> {
        val trimmed = query.trim()
        val match = FtsQuery.sanitize(trimmed) ?: return flowOf(SearchResults())
        val foldedQuery = SearchTextNormalizer.foldForSearch(trimmed)
        if (foldedQuery.isEmpty()) return flowOf(SearchResults())
        // The LIKE queries wrap this in '%' || :q || '%', so the wildcards
        // have to be neutralised first: typing a single '%' otherwise matched
        // the entire library, and '_' matched any character at all. Matches
        // the ESCAPE '\' clauses in SongDao and CollectionDao — the backslash
        // itself goes first, or it would double-escape the ones added after.
        val likeQuery = foldedQuery
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return combine(
            songDao.search(match, limit = 100),
            // Substring hits, merged in behind the FTS ones. FTS alone only
            // matches token prefixes, so "eatles" or "alori" would find
            // nothing — see SongDao.searchLike.
            //
            // combine() will not emit until EVERY source has produced a value,
            // so without the empty seed below the fast indexed FTS result would
            // sit behind three unindexed full scans and the list would only
            // appear once the slowest of them finished. Seeding them lets the
            // prefix hits paint on the current keystroke; the scans then fill
            // in the infix matches, albums and artists a moment later.
            songDao.searchLike(likeQuery, limit = 100).onStart { emit(emptyList()) },
            collectionDao.searchAlbums(likeQuery, limit = 25).onStart { emit(emptyList()) },
            collectionDao.searchArtists(likeQuery, limit = 25).onStart { emit(emptyList()) },
        ) { ftsSongs, likeSongs, albums, artists ->
            // FTS first: a prefix hit is a better match than an infix one,
            // and distinctBy keeps that order while dropping duplicates.
            val songs = (ftsSongs + likeSongs).distinctBy { it.id }.take(100)
            SearchResults(
                songs = songs.map { it.toDomain() },
                albums = albums.map { it.toDomain() },
                // Via toDomain(), not by hand: the row's `id` is a song id and
                // is not unique per artist. See CollectionDao.ArtistRow.toDomain.
                artists = artists.map { it.toDomain() },
            )
        }
    }

    private companion object {
        const val SQL_CHUNK = 900
    }
}
