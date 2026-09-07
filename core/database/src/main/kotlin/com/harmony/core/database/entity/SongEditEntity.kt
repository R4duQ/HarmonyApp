package com.harmony.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user's manual corrections to one song's tags.
 *
 * Kept in its own table rather than written into `songs` for one decisive
 * reason: the scanner upserts `songs` on every rescan, so an edit written
 * there would survive exactly until the next scan and then silently revert.
 * A separate table is untouched by scanning, and the join in
 * [com.harmony.core.database.entity.SongWithEdits] applies it on read.
 *
 * These edits do NOT change the audio files. That's deliberate — rewriting
 * ID3 or Vorbis tags in place risks corrupting the library, and needs
 * per-file write permission on modern Android. Everything Harmony shows
 * (including Android Auto, which reads the same database) reflects the
 * edit; other apps still see the original tags.
 *
 * A null field means "not overridden", so clearing an edit restores what
 * the file says rather than leaving a blank.
 */
@Entity(tableName = "song_edits")
data class SongEditEntity(
    @PrimaryKey val songId: Long,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    /** Replacement artwork, stored by ArtworkCache under a user-art key. */
    val artworkUri: String? = null,
    /**
     * True when the user explicitly REMOVED artwork. Distinct from a null
     * artworkUri, which only means "no replacement supplied" — without this
     * flag there'd be no way to say "show no cover" as opposed to "fall
     * back to the embedded one".
     */
    val artworkCleared: Boolean = false,
)
