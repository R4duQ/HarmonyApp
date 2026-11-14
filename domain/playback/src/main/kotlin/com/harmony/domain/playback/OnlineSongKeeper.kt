package com.harmony.domain.playback

import com.harmony.core.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where keeping an online song stands. */
sealed interface KeepStatus {
    /** Being copied into the library. */
    data object Keeping : KeepStatus

    /** In the library, at [uri]. */
    data class Kept(val uri: String) : KeepStatus

    /** Not kept, and why (for the person: short, plain). */
    data class Failed(val reason: String) : KeepStatus
}

/**
 * Keeps a song an online mix prepared ([OnlineSongs]): its file, already
 * downloaded into the online cache (which deletes the oldest files when it is
 * full), is copied into the download folder and added to the library like any
 * download. Nothing is downloaded again.
 */
interface OnlineSongKeeper {
    /** By video id: the songs being kept, kept, or that could not be. Kept ones are remembered. */
    val status: StateFlow<Map<String, KeepStatus>>

    /**
     * Keeps [song] (an online song; any other is ignored) and returns the
     * library's song for it, or null when it could not be kept ([status]
     * says why). A song already kept, or already in the library, is not
     * copied again: the library's song is returned. One song at a time.
     */
    suspend fun keep(song: Song): Song?

    /** [song] was played to its end: how many times that has happened now (remembered). */
    suspend fun finished(song: Song): Int

    companion object {
        /** A build or a test without keeping: nothing is ever kept. */
        val NONE: OnlineSongKeeper = object : OnlineSongKeeper {
            private val empty = MutableStateFlow<Map<String, KeepStatus>>(emptyMap())
            override val status: StateFlow<Map<String, KeepStatus>> = empty.asStateFlow()
            override suspend fun keep(song: Song): Song? = null
            override suspend fun finished(song: Song): Int = 0
        }
    }
}
