package com.harmony.domain.library.discovery

import com.harmony.core.model.Song
import com.harmony.domain.library.repository.DiscoveryVote
import kotlin.math.ln
import kotlin.math.pow

/** One row of play_history. `completed` = heard at least half, or four minutes. */
data class ListeningEvent(val songId: Long, val playedAt: Long, val completed: Boolean)

enum class FeedbackKind { MORE_LIKE_THIS, NOT_INTERESTED }

/** An explicit choice made in Discover; stronger than a passive signal. */
data class TasteFeedback(
    val kind: FeedbackKind,
    val songKey: String,
    val title: String,
    val artist: String,
    val genres: Set<String> = emptySet(),
    val at: Long,
)

/** Everything the profile may use. All of it is data the app already stores. */
data class TasteInputs(
    val library: List<Song> = emptyList(),
    val events: List<ListeningEvent> = emptyList(),
    val favoriteIds: Set<Long> = emptySet(),
    /** Songs in the user's own playlists (Discover-made playlists excluded, so it cannot feed itself). */
    val playlistSongIds: Set<Long> = emptySet(),
    /** Likes and passes from the earlier swipe deck, oldest first. */
    val votes: List<DiscoveryVote> = emptyList(),
    val feedback: List<TasteFeedback> = emptyList(),
    val pickedArtists: List<String> = emptyList(),
    val pickedGenres: List<String> = emptyList(),
    val now: Long,
)

enum class ReasonKind {
    PICKED_ARTIST, PICKED_GENRE, MORE_LIKE_THIS, FAVORITE, FREQUENT_RECENT, FREQUENT, PLAYLISTS, DISCOVER_LIKE, LISTENED,
    RELATED_ARTIST, RELATED_TWO_STEPS, GENRE_CHART, LIBRARY_GENRE, LIBRARY_UNPLAYED, FILTER,
}

/** A short, true sentence: every reason names the data it came from. */
data class Reason(val kind: ReasonKind, val text: String)

data class ArtistTaste(
    val key: String,
    val name: String,
    val score: Float,
    val completed: Int,
    val recentCompleted: Int,
    val skipped: Int,
    val replays: Int,
    val favorites: Int,
    val inPlaylists: Int,
    val discoverLikes: Int,
    val moreLike: Int,
    val picked: Boolean,
    val genres: List<String>,
)

data class GenreTaste(val key: String, val name: String, val score: Float, val picked: Boolean)

data class TasteProfile(
    val artists: List<ArtistTaste>,
    val genres: List<GenreTaste>,
    /** Song ids with at least one completed play. */
    val heardSongIds: Set<Long>,
    val lastPlayedAt: Map<Long, Long>,
    val hasListeningData: Boolean,
) {
    private val byKey = artists.associateBy { it.key }
    private val genreByKey = genres.associateBy { it.key }

    fun artist(name: String): ArtistTaste? = byKey[TrackIdentity.primaryArtist(name)]
    fun genre(name: String): GenreTaste? = genreByKey[Genres.family(name)]

    /** Artists the mix treats as "close": a clearly positive, not merely present, signal. */
    val likedArtists: List<ArtistTaste> get() = artists.filter { it.score >= TasteProfileBuilder.LIKED_THRESHOLD }
    val likedGenres: List<GenreTaste> get() = genres.filter { it.score >= TasteProfileBuilder.LIKED_THRESHOLD }
    val isEmpty: Boolean get() = likedArtists.isEmpty() && likedGenres.isEmpty()

    /** The strongest real reason for this artist, or null if there is none. */
    fun reasonFor(artistName: String): Reason? {
        val a = artist(artistName) ?: return null
        if (a.score <= 0f) return null
        val n = a.name
        return when {
            a.picked -> Reason(ReasonKind.PICKED_ARTIST, "You picked $n")
            a.moreLike > 0 -> Reason(ReasonKind.MORE_LIKE_THIS, "You asked for more like $n")
            a.favorites > 1 -> Reason(ReasonKind.FAVORITE, "${a.favorites} of your favorites are by $n")
            a.favorites == 1 -> Reason(ReasonKind.FAVORITE, "A song by $n is in your favorites")
            a.recentCompleted >= 3 -> Reason(ReasonKind.FREQUENT_RECENT, "You've played $n a lot lately")
            a.completed >= 5 -> Reason(ReasonKind.FREQUENT, "You listen to $n often")
            a.inPlaylists > 0 -> Reason(ReasonKind.PLAYLISTS, "$n is in your playlists")
            a.discoverLikes > 0 -> Reason(ReasonKind.DISCOVER_LIKE, "You liked $n in Discover")
            else -> Reason(ReasonKind.LISTENED, "You've listened to $n")
        }
    }
}

/**
 * Builds the profile. Recent listening weighs more (21-day half-life), but a
 * long-term term keeps old favourites alive; negative signals are damped and
 * never exclude anything by themselves. Only "Not interested" removes a song,
 * and only that song.
 */
object TasteProfileBuilder {
    const val LIKED_THRESHOLD = 0.6f
    private const val DAY = 86_400_000L
    private const val HALF_LIFE_DAYS = 21.0
    private const val RECENT_WEIGHT = 0.6f
    private const val LONG_WEIGHT = 0.4f
    private const val COMPLETED = 1f
    private const val SKIP = 0.35f
    private const val REPLAY = 0.5f
    private const val REPLAY_WINDOW = 36 * 3_600_000L

    private class Acc(val key: String) {
        val names = HashMap<String, Int>()
        var recentPos = 0f; var recentNeg = 0f; var longPos = 0f; var longNeg = 0f
        var completed = 0; var recentCompleted = 0; var skipped = 0; var replays = 0
        var favorites = 0; var inPlaylists = 0; var discoverLikes = 0; var moreLike = 0; var picked = false
        val genres = HashMap<String, Int>()
        fun name(value: String) { names[value] = (names[value] ?: 0) + 1 }
        fun score(): Float = RECENT_WEIGHT * (recentPos - recentNeg) +
            LONG_WEIGHT * 2f * (ln(1f + longPos) - 0.5f * ln(1f + longNeg))
    }

    fun build(input: TasteInputs): TasteProfile {
        val songs = input.library.associateBy { it.id }
        val artists = HashMap<String, Acc>()
        val genres = HashMap<String, Acc>()
        fun artistAcc(name: String): Acc? {
            val key = TrackIdentity.primaryArtist(name).takeIf { it.isNotBlank() && it !in Genres.unknownArtists } ?: return null
            return artists.getOrPut(key) { Acc(key) }.also { it.name(name.trim()) }
        }
        fun genreAccs(raw: Collection<String>): List<Acc> = raw.flatMap(Genres::split).mapNotNull { g ->
            val key = Genres.family(g).takeIf { Genres.valid(g) } ?: return@mapNotNull null
            genres.getOrPut(key) { Acc(key) }.also { it.name(Genres.display(g)) }
        }
        fun songGenres(s: Song) = listOfNotNull(s.genre)
        fun decay(at: Long) = 0.5.pow(((input.now - at).coerceAtLeast(0) / DAY.toDouble()) / HALF_LIFE_DAYS).toFloat()

        // Listening history.
        val completedTimes = HashMap<Long, MutableList<Long>>()
        val last = HashMap<Long, Long>()
        for (e in input.events) {
            val song = songs[e.songId] ?: continue
            last[e.songId] = maxOf(last[e.songId] ?: 0L, e.playedAt)
            val w = decay(e.playedAt)
            val targets = listOfNotNull(artistAcc(song.artist)) + genreAccs(songGenres(song))
            for (acc in targets) {
                if (e.completed) {
                    acc.recentPos += w * COMPLETED; acc.longPos += COMPLETED; acc.completed++
                    if (input.now - e.playedAt <= 30 * DAY) acc.recentCompleted++
                } else {
                    acc.recentNeg += w * SKIP; acc.longNeg += SKIP; acc.skipped++
                }
            }
            if (e.completed) completedTimes.getOrPut(e.songId) { mutableListOf() } += e.playedAt
        }
        // Replays: a completed play within 36 h of the previous completed play of the same song.
        for ((songId, times) in completedTimes) {
            val song = songs[songId] ?: continue
            times.sort()
            for (i in 1 until times.size) if (times[i] - times[i - 1] <= REPLAY_WINDOW) {
                val w = decay(times[i])
                (listOfNotNull(artistAcc(song.artist)) + genreAccs(songGenres(song))).forEach {
                    it.recentPos += w * REPLAY; it.longPos += REPLAY; it.replays++
                }
            }
        }
        // Favorites and playlists: deliberate, long-term signals (no timestamps are stored for them).
        for (id in input.favoriteIds) {
            val song = songs[id] ?: continue
            artistAcc(song.artist)?.let { it.longPos += 3f; it.favorites++ }
            genreAccs(songGenres(song)).forEach { it.longPos += 1.5f }
        }
        val playlistCredit = HashMap<String, Int>()
        for (id in input.playlistSongIds) {
            val song = songs[id] ?: continue
            val acc = artistAcc(song.artist) ?: continue
            acc.inPlaylists++
            val n = (playlistCredit[acc.key] ?: 0) + 1
            playlistCredit[acc.key] = n
            if (n <= 8) acc.longPos += 1.2f // capped: one huge playlist must not decide everything
            genreAccs(songGenres(song)).forEach { it.longPos += 0.4f }
        }
        // Earlier Discover swipes.
        for (vote in input.votes) {
            val targets = listOfNotNull(artistAcc(vote.song.artist)) + genreAccs(vote.song.genres)
            targets.forEach { if (vote.liked) { it.longPos += 1.5f; it.discoverLikes++ } else it.longNeg += 0.4f }
        }
        // Explicit feedback from this flow.
        for (f in input.feedback) {
            val w = decay(f.at)
            val targets = listOfNotNull(artistAcc(f.artist)) + genreAccs(f.genres)
            targets.forEach {
                if (f.kind == FeedbackKind.MORE_LIKE_THIS) { it.recentPos += 3f * w; it.longPos += 1f; it.moreLike++ }
                else { it.recentNeg += 1.5f * w; it.longNeg += 1f }
            }
        }
        // Cold start: what the user told us.
        input.pickedArtists.forEach { name -> artistAcc(name)?.let { it.picked = true; it.longPos += 12f } }
        input.pickedGenres.filter(Genres::valid).forEach { g -> genreAccs(listOf(g)).forEach { it.picked = true; it.longPos += 12f } }

        // Artist → genres from the library's own tags.
        for (song in input.library) {
            val acc = artists[TrackIdentity.primaryArtist(song.artist)] ?: continue
            songGenres(song).flatMap(Genres::split).filter(Genres::valid).forEach {
                val d = Genres.display(it); acc.genres[d] = (acc.genres[d] ?: 0) + 1
            }
        }
        val artistList = artists.values.map { a ->
            ArtistTaste(a.key, a.names.maxByOrNull { it.value }!!.key, a.score(), a.completed, a.recentCompleted, a.skipped,
                a.replays, a.favorites, a.inPlaylists, a.discoverLikes, a.moreLike, a.picked,
                a.genres.entries.sortedByDescending { it.value }.take(2).map { it.key })
        }.sortedByDescending { it.score }
        val genreList = genres.values.map { g ->
            GenreTaste(g.key, g.names.maxByOrNull { it.value }!!.key, g.score(), g.picked)
        }.sortedByDescending { it.score }
        return TasteProfile(artistList, genreList, completedTimes.keys, last,
            hasListeningData = input.events.isNotEmpty() || input.favoriteIds.isNotEmpty() ||
                input.playlistSongIds.isNotEmpty() || input.votes.isNotEmpty())
    }
}

/** Genre tags as they appear in files and catalogs, cleaned before use. */
object Genres {
    val unknownArtists = setOf("unknown", "unknown artist", "various artists", "various", "va", "artist")
    private val junk = setOf("", "unknown", "other", "genre", "music", "misc", "none", "null", "default", "audio")

    /** "Pop; Rock" → [Pop, Rock]. "/" is kept: "Rap/Hip Hop" is one genre. */
    fun split(raw: String): List<String> = raw.split(';', ',', '|').map(String::trim).filter(String::isNotBlank)

    /** False for placeholders and bare ID3 numeric codes like "(13)". */
    fun valid(raw: String): Boolean {
        val n = TrackIdentity.normalize(raw)
        return n !in junk && !n.matches(Regex("\\d+")) && n.length <= 40
    }

    fun family(raw: String): String = when (val n = TrackIdentity.normalize(raw)) {
        "hip hop", "rap", "hip hop rap", "rap hip hop", "hiphop" -> "rap"
        "r b", "r b soul", "rnb", "rnb soul", "rhythm and blues" -> "rnb"
        "electro", "electronic", "electronica" -> "electronic"
        "soundtrack", "soundtracks", "film score" -> "soundtrack"
        else -> n
    }

    fun display(raw: String): String = raw.trim().replaceFirstChar { it.uppercaseChar() }
}
