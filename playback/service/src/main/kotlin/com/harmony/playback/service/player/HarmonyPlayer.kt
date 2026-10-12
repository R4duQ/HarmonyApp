package com.harmony.playback.service.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.harmony.core.media.artwork.ArtworkCache
import com.harmony.core.media.artwork.ArtworkProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.harmony.core.model.ReplayGainMode
import com.harmony.core.model.Song

/**
 * Owner of the two [ExoPlayer]s behind [player] and everything that must be
 * configured at construction time (audio sink, renderers, attributes).
 *
 * Responsibilities:
 *  - Gapless playback: ExoPlayer plays adjacent items in a playlist gaplessly
 *    by default for formats carrying gapless metadata (MP3 LAME/iTunes tags,
 *    AAC) and inherently sample-accurate formats (FLAC/WAV/ALAC). We keep a
 *    single playlist-based queue (never one-item-at-a-time) so this works.
 *  - Speed & pitch: [setSpeed]. With pitch correction ON we set pitch=1.0 and
 *    only vary speed (ExoPlayer's Sonic-based time stretching preserves
 *    pitch). With correction OFF we set pitch=speed for a "tape speed" effect.
 *  - ReplayGain: implemented as a custom [ReplayGainAudioProcessor] inserted
 *    into the audio sink chain, fed per-track gain values when the current
 *    item changes. Applying gain in the processor (float PCM domain) avoids
 *    the precision loss and pop artifacts of hot-swapping player volume.
 *  - Crossfade: two players taking turns, see [TwinPlayer]. Each has its own
 *    ReplayGain, EQ and level meter, so the song coming in sounds exactly as
 *    it will once it plays alone; only the one playing reports to the screen.
 *
 * This class deliberately does NOT know about MediaSession, notifications,
 * Android Auto, or Smart Shuffle. It is a pure audio engine.
 */
@OptIn(UnstableApi::class)
class HarmonyPlayer(
    private val context: Context,
    private val artworkCache: ArtworkCache,
) {

    /** One ExoPlayer and the audio processors in its sink. */
    private class Engine(
        val exo: ExoPlayer,
        val replayGain: ReplayGainAudioProcessor,
        val equalizer: EqualizerAudioProcessor,
        val levelMeter: LevelMeterAudioProcessor,
    )

    private val engines: List<Engine>

    /** The player everything uses: the session, the queue, the settings. */
    val player: TwinPlayer

    private var pitchCorrection: Boolean = true
    private var replayGainMode: ReplayGainMode = ReplayGainMode.OFF

    /** Song metadata for the current queue, keyed by mediaId, for RG lookup. */
    private val songsById = mutableMapOf<String, Song>()

    /** Error-recovery budgets. Touched only on the player's application thread. */
    private var retriesForCurrentItem = 0
    private var failedItemsInARow = 0

    init {
        engines = listOf(buildEngine(handleAudioFocus = true), buildEngine(handleAudioFocus = false))
        player = TwinPlayer(engines[0].exo, engines[1].exo)
        publishFrom(engines[0].exo)
        player.onActiveChanged = ::publishFrom

        for (engine in engines) {
            // Each player sets its own song's gain and EQ, the waiting one too
            // when it loads the next song, so it is ready before it is heard.
            engine.exo.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    engine.replayGain.setGainDb(replayGainDbFor(engine.exo.currentMediaItem))
                    engine.equalizer.setTrack(mediaItem?.mediaId)
                }
            })
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                // A MediaController changes speed directly on the player.
                // Keep its pitch consistent without resetting that speed.
                applyPlaybackParameters()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // A new item gets a fresh retry budget. Retrying the SAME
                // item calls prepare() without a transition, so the counter
                // only resets when we genuinely move on.
                retriesForCurrentItem = 0
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Audio is flowing again, so whatever went wrong is behind
                // us and the queue-level budget can be handed back.
                if (playbackState == Player.STATE_READY) failedItemsInARow = 0
            }

            override fun onPlayerError(error: PlaybackException) = recoverFrom(error)
        })
    }

    private fun buildEngine(handleAudioFocus: Boolean): Engine {
        val replayGain = ReplayGainAudioProcessor()
        val equalizer = EqualizerAudioProcessor()
        val levelMeter = LevelMeterAudioProcessor()
        // Meter LAST: it should report what actually reaches the speaker, so
        // it has to sit after ReplayGain's scaling and the EQ's filtering
        // rather than measuring the raw decode.
        val exo = ExoPlayer.Builder(context, renderersFactory(arrayOf(replayGain, equalizer, levelMeter)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                handleAudioFocus,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        return Engine(exo, replayGain, equalizer, levelMeter)
    }

    /** The visualiser and the Auto EQ readout follow the player that plays. */
    private fun publishFrom(active: ExoPlayer) {
        for (engine in engines) {
            val publishes = engine.exo === active
            engine.equalizer.publishesAutoEq = publishes
            engine.levelMeter.publishing = publishes
        }
    }

    /**
     * Puts the player back to work after an error instead of leaving it dead.
     *
     * Without this the player parked in STATE_IDLE the moment anything went
     * wrong — a bad frame in a FLAC, a decoder giving up, a file replaced
     * underneath us by a download, an audio-sink write failing across a
     * Bluetooth route change. Nothing ever called prepare() again, so the
     * song simply stopped. Media3 then dropped the media notification, the
     * service left the foreground, and Android reclaimed the process: from
     * the outside, the app "closed" on its own.
     *
     * Recovery is deliberately budgeted in two directions. One retry of the
     * same item handles the transient case (route changes, a momentary sink
     * failure) where re-preparing at the same position is exactly right.
     * Past that the item is treated as genuinely broken and skipped. And if
     * items keep failing one after another we stop rather than sprint
     * through the whole queue burning CPU on a problem that clearly isn't
     * per-file — a queue of unreadable files should go quiet, not spin.
     */
    private fun recoverFrom(error: PlaybackException) {
        val index = player.currentMediaItemIndex
        val position = player.currentPosition.coerceAtLeast(0)
        Log.e(
            TAG,
            "Playback error on item $index (${error.errorCodeName}); " +
                "retries=$retriesForCurrentItem failedInARow=$failedItemsInARow",
            error,
        )

        if (failedItemsInARow >= MAX_FAILED_ITEMS_IN_A_ROW) {
            Log.e(TAG, "Giving up: $failedItemsInARow items failed in a row.")
            return
        }

        if (retriesForCurrentItem < MAX_RETRIES_PER_ITEM) {
            retriesForCurrentItem++
            // Seeking is legal in STATE_IDLE; the position is applied when
            // prepare() re-acquires the source, so a transient failure
            // resumes where it stopped rather than at the top of the track.
            player.seekTo(index, position)
            player.prepare()
            return
        }

        retriesForCurrentItem = 0
        failedItemsInARow++
        if (player.hasNextMediaItem()) {
            Log.w(TAG, "Item $index unplayable after $MAX_RETRIES_PER_ITEM retries; skipping.")
            player.seekToNextMediaItem()
            player.prepare()
        } else {
            Log.e(TAG, "Item $index unplayable and nothing follows it; stopping.")
        }
    }

    // -- Queue ---------------------------------------------------------------

    fun setQueue(songs: List<Song>, startIndex: Int, playWhenReady: Boolean) {
        songsById.clear()
        songs.forEach { songsById[it.id.toString()] = it }
        player.setMediaItems(songs.map { it.toMediaItem() }, startIndex, C.TIME_UNSET)
        player.playWhenReady = playWhenReady
        player.prepare()
        applyReplayGainForCurrentItem()
    }

    fun addToQueue(song: Song) {
        songsById[song.id.toString()] = song
        player.addMediaItem(song.toMediaItem())
    }

    fun addNext(song: Song) {
        songsById[song.id.toString()] = song
        val insertAt = (player.currentMediaItemIndex + 1)
            .coerceAtMost(player.mediaItemCount)
        player.addMediaItem(insertAt, song.toMediaItem())
    }

    // -- Speed / pitch -------------------------------------------------------

    fun setSpeed(newSpeed: Float) {
        val speed = newSpeed.takeIf { it.isFinite() }?.coerceIn(0.25f, 3.0f) ?: 1f
        player.playbackParameters = PlaybackParameters(speed, if (pitchCorrection) 1f else speed)
    }

    fun setPitchCorrection(enabled: Boolean) {
        pitchCorrection = enabled
        applyPlaybackParameters()
    }

    private fun applyPlaybackParameters() {
        val speed = player.playbackParameters.speed
        val pitch = if (pitchCorrection) 1.0f else speed
        if (player.playbackParameters.pitch != pitch) {
            player.playbackParameters = PlaybackParameters(speed, pitch)
        }
    }

    // -- ReplayGain ----------------------------------------------------------

    fun setReplayGainMode(mode: ReplayGainMode) {
        replayGainMode = mode
        applyReplayGainForCurrentItem()
    }

    private fun applyReplayGainForCurrentItem() {
        for (engine in engines) engine.replayGain.setGainDb(replayGainDbFor(engine.exo.currentMediaItem))
    }

    private fun replayGainDbFor(item: MediaItem?): Float {
        val song = item?.mediaId?.let(songsById::get)
        val extras = item?.mediaMetadata?.extras
        val track = ReplayGainMetadata.gain(extras, ReplayGainMetadata.TRACK) ?: song?.replayGainTrackDb
        val album = ReplayGainMetadata.gain(extras, ReplayGainMetadata.ALBUM) ?: song?.replayGainAlbumDb
        val gainDb = when (replayGainMode) {
            ReplayGainMode.OFF -> null
            ReplayGainMode.TRACK -> track
            ReplayGainMode.ALBUM -> album ?: track
        }
        return gainDb ?: 0f
    }

    fun setEqualizer(settings: com.harmony.core.model.EqSettings) {
        for (engine in engines) engine.equalizer.apply(settings)
    }

    private fun renderersFactory(processors: Array<AudioProcessor>): DefaultRenderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    // Float output must be OFF: Media3's DefaultAudioSink
                    // BYPASSES the custom audio-processor chain entirely when
                    // float output is enabled — which silently disabled both
                    // ReplayGain and the EQ from day one (the original comment
                    // here claimed the opposite; it was wrong). With float
                    // output off, processors receive 16-bit PCM and actually
                    // run; both processors convert to float internally for
                    // their math, so quality is preserved where it matters.
                    .setEnableFloatOutput(false)
                    .setAudioProcessors(processors)
                    .build()
            }
        }.apply {
            // Prefer Media3's own decoders (FLAC, Opus, etc. via extensions if
            // present) before falling back to MediaCodec, for consistent
            // gapless behaviour across OEM codec implementations.
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }

    fun release() = player.release()

    /**
     * These are the items the player actually holds, so this metadata is
     * what every controller sees — the in-app UI, the notification, and the
     * Android Auto host.
     *
     * Artwork therefore cannot use `artworkUri` directly: that's a file://
     * path into this app's private storage. Coil reads it fine in-process,
     * which is why the phone looks right, but every out-of-process
     * controller sees an unreadable path and renders a blank square.
     *
     * So artwork goes out two ways at once. A content:// URI from
     * ArtworkProvider, which any process can resolve; and the bytes
     * themselves, which travel inside the binder transaction and need no
     * permission at all. Controllers take whichever they prefer, and the
     * bytes make the common case work even where the URI is refused.
     *
     * Bytes are scaled and attached per item because a queue is set one
     * item at a time here, not marshalled as one giant list — so the
     * transaction limit that rules bytes out for browse lists doesn't
     * apply.
     */
    private fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setExtras(ReplayGainMetadata.from(this))
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setAlbumArtist(albumArtist)
                .setArtworkUri(outOfProcessArtworkUri(this))
                .setArtworkData(
                    artworkUri?.let { artworkCache.bytes(id, userArtwork = it.endsWith("-user.img")) },
                    MediaMetadata.PICTURE_TYPE_FRONT_COVER,
                )
                .setTrackNumber(trackNumber)
                .setDiscNumber(discNumber)
                .build(),
        )
        .build()

    private fun outOfProcessArtworkUri(song: Song): android.net.Uri? = when {
        song.artworkUri != null ->
            android.net.Uri.parse(ArtworkProvider.artUri(song.id, song.artworkUri))
        // No embedded art extracted, but MediaStore may still hold a cover
        // for the album — and that one is public.
        song.albumId > 0 -> android.net.Uri.parse(
            "content://media/external/audio/albumart/${song.albumId}"
        )
        else -> null
    }

    private companion object {
        const val TAG = "HarmonyPlayback"
        const val MAX_RETRIES_PER_ITEM = 1
        const val MAX_FAILED_ITEMS_IN_A_ROW = 3
    }
}
