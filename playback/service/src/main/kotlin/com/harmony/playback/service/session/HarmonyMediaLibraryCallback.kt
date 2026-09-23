package com.harmony.playback.service.session

import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.harmony.core.model.ReplayGainMode
import com.harmony.core.model.SmartPlaylistType
import com.harmony.core.model.Song
import com.harmony.core.media.artwork.ArtworkCache
import com.harmony.domain.library.repository.FavoritesRepository
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.library.repository.PlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import com.harmony.playback.service.player.CrossfadeController
import com.harmony.playback.service.player.HarmonyPlayer
import com.harmony.playback.service.timer.SleepTimer
import javax.inject.Inject
import dagger.hilt.android.scopes.ServiceScoped

/**
 * Session callback: Android Auto browse tree + custom commands.
 *
 * Custom session commands are the transport for app-specific features that
 * Media3's Player interface has no verb for (crossfade, ReplayGain mode,
 * sleep timer, pitch correction). The in-app [PlaybackConnection] sends these;
 * external controllers (Auto, Bluetooth) simply never see them.
 *
 * The browse tree is backed by the library repository — see [BrowseTree] for
 * why it's shaped the way it is.
 */
@OptIn(UnstableApi::class)
@ServiceScoped
class HarmonyMediaLibraryCallback @Inject constructor(
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val favorites: FavoritesRepository,
    private val artworkCache: ArtworkCache,
) : MediaLibrarySession.Callback {

    /**
     * Browse requests are one-shot reads of flows that are otherwise
     * long-lived, so they get their own scope rather than borrowing the
     * service's — a browse must not outlive a disconnect, and cancelling
     * this scope can't affect playback.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Short-lived cache of whole browse lists, keyed by node id.
     *
     * Auto requests a node one PAGE at a time, so scrolling a long album grid
     * would otherwise re-run the same Room query and rebuild the same list on
     * every page. On an older head unit that is the difference between a
     * smooth scroll and a visible stall.
     *
     * Deliberately short: a scan finishing mid-drive should show up without
     * a reconnect, and this is only meant to span one browsing session.
     */
    private val cache = LinkedHashMap<String, CachedList>()

    private class CachedList(val items: List<MediaItem>, val at: Long)

    private suspend fun cached(key: String, build: suspend () -> List<MediaItem>): List<MediaItem> {
        val now = SystemClock.elapsedRealtime()
        synchronized(cache) { cache[key] }?.let { if (now - it.at < CACHE_TTL_MS) return it.items }
        val built = build()
        synchronized(cache) {
            cache[key] = CachedList(built, now)
            // Bounded: a big library browsed thoroughly would otherwise hold
            // every list it ever built.
            while (cache.size > CACHE_MAX_NODES) {
                cache.remove(cache.keys.first())
            }
        }
        return built
    }

    private var player: HarmonyPlayer? = null
    private var crossfade: CrossfadeController? = null
    private var sleepTimer: SleepTimer? = null

    fun attach(player: HarmonyPlayer, crossfade: CrossfadeController, sleepTimer: SleepTimer) {
        this.player = player
        this.crossfade = crossfade
        this.sleepTimer = sleepTimer
    }

    fun close() {
        scope.cancel()
        synchronized(cache) { cache.clear() }
        player = null
        crossfade = null
        sleepTimer = null
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            .buildUpon()
            .add(SessionCommand(CMD_SET_CROSSFADE, Bundle.EMPTY))
            .add(SessionCommand(CMD_SET_REPLAYGAIN, Bundle.EMPTY))
            .add(SessionCommand(CMD_SET_SLEEP_TIMER, Bundle.EMPTY))
            .add(SessionCommand(CMD_SET_PITCH_CORRECTION, Bundle.EMPTY))
            .add(SessionCommand(CMD_SET_EQ, Bundle.EMPTY))
            .add(SessionCommand(CMD_TOGGLE_FAVORITE, Bundle.EMPTY))
            .add(SessionCommand(CMD_SMART_SHUFFLE, Bundle.EMPTY))
            .build()

        // Custom buttons in the car's playback view. Along with the app
        // theme, this is the only other thing a projected media app
        // controls about its own appearance — the Auto host renders
        // everything else. Two, deliberately: Auto shows a small number
        // beside the transport, and picking the two that matter beats
        // offering six the driver has to read.
        val carButtons = ImmutableList.of(
            CommandButton.Builder()
                .setDisplayName("Favourite")
                .setIconResId(com.harmony.playback.service.R.drawable.ic_car_favorite)
                .setSessionCommand(SessionCommand(CMD_TOGGLE_FAVORITE, Bundle.EMPTY))
                .build(),
            CommandButton.Builder()
                .setDisplayName("Shuffle all")
                .setIconResId(com.harmony.playback.service.R.drawable.ic_car_shuffle)
                .setSessionCommand(SessionCommand(CMD_SMART_SHUFFLE, Bundle.EMPTY))
                .build(),
        )

        AutoDiagnostics.log("connect ${controller.packageName} (version ${controller.controllerVersion}) · accepted")
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setCustomLayout(carButtons)
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        // The two car buttons need a real implementation, not just a slot in
        // the layout, or they'd be dead controls on the head unit.
        when (customCommand.customAction) {
            CMD_TOGGLE_FAVORITE -> {
                // Bare id — see BrowseTree.songId.
                val id = session.player.currentMediaItem?.mediaId?.toLongOrNull()
                if (id != null) scope.launch { favorites.toggle(id) }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            CMD_SMART_SHUFFLE -> {
                // Reshuffles the whole library into the queue. Note this is
                // a plain shuffle, NOT the app's similarity-based Smart
                // Shuffle: that runs through the shuffle domain module,
                // which this callback has no handle on. Labelled honestly
                // as "Shuffle all" rather than borrowing the better name.
                scope.launch {
                    val items = library.observeSongs().first()
                        .shuffled().take(MAX_ITEMS).map(BrowseTree::playable)
                    if (items.isEmpty()) return@launch
                    withContext(Dispatchers.Main) {
                        session.player.setMediaItems(items)
                        session.player.prepare()
                        session.player.play()
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }
        when (customCommand.customAction) {
            CMD_SET_CROSSFADE ->
                crossfade?.crossfadeSeconds = args.getInt(ARG_SECONDS, 0)
            CMD_SET_REPLAYGAIN ->
                player?.setReplayGainMode(
                    ReplayGainMode.entries[args.getInt(ARG_MODE, 0)]
                )
            CMD_SET_SLEEP_TIMER ->
                sleepTimer?.set(
                    durationMs = args.getLong(ARG_DURATION_MS, -1L).takeIf { it > 0 },
                    finishTrack = args.getBoolean(ARG_FINISH_TRACK, false),
                )
            CMD_SET_PITCH_CORRECTION ->
                player?.setPitchCorrection(args.getBoolean(ARG_ENABLED, true))
            CMD_SET_EQ -> player?.setEqualizer(
                com.harmony.core.model.EqSettings(
                    enabled = args.getBoolean(ARG_ENABLED, false),
                    bandGainsDb = (args.getFloatArray(ARG_EQ_BANDS) ?: FloatArray(10)).toList(),
                    bassBoostDb = args.getFloat(ARG_EQ_BASS, 0f),
                    trebleBoostDb = args.getFloat(ARG_EQ_TREBLE, 0f),
                )
            )
            else -> return Futures.immediateFuture(
                SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
            )
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    // -- Android Auto browse tree -------------------------------------------

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        AutoDiagnostics.log("disconnect ${controller.packageName}")
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        AutoDiagnostics.log("root requested by ${browser.packageName} · recent=${params?.isRecent} offline=${params?.isOffline}")
        val root = BrowseTree.browsable(
            id = BrowseTree.ROOT,
            title = "Harmony",
            extras = BrowseTree.listExtras(),
        )
        // Declared on the ROOT params too: some Auto hosts read the style
        // capability here rather than from the item.
        val rootParams = LibraryParams.Builder()
            .setExtras(BrowseTree.listExtras())
            .build()
        return Futures.immediateFuture(LibraryResult.ofItem(root, rootParams))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
      AutoDiagnostics.timed("children of $parentId for ${browser.packageName}") {
        val all: List<MediaItem> = cached(parentId) {
          when (val node = BrowseTree.parse(parentId)) {
            BrowseTree.Node.Root -> rootChildren()

            // Shuffle is a top-level ACTION, not a folder. Auto still asks
            // for its children when the row is tapped as browsable, so it
            // returns the shuffled songs — the controller plays the first
            // and queues the rest.
            BrowseTree.Node.ShuffleAll ->
                library.observeSongs().first().shuffled().take(MAX_ITEMS)
                    .map(BrowseTree::playable)

            BrowseTree.Node.Recent ->
                playlists.observeSmartPlaylist(SmartPlaylistType.RECENTLY_PLAYED, MAX_ITEMS)
                    .first().map(BrowseTree::playable)

            // Tighter cap than a text list: every tile in a circular grid
            // costs an artwork decode on the head unit, so this is the
            // heaviest node in the tree.
            // Songs are bucketed by first letter rather than served as one
            // flat list. With thousands of tracks a single node is both
            // unscrollable in a car and slow to build; a bucket is at most
            // a few hundred, and the letters themselves are a usable index.
            BrowseTree.Node.Songs -> {
                val letters = library.observeSongs().first()
                    .map { bucketOf(it.title) }
                    .distinct()
                    .sorted()
                letters.map { letter ->
                    BrowseTree.browsable(
                        id = BrowseTree.letterId(letter),
                        title = letter,
                        extras = BrowseTree.listExtras(),
                    )
                }
            }

            is BrowseTree.Node.OneLetter ->
                library.observeSongs().first()
                    .filter { bucketOf(it.title) == node.letter }
                    .sortedBy { it.title.lowercase() }
                    .take(MAX_ITEMS)
                    .map { BrowseTree.playable(it) }

            BrowseTree.Node.Albums ->
                library.observeAlbums().first().take(MAX_GRID_ITEMS)
                    .map(BrowseTree::albumItem)

            BrowseTree.Node.Artists ->
                library.observeArtists().first().take(MAX_GRID_ITEMS)
                    .map(BrowseTree::artistItem)

            BrowseTree.Node.Playlists -> buildList {
                // Smart lists first: they always exist and always have
                // content, so they're the reliable thing to reach for while
                // driving. User playlists follow.
                SmartPlaylistType.entries.forEach { type ->
                    add(
                        BrowseTree.browsable(
                            id = BrowseTree.smartId(type.name),
                            title = type.label(),
                            subtitle = "Updates itself",
                            playable = true,
                        )
                    )
                }
                playlists.observePlaylists().first().forEach { playlist ->
                    add(
                        BrowseTree.browsable(
                            id = BrowseTree.playlistId(playlist.id),
                            title = playlist.name,
                            subtitle = "${playlist.songCount} songs",
                            mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            playable = true,
                        )
                    )
                }
            }

            is BrowseTree.Node.OneAlbum ->
                library.observeSongsByAlbum(node.id).first().map(BrowseTree::playable)

            is BrowseTree.Node.OneArtist ->
                library.observeSongsByArtist(node.name).first().map(BrowseTree::playable)

            is BrowseTree.Node.OnePlaylist ->
                playlists.observePlaylistSongs(node.id).first().map(BrowseTree::playable)

            is BrowseTree.Node.OneSmart ->
                songsForSmart(node.type).map(BrowseTree::playable)

            is BrowseTree.Node.OneSong, BrowseTree.Node.Unknown -> emptyList()
          }
        }

        // Honour the requested page. Ignoring it (the previous behaviour)
        // meant every browse marshalled the entire list — hundreds of items
        // and their artwork — across the binder at once, which is precisely
        // what makes an older head unit stutter.
        val items = if (pageSize > 0) {
            all.drop(page * pageSize).take(pageSize)
        } else {
            all.take(MAX_ITEMS)
        }
        LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
      }
    }

    /**
     * Voice and text search from the car.
     *
     * Neither of these existed, so "play X" from the steering wheel found
     * nothing at all. Auto calls onSearch to say a query was made, then
     * onGetSearchResult for the results; both have to answer or the car
     * shows an empty screen.
     *
     * Songs come first because a spoken query is nearly always a song.
     */
    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = scope.future {
        val hits = library.search(query).first()
        val total = hits.songs.size + hits.albums.size + hits.artists.size
        session.notifySearchResultChanged(browser, query, total, params)
        LibraryResult.ofVoid()
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val hits = library.search(query).first()
        val all = buildList {
            addAll(hits.songs.map { BrowseTree.playable(it) })
            addAll(hits.albums.map(BrowseTree::albumItem))
            addAll(hits.artists.map(BrowseTree::artistItem))
        }
        val items = if (pageSize > 0) {
            all.drop(page * pageSize).take(pageSize)
        } else {
            all.take(MAX_ITEMS)
        }
        LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        val song = (BrowseTree.parse(mediaId) as? BrowseTree.Node.OneSong)
            ?.let { library.songById(it.id) }
        if (song == null) {
            LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        } else {
            LibraryResult.ofItem(BrowseTree.playable(song), null)
        }
    }

    /**
     * Auto hands back the browse item the user tapped, which carries a media
     * id but no URI — the player can't do anything with that. This resolves
     * each one into a real, playable item.
     *
     * A tap on a browsable-and-playable row (an album, a playlist) arrives
     * here as that single item, so it's expanded into the whole list. That's
     * what makes one tap play a release rather than a track.
     */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = scope.future {
        val resolved = mutableListOf<MediaItem>()
        for (item in mediaItems) {
            // An item that already carries a URI came from the in-app
            // player, which knows what it's doing; pass it through.
            if (item.localConfiguration != null) {
                resolved += item
                continue
            }
            // vinyl = true: these become the QUEUE, so their artwork is what
            // the now-playing view shows at full size — big enough for the
            // record to read. Browse rows keep the plain cover.
            resolved += expand(item.mediaId, vinyl = true)
        }
        withArtworkBytes(resolved, 0).toMutableList()
    }

    /**
     * Attaches artwork BYTES to one item — the one that is about to play.
     *
     * Bytes travel inside the binder transaction, so they need no URI
     * permission and no provider cooperation, which makes them the reliable
     * way to get a cover onto the car's now-playing view. They're applied to
     * exactly one item because a whole list of them would exceed the ~1 MB
     * transaction limit and throw.
     */
    private fun withArtworkBytes(items: List<MediaItem>, index: Int): List<MediaItem> {
        if (index !in items.indices) return items
        val target = items[index]
        val songId = target.mediaId.toLongOrNull() ?: return items
        val artworkUri = target.mediaMetadata.artworkUri ?: return items
        if (artworkUri.authority != com.harmony.core.media.artwork.ArtworkProvider.AUTHORITY) return items
        val bytes = artworkCache.bytes(songId, userArtwork = artworkUri.getQueryParameter("user") == "true") ?: return items
        return items.toMutableList().also {
            it[index] = target.buildUpon()
                .setMediaMetadata(
                    target.mediaMetadata.buildUpon()
                        .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build()
                )
                .build()
        }
    }

    /**
     * Called when a controller sets a whole queue with a starting position —
     * which is what Android Auto does when you tap a row in a list.
     *
     * The previous code only implemented onAddMediaItems and attached
     * artwork to index 0, so tapping the fifth song in a bucket put the
     * cover on the first song and left the playing one blank.
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
        val resolved = mutableListOf<MediaItem>()
        for (item in mediaItems) {
            if (item.localConfiguration != null) resolved += item
            else resolved += expand(item.mediaId, vinyl = true)
        }
        val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        MediaSession.MediaItemsWithStartPosition(
            withArtworkBytes(resolved, index),
            index,
            startPositionMs,
        )
    }

    private suspend fun expand(mediaId: String, vinyl: Boolean = false): List<MediaItem> =
        when (val node = BrowseTree.parse(mediaId)) {
            is BrowseTree.Node.OneSong ->
                listOfNotNull(library.songById(node.id)?.let { BrowseTree.playable(it, vinyl) })
            is BrowseTree.Node.OneAlbum ->
                library.observeSongsByAlbum(node.id).first()
                    .map { BrowseTree.playable(it, vinyl) }
            is BrowseTree.Node.OneArtist ->
                library.observeSongsByArtist(node.name).first()
                    .map { BrowseTree.playable(it, vinyl) }
            is BrowseTree.Node.OnePlaylist ->
                playlists.observePlaylistSongs(node.id).first()
                    .map { BrowseTree.playable(it, vinyl) }
            is BrowseTree.Node.OneSmart ->
                songsForSmart(node.type).map { BrowseTree.playable(it, vinyl) }
            is BrowseTree.Node.OneLetter ->
                library.observeSongs().first()
                    .filter { bucketOf(it.title) == node.letter }
                    .sortedBy { it.title.lowercase() }
                    .take(MAX_ITEMS)
                    .map { BrowseTree.playable(it, vinyl) }
            BrowseTree.Node.ShuffleAll ->
                library.observeSongs().first().shuffled().take(MAX_ITEMS)
                    .map { BrowseTree.playable(it, vinyl) }
            BrowseTree.Node.Recent ->
                playlists.observeSmartPlaylist(SmartPlaylistType.RECENTLY_PLAYED, MAX_ITEMS)
                    .first().map { BrowseTree.playable(it, vinyl) }
            else -> emptyList()
        }

    private suspend fun rootChildren(): List<MediaItem> = listOf(
        // Order is the whole design: the most common in-car intent is "just
        // play something", so it comes first.
        BrowseTree.browsable(
            id = BrowseTree.SHUFFLE_ALL,
            title = "Shuffle everything",
            subtitle = "Play your whole library",
            playable = true,
            extras = BrowseTree.listExtras(),
        ),
        BrowseTree.browsable(
            id = BrowseTree.RECENT,
            title = "Recently played",
            playable = true,
            extras = BrowseTree.listExtras(),
        ),
        BrowseTree.browsable(
            id = BrowseTree.PLAYLISTS,
            title = "Playlists",
            extras = BrowseTree.listExtras(),
        ),
        BrowseTree.browsable(
            id = BrowseTree.SONGS,
            title = "Songs",
            subtitle = "All tracks, A to Z",
            extras = BrowseTree.listExtras(),
        ),
        // Albums and artists render as circular tiles — the closest the
        // platform gets to the phone's record view.
        BrowseTree.browsable(
            id = BrowseTree.ALBUMS,
            title = "Albums",
            extras = BrowseTree.circularGridExtras(),
        ),
        BrowseTree.browsable(
            id = BrowseTree.ARTISTS,
            title = "Artists",
            extras = BrowseTree.circularGridExtras(),
        ),
    )

    /**
     * First letter, uppercased, with everything non-alphabetic collected
     * under "#" — so numeric and symbol-led titles get one bucket instead of
     * a scatter of single-entry ones.
     */
    private fun bucketOf(title: String): String {
        val ch = title.trimStart().firstOrNull()?.uppercaseChar() ?: return "#"
        return if (ch in 'A'..'Z') ch.toString() else "#"
    }

    private suspend fun songsForSmart(typeName: String): List<Song> {
        val type = SmartPlaylistType.entries.firstOrNull { it.name == typeName }
            ?: return emptyList()
        return playlists.observeSmartPlaylist(type, MAX_ITEMS).first()
    }

    private fun SmartPlaylistType.label(): String = when (this) {
        SmartPlaylistType.FAVORITES -> "Favorites"
        SmartPlaylistType.MOST_PLAYED -> "Most played"
        SmartPlaylistType.RECENTLY_ADDED -> "Recently added"
        SmartPlaylistType.RECENTLY_PLAYED -> "Recently played"
        SmartPlaylistType.HIGHEST_ENERGY -> "High energy"
        SmartPlaylistType.LOWEST_ENERGY -> "Low energy"
    }

    companion object {
        const val ROOT_ID = BrowseTree.ROOT

        /**
         * Cap on items in any one browse list. Auto's own guidance is to
         * keep lists short, the screen shows a handful at a time, and a
         * 20k-song list would be both unusable and slow to marshal across
         * the binder.
         */
        private const val MAX_ITEMS = 300

        /**
         * Cap for artwork-bearing grids. Lower than [MAX_ITEMS] because each
         * tile is an image the car has to fetch and decode, and older units
         * (a 2018 MIB2, say) have little headroom for that.
         */
        private const val MAX_GRID_ITEMS = 100

        /** Browse lists are rebuilt at most this often. */
        private const val CACHE_TTL_MS = 30_000L

        /** Cached node lists retained at once. */
        private const val CACHE_MAX_NODES = 12

        const val CMD_TOGGLE_FAVORITE = "com.harmony.TOGGLE_FAVORITE"
        const val CMD_SMART_SHUFFLE = "com.harmony.SMART_SHUFFLE"

        const val CMD_SET_CROSSFADE = "com.harmony.SET_CROSSFADE"
        const val CMD_SET_REPLAYGAIN = "com.harmony.SET_REPLAYGAIN"
        const val CMD_SET_SLEEP_TIMER = "com.harmony.SET_SLEEP_TIMER"
        const val CMD_SET_PITCH_CORRECTION = "com.harmony.SET_PITCH_CORRECTION"
        const val CMD_SET_EQ = "com.harmony.SET_EQ"

        const val ARG_SECONDS = "seconds"
        const val ARG_MODE = "mode"
        const val ARG_DURATION_MS = "durationMs"
        const val ARG_FINISH_TRACK = "finishTrack"
        const val ARG_ENABLED = "enabled"
        const val ARG_EQ_BANDS = "eqBands"
        const val ARG_EQ_BASS = "eqBass"
        const val ARG_EQ_TREBLE = "eqTreble"
    }
}
