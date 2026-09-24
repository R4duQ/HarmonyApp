package com.harmony.feature.discover.provider

import com.harmony.domain.library.discovery.ArtistTaste
import com.harmony.domain.library.discovery.Candidate
import com.harmony.domain.library.discovery.CandidateKind
import com.harmony.domain.library.discovery.DraftItem
import com.harmony.domain.library.discovery.ExplorationLevel
import com.harmony.domain.library.discovery.Genres
import com.harmony.domain.library.discovery.LibraryIndex
import com.harmony.domain.library.discovery.Reason
import com.harmony.domain.library.discovery.ReasonKind
import com.harmony.domain.library.discovery.RecommendationMixer
import com.harmony.domain.library.discovery.TasteProfile
import com.harmony.domain.library.discovery.TrackIdentity
import com.harmony.domain.library.repository.DiscoveryFilter
import com.harmony.domain.library.repository.DiscoveryRules
import com.harmony.domain.library.repository.DiscoverySong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import kotlin.math.pow
import kotlin.random.Random

data class RecommendationRequest(
    val profile: TasteProfile,
    val library: LibraryIndex,
    val level: ExplorationLevel,
    val online: Boolean,
    val now: Long,
    val seed: Long,
    val filter: DiscoveryFilter = DiscoveryFilter(),
    val declined: Set<String> = emptySet(),
)

data class RecommendationOutcome(
    val candidates: List<Candidate>,
    /** Metadata sources that actually contributed, e.g. "Deezer", "Your library". */
    val sources: List<String>,
    /** User-facing notes about sources that failed; empty when all went well. */
    val problems: List<String>,
    val usedOnline: Boolean,
    /** Set when the connection itself failed mid-way, not just one source. */
    val offline: Boolean = false,
)

/**
 * Harmony's own selection. Providers supply catalog facts (an artist's top
 * tracks, Deezer's related-artist graph, genre charts); which of them fit
 * this listener, and why, is decided here from local data. Nothing is
 * presented as a provider's personal recommendation.
 */
class RecommendationEngine @Inject constructor(private val catalog: DiscoveryCatalog) {

    suspend fun candidates(request: RecommendationRequest, progress: (String) -> Unit = {}): RecommendationOutcome {
        val out = Collector(request)
        progress("Reading your library…")
        local(request, out)
        if (request.online) runOnline(out) {
            if (request.profile.isEmpty) popular(request, out, progress) else online(request, out, progress)
        }
        return out.result()
    }

    /** "More like this": same artist first, then its related artists, then the library. */
    suspend fun moreLike(item: DraftItem, request: RecommendationRequest): RecommendationOutcome {
        val out = Collector(request)
        val song = item.song
        val artistKey = TrackIdentity.primaryArtist(song.artist)
        val genres = song.genres.filter(Genres::valid).map(Genres::family).toSet()
        val like = "“${song.title}”"
        for (local in request.library.songs) {
            if (TrackIdentity.primaryArtist(local.artist) == artistKey) {
                out.add(DiscoveryRules.fromLocal(local), CandidateKind.CLOSE, Reason(ReasonKind.MORE_LIKE_THIS, "Same artist as $like"), 1.2f)
            } else if (genres.isNotEmpty() && local.genre?.let(Genres::split)?.any { Genres.family(it) in genres } == true) {
                out.add(DiscoveryRules.fromLocal(local), CandidateKind.EXPLORE, Reason(ReasonKind.MORE_LIKE_THIS, "Same genre as $like"), 0.6f)
            }
        }
        if (request.online) runOnline(out) {
            val artist = out.attempt("Deezer") { catalog.findArtist(song.artist) } ?: return@runOnline
            out.attempt("Deezer") { catalog.artistTop(artist, 12) }?.forEachIndexed { i, t ->
                out.add(t, CandidateKind.CLOSE, Reason(ReasonKind.MORE_LIKE_THIS, "More from ${artist.name}, like $like"), 1.5f - i * 0.03f)
            }
            val related = out.attempt("Deezer") { catalog.related(artist, 10) }.orEmpty().take(4)
            for (r in related) out.attempt("Deezer") { catalog.artistTop(r, 3) }?.forEach { t ->
                out.add(t, CandidateKind.EXPLORE, Reason(ReasonKind.RELATED_ARTIST, "Deezer lists ${r.name} as related to ${artist.name}"), 1f)
            }
        }
        return out.result()
    }

    private suspend fun runOnline(out: Collector, block: suspend () -> Unit) {
        try {
            withTimeout(ONLINE_BUDGET_MS) { block() }
        } catch (e: TimeoutCancellationException) {
            out.note("Some sources took too long. Showing what arrived in time.")
        }
    }

    /** Library songs: available offline, and the only source when there is no connection. */
    private fun local(request: RecommendationRequest, out: Collector) {
        val p = request.profile
        val liked = p.likedArtists.associateBy { it.key }
        val likedGenres = p.likedGenres.associateBy { it.key }
        val topArtist = liked.values.maxOfOrNull { it.score }?.coerceAtLeast(1f) ?: 1f
        val topGenre = likedGenres.values.maxOfOrNull { it.score }?.coerceAtLeast(1f) ?: 1f
        val recently = request.now - 60 * DAY
        for (song in request.library.songs) {
            // Played in the last two months: the listener knows it, it isn't a discovery.
            if ((p.lastPlayedAt[song.id] ?: 0L) > recently) continue
            val track = DiscoveryRules.fromLocal(song)
            val artist = liked[TrackIdentity.primaryArtist(song.artist)]
            if (artist != null) {
                val reason = p.reasonFor(song.artist) ?: continue
                out.add(track, CandidateKind.CLOSE, reason, 0.55f * artist.score / topArtist + if (song.id !in p.heardSongIds) 0.1f else 0f)
                continue
            }
            val genre = song.genre?.let(Genres::split)?.firstOrNull { Genres.valid(it) && Genres.family(it) in likedGenres } ?: continue
            val g = likedGenres.getValue(Genres.family(genre))
            val reason = if (song.id !in p.heardSongIds && p.lastPlayedAt[song.id] == null)
                Reason(ReasonKind.LIBRARY_UNPLAYED, "In your library, never played · ${g.name}, a genre you play")
            else Reason(ReasonKind.LIBRARY_GENRE, "From your library · ${g.name}, a genre you play")
            // A genre the listener plays is their taste, even from an artist they haven't heard.
            out.add(track, CandidateKind.CLOSE, reason, 0.35f * g.score / topGenre)
        }
        if (out.size > 0) out.source(LIBRARY)
    }

    private suspend fun online(request: RecommendationRequest, out: Collector, progress: (String) -> Unit) {
        val p = request.profile
        val level = request.level
        val known = p.artists.filter { it.score > 0f }.map { it.key }.toSet()
        val topScore = p.likedArtists.maxOfOrNull { it.score }?.coerceAtLeast(1f) ?: 1f
        val seeds = seeds(p.likedArtists, 8, request.seed)

        progress("Looking up artists you play…")
        val resolved = ArrayList<Pair<ArtistTaste, CatalogArtist>>()
        for (taste in seeds) {
            if (out.stopped("Deezer")) break
            out.attempt("Deezer") { catalog.findArtist(taste.name) }?.let { resolved += taste to it }
        }

        progress("Finding new songs by artists you like…")
        for ((taste, artist) in resolved) {
            val reason = p.reasonFor(taste.name) ?: continue
            out.attempt("Deezer") { catalog.artistTop(artist, 15) }?.forEachIndexed { i, t ->
                out.add(t, CandidateKind.CLOSE, reason, 0.7f + 0.5f * taste.score / topScore - i * 0.02f)
            }
        }

        // Exploration with a stated link: Deezer's related-artist graph from artists the listener likes.
        progress("Following related artists…")
        val perSeed = when (level) { ExplorationLevel.FOR_MY_TASTE -> 2; ExplorationLevel.BALANCED -> 3; ExplorationLevel.SURPRISE_ME -> 4 }
        val seen = resolved.map { it.second.id }.toMutableSet()
        val hop1 = ArrayList<Triple<CatalogArtist, ArtistTaste, CatalogArtist>>()
        for ((taste, artist) in resolved.take(5)) {
            val related = out.attempt("Deezer") { catalog.related(artist, 12) } ?: continue
            related.filter { TrackIdentity.primaryArtist(it.name) !in known && seen.add(it.id) }.take(perSeed)
                .forEach { hop1 += Triple(it, taste, artist) }
        }
        for ((artist, taste, seed) in hop1) {
            out.attempt("Deezer") { catalog.artistTop(artist, 5) }?.forEachIndexed { i, t ->
                out.add(t, CandidateKind.EXPLORE, Reason(ReasonKind.RELATED_ARTIST, "Deezer lists ${artist.name} as related to ${seed.name}"),
                    0.55f + 0.3f * taste.score / topScore - i * 0.03f)
            }
        }
        // Further out, for the more adventurous levels: two steps along the same graph.
        if (level != ExplorationLevel.FOR_MY_TASTE) {
            val middles = hop1.shuffled(Random(request.seed)).take(if (level == ExplorationLevel.SURPRISE_ME) 3 else 1)
            for ((middle, _, seed) in middles) {
                val far = out.attempt("Deezer") { catalog.related(middle, 10) } ?: continue
                for (artist in far.filter { TrackIdentity.primaryArtist(it.name) !in known && seen.add(it.id) }.take(2)) {
                    out.attempt("Deezer") { catalog.artistTop(artist, 3) }?.forEach { t ->
                        out.add(t, CandidateKind.EXPLORE, Reason(ReasonKind.RELATED_TWO_STEPS,
                            "${artist.name} is related to ${middle.name}, who is related to ${seed.name} (Deezer)"), 0.4f)
                    }
                }
            }
        }

        progress("Checking genre charts…")
        for (g in p.likedGenres.take(if (level == ExplorationLevel.SURPRISE_ME) 3 else 2)) {
            if (out.stopped("Deezer")) break
            val genre = catalog.genreFor(g.name) ?: continue
            out.attempt("Deezer") { catalog.genreChart(genre, 40) }?.forEachIndexed { i, t ->
                val reason = p.reasonFor(t.artist)
                if (reason != null) out.add(t, CandidateKind.CLOSE, reason, 0.5f - i * 0.005f)
                else out.add(t, CandidateKind.CLOSE, Reason(ReasonKind.GENRE_CHART, "Popular in ${genre.name} on Deezer · a genre you play"), 0.35f - i * 0.004f)
            }
        }

        filtered(request, out)

        // Deezer failed: Apple's catalog for the same artists, and a country chart for discoveries.
        if (out.stopped("Deezer") && out.onlineCount < RecommendationMixer.DEFAULT_SIZE) {
            progress("Deezer is unavailable, trying Apple Music…")
            for (taste in seeds.take(4)) {
                if (out.stopped("Apple Music")) break
                val reason = p.reasonFor(taste.name) ?: continue
                out.attempt("Apple Music") { catalog.appleArtistSongs(taste.name, 20) }?.forEachIndexed { i, t ->
                    out.add(t, CandidateKind.CLOSE, reason, 0.7f - i * 0.02f)
                }
            }
            val country = request.filter.country.ifBlank { "US" }
            val genres = p.likedGenres.map { it.key }.toSet()
            out.attempt("Apple Music") { catalog.regionalChart(country) }?.forEach { t ->
                val genre = t.genres.firstOrNull { Genres.family(it) in genres } ?: return@forEach
                if (p.reasonFor(t.artist) == null) out.add(t, CandidateKind.CLOSE,
                    Reason(ReasonKind.GENRE_CHART, "In Apple Music's ${countryName(country)} chart · $genre, a genre you play"), 0.35f)
            }
        }
    }

    /** No history and nothing picked: charts, labelled as charts. */
    private suspend fun popular(request: RecommendationRequest, out: Collector, progress: (String) -> Unit) {
        progress("Loading popular songs…")
        out.attempt("Deezer") { catalog.genreChart(DiscoveryGenre(0, ""), 100) }?.forEachIndexed { i, t ->
            out.add(t, CandidateKind.EXPLORE, Reason(ReasonKind.GENRE_CHART, "Popular on Deezer right now"), 0.5f - i * 0.004f)
        }
        filtered(request, out)
    }

    /** The optional country / genre / playlist-keyword filters from the earlier Discover. */
    private suspend fun filtered(request: RecommendationRequest, out: Collector) {
        val f = request.filter
        if (f == DiscoveryFilter()) return
        val label = listOfNotNull(f.genre.ifBlank { null }, f.vibe.ifBlank { null }?.let { "“$it” playlists" },
            f.country.ifBlank { null }?.let { if (f.artistCountryOnly) "artists from ${countryName(it)}" else "popular in ${countryName(it)}" })
            .joinToString(" · ")
        val page = out.attempt(if (f.artistCountryOnly) "MusicBrainz" else "Deezer") { catalog.page(f, 0, emptyList()) } ?: return
        page.note?.let(out::note)
        page.songs.forEachIndexed { i, t ->
            val reason = request.profile.reasonFor(t.artist)
            if (reason != null) out.add(t, CandidateKind.CLOSE, reason, 0.6f - i * 0.003f)
            else out.add(t, CandidateKind.EXPLORE, Reason(ReasonKind.FILTER, "Matches your filter: $label"), 0.5f - i * 0.003f)
        }
    }

    /** Weighted sample of liked artists: the top two always, the rest vary between sessions. */
    internal fun seeds(liked: List<ArtistTaste>, count: Int, seed: Long): List<ArtistTaste> {
        val pool = liked.take(24)
        if (pool.size <= count) return pool
        val random = Random(seed)
        val rest = pool.drop(2).sortedByDescending { random.nextDouble().pow(1.0 / it.score.coerceAtLeast(0.05f)) }
        return pool.take(2) + rest.take(count - 2)
    }

    /** Collects candidates as they arrive, so a timeout still leaves a usable partial result. */
    private class Collector(private val request: RecommendationRequest) {
        private val byIdentity = LinkedHashMap<String, Candidate>()
        private val isrcs = HashMap<String, String>()
        private val sources = LinkedHashSet<String>()
        private val notes = LinkedHashSet<String>()
        private val stoppedSources = HashSet<String>()
        private var offline = false
        var onlineCount = 0; private set
        val size: Int get() = byIdentity.size

        fun source(name: String) { sources += name }
        fun note(text: String) { notes += text }
        fun stopped(source: String) = offline || source in stoppedSources

        suspend fun <T> attempt(source: String, block: suspend () -> T): T? {
            if (stopped(source)) return null
            currentCoroutineContext().ensureActive()
            return try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = SourceErrors.fromThrowable(source, e)
                notes += failure.message.orEmpty()
                when (failure.problem) {
                    SourceProblem.OFFLINE -> offline = true
                    SourceProblem.RATE_LIMITED, SourceProblem.UNAVAILABLE, SourceProblem.AUTH_EXPIRED, SourceProblem.TIMEOUT -> stoppedSources += source
                    SourceProblem.BAD_RESPONSE -> Unit // one odd answer; the next request may be fine
                }
                null
            }
        }

        fun add(raw: DiscoverySong, kind: CandidateKind, reason: Reason, score: Float) {
            if (raw.key in request.declined || raw.identity in request.declined || (raw.isrc != null && raw.isrc in request.declined)) return
            val local = request.library.match(raw)
            val p = request.profile
            // Already heard: not a discovery. Recently played files are handled by [local].
            if (local != null && local.id in p.heardSongIds && raw.localUri == null) return
            val song = if (local != null) raw.copy(localUri = local.uri) else raw
            // A mild, bounded penalty for artists with net negative signals; never an exclusion.
            val penalty = (p.artist(song.artist)?.score ?: 0f).coerceIn(-3f, 0f) * 0.1f
            val candidate = Candidate(song, kind, reason, score + penalty)
            val key = song.isrc?.let(isrcs::get) ?: song.identity
            val existing = byIdentity[key]
            when {
                existing == null -> byIdentity[key] = candidate
                TrackIdentity.same(existing.song.track, song.track) -> if (better(candidate, existing)) byIdentity[key] = candidate
                else -> byIdentity[song.key] = candidate // same name and artist, different recording (e.g. length)
            }
            song.isrc?.let { isrcs.putIfAbsent(it, key) }
            if (raw.provider != "Library") { sources += raw.provider; onlineCount++ }
        }

        /** Prefer a file the user already has, then an ISRC, then the higher score. */
        private fun better(a: Candidate, b: Candidate): Boolean = when {
            (a.song.localUri != null) != (b.song.localUri != null) -> a.song.localUri != null
            (a.song.isrc != null) != (b.song.isrc != null) -> a.song.isrc != null
            else -> a.score > b.score
        }

        fun result() = RecommendationOutcome(byIdentity.values.toList(), sources.toList(), notes.toList(),
            usedOnline = onlineCount > 0, offline = offline)
    }

    companion object {
        const val LIBRARY = "Your library"
        private const val ONLINE_BUDGET_MS = 45_000L
        private const val DAY = 86_400_000L
        fun countryName(code: String): String =
            if (code == "GB") "United Kingdom" else java.util.Locale("", code).getDisplayCountry(java.util.Locale.ENGLISH)
    }
}
