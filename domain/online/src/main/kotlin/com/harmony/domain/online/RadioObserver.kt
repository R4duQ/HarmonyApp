package com.harmony.domain.online

/**
 * Told of every radio an online mix reads, so what it says goes together
 * can be remembered for the offline mix (the taste memory). Called on the
 * mix's own scope; a failure is the observer's to handle.
 */
fun interface RadioObserver {
    /**
     * [tracks] (songs only, in the radio's order) came from the radio of
     * [seed], the song [seedSongId] of the library or of an online mix.
     * [sameSong]: the radio is the seed's own; false when it started from
     * another song of the seed's artist.
     */
    suspend fun onRadio(seedSongId: Long, seed: SeedTrack, sameSong: Boolean, tracks: List<CatalogTrack>)

    companion object {
        val NONE = RadioObserver { _, _, _, _ -> }
    }
}
