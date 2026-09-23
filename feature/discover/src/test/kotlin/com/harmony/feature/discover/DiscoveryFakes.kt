package com.harmony.feature.discover

import com.harmony.core.model.Song
import com.harmony.domain.library.repository.DiscoveryFilter
import com.harmony.domain.library.repository.DiscoverySong
import com.harmony.domain.library.repository.SongDiscoveryRepository
import com.harmony.domain.library.repository.SongDiscoveryState
import com.harmony.feature.discover.provider.CatalogArtist
import com.harmony.feature.discover.provider.CatalogPage
import com.harmony.feature.discover.provider.DiscoveryCatalog
import com.harmony.feature.discover.provider.DiscoveryGenre
import com.harmony.feature.discover.provider.LiveSongCatalog
import com.harmony.feature.discover.provider.SourceException
import com.harmony.feature.discover.provider.SourceProblem
import com.harmony.feature.discover.provider.SourceStatus
import com.harmony.feature.discover.ui.PreviewPlayer
import com.harmony.feature.discover.ui.PreviewPlayerFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList

internal fun libSong(id: Long, artist: String, title: String = "$artist song $id", genre: String? = "Rock", ms: Long = 200_000L) = Song(
    id = id, uri = "content://songs/$id", title = title, artist = artist, album = "Album", albumId = 1, albumArtist = null,
    composer = null, year = null, genre = genre, discNumber = null, trackNumber = null, durationMs = ms, bitrateKbps = null,
    sampleRateHz = null, bitDepth = null, channels = null, artworkUri = null, embeddedLyrics = null,
    replayGainTrackDb = null, replayGainAlbumDb = null,
)

/**
 * A catalog with a small, fixed graph:
 * Muse → related Radiohead, Placebo, Foo Fighters; Radiohead → Portishead, Massive Attack.
 * Every artist has 12 top tracks "dz:<artistId><nn>".
 */
internal class FakeCatalog : DiscoveryCatalog {
    val artists = listOf(
        CatalogArtist(2, "Muse", fans = 900), CatalogArtist(3, "Radiohead"), CatalogArtist(4, "Placebo"),
        CatalogArtist(5, "Portishead"), CatalogArtist(6, "Massive Attack"), CatalogArtist(7, "Foo Fighters"),
        CatalogArtist(8, "Queens of the Stone Age"),
    ).associateBy { it.name }
    private val graph = mapOf("Muse" to listOf("Radiohead", "Placebo", "Foo Fighters"), "Radiohead" to listOf("Portishead", "Massive Attack"),
        "Placebo" to listOf("Queens of the Stone Age"), "Foo Fighters" to listOf("Queens of the Stone Age"))
    var deezer: SourceProblem? = null
    var offline = false
    /** When set, every Deezer call waits for it: lets a test hold a request in flight. */
    @Volatile var gate: CompletableDeferred<Unit>? = null
    val calls = CopyOnWriteArrayList<String>()
    override val status: StateFlow<Map<String, SourceStatus>> = MutableStateFlow(LiveSongCatalog.initialStatus)

    private suspend fun deezerCall(name: String) {
        calls += name
        gate?.await()
        if (offline) throw UnknownHostException("api.deezer.com")
        deezer?.let { throw SourceException("Deezer", it) }
    }

    fun top(artist: String, n: Int = 12) = (1..n).map { i ->
        val id = artists.getValue(artist).id
        DiscoverySong("dz:$id${"%02d".format(i)}", "$artist track $i", artist, durationMs = 180_000L + id * 1_000 + i, provider = "Deezer",
            artwork = "https://cdn/$id/$i.jpg")
    }

    override suspend fun genres() = listOf(DiscoveryGenre(152, "Rock"), DiscoveryGenre(129, "Jazz"))
    override suspend fun genreFor(name: String) = genres().firstOrNull { it.name.equals(name, true) }
    override suspend fun findArtist(name: String): CatalogArtist? { deezerCall("find:$name"); return artists[name] }
    override suspend fun searchArtists(query: String, limit: Int): List<CatalogArtist> {
        deezerCall("search:$query"); return artists.values.filter { it.name.contains(query, true) }
    }
    override suspend fun artistTop(artist: CatalogArtist, limit: Int): List<DiscoverySong> { deezerCall("top:${artist.name}"); return top(artist.name, minOf(limit, 12)) }
    override suspend fun related(artist: CatalogArtist, limit: Int): List<CatalogArtist> {
        deezerCall("related:${artist.name}"); return graph[artist.name].orEmpty().mapNotNull(artists::get)
    }
    override suspend fun genreChart(genre: DiscoveryGenre, limit: Int): List<DiscoverySong> {
        deezerCall("chart:${genre.id}")
        return (1..20).map { DiscoverySong("dz:c${genre.id}$it", "Chart hit $it", "Chart Artist $it", durationMs = 200_000L + it, provider = "Deezer",
            genres = setOfNotNull(genre.name.ifBlank { null })) }
    }
    override suspend fun appleArtistSongs(artist: String, limit: Int): List<DiscoverySong> {
        calls += "apple:$artist"
        if (offline) throw UnknownHostException("itunes.apple.com")
        return (1..8).map { DiscoverySong("apple:${artist.hashCode()}$it", "$artist (Apple) $it", artist, durationMs = 190_000L + it, provider = "Apple Music") }
    }
    override suspend fun regionalChart(country: String): List<DiscoverySong> {
        calls += "regional:$country"
        return (1..10).map { DiscoverySong("apple:r$it", "Regional $it", "Regional Artist $it", provider = "Apple Music chart", genres = setOf("Rock")) }
    }
    override suspend fun page(filter: DiscoveryFilter, page: Int, likes: List<DiscoverySong>) = CatalogPage(emptyList())
    override suspend fun preview(song: DiscoverySong): String? { calls += "preview:${song.key}"; return "https://cdnt-preview.dzcdn.net/${song.key}.mp3" }
}

internal class InMemoryDiscoveryRepository(initial: SongDiscoveryState = SongDiscoveryState()) : SongDiscoveryRepository {
    private val mutable = MutableStateFlow(initial)
    override val state: StateFlow<SongDiscoveryState> = mutable
    var failWrites = false
    override suspend fun update(change: (SongDiscoveryState) -> SongDiscoveryState) = synchronized(this) {
        check(!failWrites) { "Couldn't save discovery progress. Free storage and retry." }
        mutable.value = change(mutable.value)
    }
}

internal class FakePreviewPlayers : PreviewPlayerFactory {
    inner class Player(val index: Int) : PreviewPlayer {
        var uri: String? = null; var released = false
        var ready: () -> Unit = {}; var ended: () -> Unit = {}; var error: () -> Unit = {}
        override fun play(uri: String, fromLibrary: Boolean, onReady: () -> Unit, onEnded: () -> Unit, onError: () -> Unit) {
            this.uri = uri; ready = onReady; ended = onEnded; error = onError
        }
        override fun release() { released = true }
    }
    val created = CopyOnWriteArrayList<Player>()
    override fun create(): PreviewPlayer = Player(created.size).also { created += it }
}
