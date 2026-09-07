package com.harmony.core.database.entity

import androidx.room.DatabaseView

/**
 * Deliberately ONE line.
 *
 * Room verifies a view by comparing the stored CREATE VIEW text against the
 * expected one, and DatabaseModule's migration has to create the same view
 * by hand (Room only auto-creates views on a fresh install). Any difference
 * — including indentation — fails that check and crashes at startup, so the
 * definition is kept in a single constant both sides reference.
 */
const val SONGS_EFFECTIVE_SQL: String =
    "SELECT s.id, s.uri, COALESCE(e.title, s.title) AS title, COALESCE(e.artist, s.artist) AS artist, COALESCE(e.album, s.album) AS album, s.albumId, COALESCE(e.albumArtist, s.albumArtist) AS albumArtist, s.composer, s.year, s.genre, s.discNumber, s.trackNumber, s.durationMs, s.bitrateKbps, s.sampleRateHz, s.bitDepth, s.channels, s.fileSizeBytes, s.fileHash, s.lastModified, s.dateAdded, s.storageVolume, s.embeddedLyrics, CASE WHEN e.artworkCleared = 1 THEN NULL ELSE COALESCE(e.artworkUri, s.artworkUri) END AS artworkUri, s.replayGainTrackDb, s.replayGainAlbumDb FROM songs s LEFT JOIN song_edits e ON e.songId = s.id"

/**
 * `songs` with the user's manual tag edits already applied.
 *
 * Defined as a view rather than applied in Kotlin so that EVERY read path
 * gets the override for free — including the paged all-songs list, which
 * streams straight from SQLite and never passes through a place where a
 * Kotlin-side merge could happen. It also means Android Auto, the
 * notification and the in-app UI can't disagree about a song's name.
 *
 * Column names match [SongEntity] exactly, so queries can select from this
 * view and still return SongEntity — Room maps by column name, not by table.
 *
 * Artwork has three states, which is why it needs a CASE rather than a plain
 * COALESCE: replaced (use the edit), removed (show nothing), or untouched
 * (fall back to what was extracted from the file).
 */
@DatabaseView(viewName = "songs_effective", value = SONGS_EFFECTIVE_SQL)
data class SongEffectiveView(
    val id: Long,
)
