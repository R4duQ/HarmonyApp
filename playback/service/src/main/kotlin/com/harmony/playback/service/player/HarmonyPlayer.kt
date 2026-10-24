package com.harmony.playback.service.player

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import com.harmony.core.eq.AudioOutputMonitor
import com.harmony.core.eq.CustomEqAudioProcessor
import com.harmony.core.eq.EqDebug
import com.harmony.core.eq.HiResEqAudioSink
import com.harmony.core.eq.OutputDeviceType
import com.harmony.core.eq.OutputRoute
import com.harmony.core.media.artwork.ArtworkCache
import com.harmony.core.media.artwork.ArtworkProvider
import com.harmony.core.model.EqSettings
import com.harmony.core.model.ReplayGainMode
import com.harmony.core.model.Song
import com.harmony.domain.playback.AudioLevels
import com.harmony.domain.playback.EqLive
import com.harmony.domain.playback.EqOutput
import com.harmony.domain.playback.DspTelemetrySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 *    only vary speed. With correction OFF we set pitch=speed for a "tape
 *    speed" effect. (On the EQ's float path AudioTrack applies both.)
 *  - The equalizer: the native float engine from core:eq-engine
 *    ([CustomEqAudioProcessor]) running in a [HiResEqAudioSink], so 24-bit and
 *    hi-res files reach it whole. It also carries ReplayGain (as a level gain
 *    ahead of its limiter), Auto (live spectral auto-calibration, in the engine), the
 *    output-device protection ([AudioOutputMonitor]) and the analyser the
 *    screens poll through [AudioLevels].
 *  - Crossfade: two players taking turns, see [TwinPlayer]. Each has its own
 *    engine, so the song coming in sounds exactly as it will once it plays
 *    alone; only the one playing reports to the screen.
 *
 * This class deliberately does NOT know about MediaSession, notifications,
 * Android Auto, or Smart Shuffle. It is a pure audio engine.
 */
@OptIn(UnstableApi::class)
class HarmonyPlayer(
    private val context: Context,
    private val artworkCache: ArtworkCache,
) {

    /** One ExoPlayer and the equalizer engine in its sink. */
    private class Engine(
        val exo: ExoPlayer,
        val equalizer: CustomEqAudioProcessor,
    )

    private val engines: List<Engine>

    /** The player everything uses: the session, the queue, the settings. */
    val player: TwinPlayer

    private var pitchCorrection: Boolean = true
    private var replayGainMode: ReplayGainMode = ReplayGainMode.OFF
    private var eq = EqSettings()

    /** Song metadata for the current queue, keyed by mediaId, for RG lookup. */
    private val songsById = mutableMapOf<String, Song>()

    /** Error-recovery budgets. Touched only on the player's application thread. */
    private var retriesForCurrentItem = 0
    private var failedItemsInARow = 0

    /** 200 ms hops past corrupt frames in the current item, and stall nudges; reset per item. */
    private var hopsForCurrentItem = 0
    private var nudgesForCurrentItem = 0

    /** Follows the output (speaker, wired, Bluetooth, USB DAC) for the engines. */
    private val outputMonitor: AudioOutputMonitor

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        engines = listOf(buildEngine(handleAudioFocus = true), buildEngine(handleAudioFocus = false))
        player = TwinPlayer(engines[0].exo, engines[1].exo)

        for (engine in engines) {
            // Each player sets its own song's level, the waiting one too when
            // it loads the next song, so it is ready before it is heard. Auto
            // needs no telling: it listens to whatever plays, continuously.
            engine.exo.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    engine.equalizer.setLevelGainDb(replayGainDbFor(engine.exo.currentMediaItem))
                }
            })
        }

        outputMonitor = AudioOutputMonitor(
            context,
            onBitPerfectChanged = ::onBitPerfectChanged,
            onRouteChanged = ::onRouteChanged,
        )
        outputMonitor.start()
        onRouteChanged(outputMonitor.route.value)
        startAnalyser()
        startEqStatus()
        startStallWatchdog()

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
                hopsForCurrentItem = 0
                nudgesForCurrentItem = 0
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Audio is flowing again, so whatever went wrong is behind
                // us and the queue-level budget can be handed back.
                if (playbackState == Player.STATE_READY) failedItemsInARow = 0
            }

            override fun onDeviceInfoChanged(deviceInfo: androidx.media3.common.DeviceInfo) = outputMonitor.refresh()

            override fun onPlayerError(error: PlaybackException) = recoverFrom(error)
        })
    }

    private fun buildEngine(handleAudioFocus: Boolean): Engine {
        val equalizer = CustomEqAudioProcessor().apply {
            setSpectrumBarCount(AudioLevels.SPECTRUM_BARS)
            setAnalyzerEnabled(false)
            setEqEnabled(false)
        }
        val exo = ExoPlayer.Builder(context, renderersFactory(equalizer))
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, resilientExtractors()))
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
        return Engine(exo, equalizer)
    }

    private val activeEngine: Engine get() = engines.firstOrNull { it.exo === player.active } ?: engines[0]

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
        EqDebug.decoderError(error.errorCodeName, position, error.cause?.message)

        // A corrupt frame in an offline file: hop 200 ms past it and play on,
        // rather than retry the same spot (which fails again) and lose the
        // whole song. A few hops per song at most; then the old budget applies.
        val duration = player.duration
        if (error.errorCode in CORRUPT_STRETCH_ERRORS && hopsForCurrentItem < MAX_HOPS_PER_ITEM &&
            (duration == C.TIME_UNSET || position + CORRUPT_HOP_MS < duration)
        ) {
            hopsForCurrentItem++
            EqDebug.decoderSkip(position, position + CORRUPT_HOP_MS)
            player.seekTo(index, position + CORRUPT_HOP_MS)
            player.prepare()
            return
        }

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
        for (engine in engines) engine.equalizer.setLevelGainDb(replayGainDbFor(engine.exo.currentMediaItem))
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

    // -- Equalizer -------------------------------------------------------------

    fun setEqualizer(settings: EqSettings) {
        val before = eq
        eq = settings
        val gains = EqSettings.sanitize(settings.bandGainsDb).toFloatArray()
        for (engine in engines) {
            // The sliders; with Auto on, the engine adds its live offsets to them.
            engine.equalizer.setBandGains(gains)
            engine.equalizer.setPreampDb(settings.preampDb)
            engine.equalizer.setEqEnabled(settings.enabled)
            engine.equalizer.setAutoMode(settings.enabled && settings.auto)
        }
        if (settings.bitPerfectUsb != before.bitPerfectUsb) applyBitPerfect()
    }

    /** Keeps the screen's hardware badge on the rate the playing engine runs at. */
    private fun startEqStatus() {
        scope.launch {
            while (true) {
                val rate = activeEngine.equalizer.sampleRate
                if (rate != EqLive.status.value.sampleRate) EqLive.update { it.copy(sampleRate = rate) }
                delay(STATUS_EVERY_MS)
            }
        }
    }

    // -- Output routing and bit-perfect USB -------------------------------------

    private fun onRouteChanged(route: OutputRoute) {
        for (engine in engines) engine.equalizer.setOutputDeviceType(route.type)
        EqLive.update {
            it.copy(
                output = when (route.type) {
                    OutputDeviceType.PHONE_SPEAKER -> EqOutput.PHONE_SPEAKER
                    OutputDeviceType.WIRED_HEADPHONES -> EqOutput.WIRED_HEADPHONES
                    OutputDeviceType.BLUETOOTH_A2DP -> EqOutput.BLUETOOTH
                    OutputDeviceType.USB_DAC_HIRES -> EqOutput.USB_DAC
                },
                outputName = route.name,
                bitPerfectAvailable = route.type == OutputDeviceType.USB_DAC_HIRES &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            )
        }
        applyBitPerfect()
    }

    /** Asks for bit-perfect USB when the listener wants it and a DAC is there; lets it go otherwise. */
    private fun applyBitPerfect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val want = eq.bitPerfectUsb && outputMonitor.route.value.type == OutputDeviceType.USB_DAC_HIRES
        if (want == outputMonitor.bitPerfectActive.value) return
        val result = runCatching { outputMonitor.setBitPerfectUsbMode(want) }
        Log.i(TAG, "Bit-perfect USB ${if (want) "on" else "off"}: ${result.getOrElse { it.toString() }}")
    }

    /** Bit-perfect switched: every engine steps aside (or back in), from a fresh AudioTrack. */
    private fun onBitPerfectChanged(active: Boolean) {
        for (engine in engines) engine.equalizer.bitPerfectPassthrough = active
        EqLive.update { it.copy(bitPerfect = active) }
        // The sink picks the path when it is configured: rebuild it where the song is.
        if (player.playbackState != Player.STATE_IDLE && player.mediaItemCount > 0) {
            player.stop()
            player.prepare()
        }
    }

    // -- Analyser ----------------------------------------------------------------

    /**
     * Hands the screens the playing engine's telemetry. They poll it once per
     * frame on the main thread (AudioLevels.pollTelemetry: effective gains,
     * FFT and live preamp in one native call); the engines only feed the
     * analyser while a screen is watching.
     */
    private fun startAnalyser() {
        AudioLevels.source = DspTelemetrySource { gains, bars, peaks ->
            activeEngine.equalizer.pollDspTelemetry(gains, bars, peaks)
        }
        scope.launch {
            AudioLevels.watchers.collect { count ->
                for (engine in engines) engine.equalizer.setAnalyzerEnabled(count > 0)
            }
        }
    }

    /**
     * Extractors that cope with real-world offline files: constant-bitrate
     * seeking for MP3/AAC/AMR files without a seek table (always, even where
     * the length is unknown), and index seeking for MP3s whose VBR headers are
     * missing or wrong, so a seek lands where it should instead of somewhere
     * the extractor then can't sync from. The MP3 and FLAC extractors already
     * resync past corrupt frames on their own.
     */
    private fun resilientExtractors(): DefaultExtractorsFactory = DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)
        .setConstantBitrateSeekingAlwaysEnabled(true)
        .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)

    /**
     * Playback that stands still with no error at all is what a stuck
     * decoder, extractor or audio path looks like: a position that doesn't
     * move while the player is supposed to play, until the listener seeks.
     * Once a second this checks for exactly that and, after a few seconds,
     * does what the listener would: seeks 200 ms on, which flushes the whole
     * chain. Never while playback is suppressed (a call, lost focus), paused,
     * or ended, and only a few times per song.
     */
    private fun startStallWatchdog() {
        scope.launch {
            var lastPosition = -1L
            var lastIndex = -1
            var stillSinceMs = 0L
            while (true) {
                delay(WATCHDOG_EVERY_MS)
                val state = player.playbackState
                val shouldPlay = player.playWhenReady &&
                    player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                    (state == Player.STATE_READY || state == Player.STATE_BUFFERING) &&
                    player.mediaItemCount > 0
                val index = player.currentMediaItemIndex
                val position = player.currentPosition
                if (!shouldPlay || index != lastIndex || position != lastPosition) {
                    lastIndex = index
                    lastPosition = position
                    stillSinceMs = 0L
                    continue
                }
                stillSinceMs += WATCHDOG_EVERY_MS
                if (stillSinceMs < STALL_AFTER_MS || nudgesForCurrentItem >= MAX_NUDGES_PER_ITEM) continue
                val duration = player.duration
                val target = position + CORRUPT_HOP_MS
                if (duration != C.TIME_UNSET && target >= duration) {
                    if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                } else {
                    player.seekTo(index, target)
                }
                nudgesForCurrentItem++
                EqDebug.playbackStall(
                    if (state == Player.STATE_BUFFERING) "buffering" else "ready, not advancing",
                    position, stillSinceMs, target,
                )
                stillSinceMs = 0L
            }
        }
    }

    private fun renderersFactory(equalizer: CustomEqAudioProcessor): DefaultRenderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink =
                // The EQ engine runs ahead of a float-output DefaultAudioSink:
                // Media3's own sink skips custom processors with float output,
                // and truncates to 16 bits before them without it.
                HiResEqAudioSink.create(context, equalizer)
        }.apply {
            // Prefer Media3's own decoders (FLAC, Opus, etc. via extensions if
            // present) before falling back to MediaCodec, for consistent
            // gapless behaviour across OEM codec implementations.
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            // A decoder that fails to start (or dies) hands over to the next one
            // that can play the format, instead of failing the song.
            setEnableDecoderFallback(true)
        }

    fun release() {
        scope.cancel()
        AudioLevels.source = null
        outputMonitor.stop()
        player.release()
        // The players are released, so their audio threads no longer touch the engines.
        for (engine in engines) engine.equalizer.release()
    }

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
        const val STATUS_EVERY_MS = 500L
        const val MAX_RETRIES_PER_ITEM = 1
        const val CORRUPT_HOP_MS = 200L
        const val MAX_HOPS_PER_ITEM = 10
        const val WATCHDOG_EVERY_MS = 1_000L
        const val STALL_AFTER_MS = 3_000L
        const val MAX_NUDGES_PER_ITEM = 5

        /** Errors that mean "this stretch of the file is bad", where hopping past it helps. */
        val CORRUPT_STRETCH_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        )
        const val MAX_FAILED_ITEMS_IN_A_ROW = 3
    }
}
