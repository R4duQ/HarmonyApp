package com.harmony.core.database.entity

import androidx.room.Entity
import androidx.room.Fts4

/**
 * External-content FTS4 shadow of the songs table. Room generates the sync
 * triggers, so this stays consistent with zero application code.
 *
 * Why FTS over LIKE (Phase 9 decision, promised in Phase 4): LIKE '%q%'
 * cannot use a B-tree index — every search was a full scan of 6 text columns
 * across 20k rows on each debounced keystroke. FTS MATCH is an inverted-index
 * lookup: sub-millisecond, and it gives prefix search ("bea*" -> Beatles,
 * Beach House) which LIKE-contains only approximated by accident.
 * Tradeoff: FTS4 tokenizes on word boundaries, so mid-word matches
 * ("eatle" -> Beatles) are lost. That is the correct trade — users type
 * prefixes, not infixes.
 */
@Fts4(contentEntity = SongEntity::class)
@Entity(tableName = "songs_fts")
data class SongFtsEntity(
    val title: String,
    val artist: String,
    val album: String,
    val genre: String?,
    val composer: String?,
    val uri: String,
)
