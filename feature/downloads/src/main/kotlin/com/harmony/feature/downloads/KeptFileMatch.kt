package com.harmony.feature.downloads

import com.harmony.core.model.Song

/**
 * Finds the library's song for a file a keep just saved.
 *
 * The saved file and the library don't spell its address the same way.
 * MediaStore hands back `content://media/external/audio/media/42`, the
 * library scanner stores the volume it found it on,
 * `content://media/external_primary/audio/media/42`, and a chosen download
 * folder gives a document address the scanner never uses. So they are
 * compared by MediaStore row id, not by text.
 */
internal object KeptFileMatch {
    private val MEDIA_ROW = Regex("^content://media/[^/]+/audio/media/(\\d+)(?:[/?#].*)?$")

    /** The MediaStore row id in a `content://media/<volume>/audio/media/<id>` address, or null. */
    fun mediaRowId(uri: String): Long? = MEDIA_ROW.find(uri)?.groupValues?.get(1)?.toLongOrNull()

    /** The library song for MediaStore row [rowId], or for the exact address [uri]. */
    fun librarySong(songs: List<Song>, uri: String, rowId: Long?): Song? =
        songs.firstOrNull { it.id >= 0 && (it.uri == uri || (rowId != null && mediaRowId(it.uri) == rowId)) }
}
