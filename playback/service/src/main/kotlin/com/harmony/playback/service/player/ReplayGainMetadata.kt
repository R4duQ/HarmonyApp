package com.harmony.playback.service.player

import android.os.Bundle
import com.harmony.core.model.Song

/** Survives MediaController transport; the service need not own a second song cache. */
internal object ReplayGainMetadata {
    const val TRACK = "com.harmony.replaygain.track"
    const val ALBUM = "com.harmony.replaygain.album"

    fun from(song: Song) = Bundle().apply {
        song.replayGainTrackDb?.takeIf { it.isFinite() }?.let { putFloat(TRACK, it) }
        song.replayGainAlbumDb?.takeIf { it.isFinite() }?.let { putFloat(ALBUM, it) }
    }

    fun gain(extras: Bundle?, key: String): Float? =
        extras?.takeIf { it.containsKey(key) }?.getFloat(key)?.takeIf { it.isFinite() }
}
