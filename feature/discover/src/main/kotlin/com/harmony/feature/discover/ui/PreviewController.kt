package com.harmony.feature.discover.ui

import com.harmony.domain.library.repository.DiscoverySong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** The preview on screen: which song, and whether it is loading, playing or failed (with a message). */
data class PreviewState(val songId: String? = null, val loading: Boolean = false,
    val playing: Boolean = false, val message: String? = null)

/** Plays one short clip. Callbacks arrive on the main thread. */
interface PreviewPlayer {
    fun play(uri: String, fromLibrary: Boolean, onReady: () -> Unit, onEnded: () -> Unit, onError: () -> Unit)
    fun release()
}

fun interface PreviewPlayerFactory { fun create(): PreviewPlayer }

/**
 * One preview at a time, always for the song on screen. Every start gets a
 * token; an answer that arrives for an older token (a slow preview URL, a
 * player that becomes ready after the user swiped on) is dropped, so the
 * sound can never belong to a different card than the one shown.
 */
class PreviewController(
    private val scope: CoroutineScope,
    private val factory: PreviewPlayerFactory,
    /** Local file first, then the provider's preview URL; null = none offered. */
    private val resolve: suspend (DiscoverySong) -> Pair<String, Boolean>?,
    private val beforeStart: () -> Unit = {},
    private val timeoutMs: Long = 15_000,
) {
    private val mutable = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = mutable.asStateFlow()
    private var token = 0L
    private var job: Job? = null
    private var watchdog: Job? = null
    private var player: PreviewPlayer? = null

    fun toggle(song: DiscoverySong) {
        val s = mutable.value
        if (s.songId == song.key && (s.loading || s.playing)) stop() else start(song)
    }

    fun start(song: DiscoverySong) {
        stop()
        val mine = ++token
        mutable.value = PreviewState(song.key, loading = true)
        beforeStart()
        watchdog = scope.launch {
            delay(timeoutMs)
            if (mine == token && mutable.value.loading) fail(song, "Preview timed out. Try again.")
        }
        job = scope.launch {
            val source = try {
                withTimeout(timeoutMs) { resolve(song) }
            } catch (e: TimeoutCancellationException) {
                if (mine == token) fail(song, "Preview timed out. Try again."); return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (mine == token) fail(song, "Preview unavailable. Check your connection and retry."); return@launch
            }
            if (mine != token) return@launch
            if (source == null) {
                watchdog?.cancel()
                mutable.value = PreviewState(song.key, message = "No preview from this source. You can still keep the song.")
                return@launch
            }
            val (uri, fromLibrary) = source
            val next = factory.create()
            player = next
            next.play(uri, fromLibrary,
                onReady = {
                    if (mine == token) {
                        watchdog?.cancel()
                        mutable.value = PreviewState(song.key, playing = true,
                            message = if (fromLibrary) "From your library · 30 seconds" else "${song.provider} preview · up to 30 seconds")
                    }
                },
                onEnded = { if (mine == token) stop() },
                onError = { if (mine == token) fail(song, "Preview unavailable. Retry, or keep the song without listening.") },
            )
        }
    }

    /** The visible card changed: a clip for another song must stop now. */
    fun onFocus(key: String?) {
        val current = mutable.value.songId ?: return
        if (current != key) stop()
    }

    fun stop() {
        token++
        job?.cancel(); job = null
        watchdog?.cancel(); watchdog = null
        player?.release(); player = null
        mutable.value = PreviewState()
    }

    private fun fail(song: DiscoverySong, message: String) {
        token++
        job?.cancel(); job = null
        watchdog?.cancel(); watchdog = null
        player?.release(); player = null
        mutable.value = PreviewState(song.key, message = message)
    }
}
