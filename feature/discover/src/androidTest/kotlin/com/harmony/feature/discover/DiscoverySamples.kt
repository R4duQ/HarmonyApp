package com.harmony.feature.discover

import com.harmony.domain.library.discovery.BatchProgress
import com.harmony.domain.library.discovery.CandidateKind
import com.harmony.domain.library.discovery.DiscoveryDraft
import com.harmony.domain.library.discovery.DraftItem
import com.harmony.domain.library.discovery.DraftStep
import com.harmony.domain.library.discovery.ExplorationLevel
import com.harmony.domain.library.discovery.Reason
import com.harmony.domain.library.discovery.ReasonKind
import com.harmony.domain.library.repository.DiscoveryBatch
import com.harmony.domain.library.repository.DiscoverySong
import com.harmony.feature.discover.provider.LiveSongCatalog
import com.harmony.feature.discover.ui.Connection
import com.harmony.feature.discover.ui.DiscoveryUiState
import com.harmony.feature.discover.ui.TasteSummary

/** Realistic states for the UI tests and screenshots; every text is the kind the flow really produces. */
internal object DiscoverySamples {
    private val artists = listOf("Radiohead", "Portishead", "Massive Attack", "Muse", "Björk", "The National", "Beach House",
        "Sufjan Stevens", "Bon Iver", "Phoebe Bridgers")

    fun item(i: Int, kept: Boolean = false, local: Boolean = i % 4 == 0): DraftItem {
        val artist = artists[i % artists.size]
        val close = i % 5 < 3
        val song = DiscoverySong("dz:$i", if (i == 1) "An Extraordinarily Long Song Title That Keeps Going (Live at the Royal Albert Hall)" else "Track $i",
            artist, "Album $i", durationMs = 180_000L + i * 7_000, artwork = "", provider = "Deezer",
            localUri = if (local) "content://songs/$i" else null)
        val reason = if (close) Reason(ReasonKind.FREQUENT_RECENT, "You've played $artist a lot lately")
            else Reason(ReasonKind.RELATED_ARTIST, "Deezer lists $artist as related to Muse")
        return DraftItem(song, if (close) CandidateKind.CLOSE else CandidateKind.EXPLORE, reason, kept)
    }

    val taste = TasteSummary(loaded = true, hasHistory = true,
        artists = listOf("Muse" to "You've played Muse a lot lately", "Radiohead" to "3 of your favorites are by Radiohead",
            "Portishead" to "Portishead is in your playlists", "Björk" to "You listen to Björk often"),
        genres = listOf("Alternative", "Trip hop", "Electronic"))

    fun preferences(connection: Connection = Connection.ONLINE, history: Boolean = true) = DiscoveryUiState(
        loaded = true, step = DraftStep.PREFERENCES,
        draft = DiscoveryDraft("d", level = ExplorationLevel.BALANCED, size = 50),
        connection = connection,
        sources = LiveSongCatalog.initialStatus.values.toList(),
        taste = if (history) taste else TasteSummary(loaded = true, hasHistory = false),
        genres = listOf("Pop", "Rock", "Rap/Hip Hop", "Electronic", "R&B", "Jazz", "Classical", "Metal"),
        artistQuery = if (history) "" else "rad", artistResults = if (history) emptyList() else listOf("Radiohead", "Radio Moscow"),
    )

    fun songs(connection: Connection = Connection.ONLINE, size: Int = 50) = DiscoveryUiState(
        loaded = true, step = DraftStep.SONGS,
        draft = DiscoveryDraft("d", DraftStep.SONGS, ExplorationLevel.BALANCED, size,
            items = (1..size - 2).map { item(it, kept = it == 3) }, generatedAt = System.currentTimeMillis() - 3 * 3_600_000L,
            generatedOnline = true, sources = listOf("Deezer", "Your library")),
        connection = connection,
        notes = if (connection == Connection.ONLINE) listOf("Found 48 of 50 songs. Add more artists or genres, or try again later.") else emptyList(),
    )

    fun review(available: Int, total: Int = 20, playlistId: Long? = null, failed: Int = 0) : DiscoveryUiState {
        val songs = (1..total).map { item(it).song }
        val batch = DiscoveryBatch("b", "Discover · 23 Sep", songs, playlistId = playlistId, locked = playlistId != null,
            errors = songs.drop(available).take(failed).associate { it.key to "No close enough match on Soulseek." },
            reasons = songs.associate { it.key to "Deezer lists ${it.artist} as related to Muse" }, activeKey = songs.getOrNull(available + failed)?.key,
            status = if (playlistId != null) "Saved with $available of $total · the rest join when downloaded" else "Ready")
        val progress = BatchProgress(songs.take(available).associate { it.key to it.key.removePrefix("dz:").toLong() },
            songs.getOrNull(available + failed)?.key, songs.drop(available).take(failed).map { it.key }.toSet(), songs.drop(available).map { it.key })
        return DiscoveryUiState(loaded = true, step = DraftStep.REVIEW, review = batch, progress = progress, connection = Connection.ONLINE,
            draft = DiscoveryDraft("d", DraftStep.REVIEW, batchId = "b"))
    }
}
