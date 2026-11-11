package com.harmony.domain.online

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The parts of an InnerTube answer Harmony reads, transcribed from
 * ytmusicapi 1.12.3 (`parsers/search.py`, `parsers/songs.py`,
 * `parsers/watch.py`, `navigation.py`): the same paths, the same rules for
 * telling an artist from an album, a duration, a year or a view count. The
 * conformance test (YtmConformanceTest) checks the output against
 * ytmusicapi's own on the same recorded answers.
 *
 * Harmony keeps less than ytmusicapi (no feedback tokens, no thumbnails) and
 * reads one thing more: the explicit badge of a radio track, which
 * ytmusicapi does not parse there.
 *
 * A missing optional field is null. A missing structural node (the catalog
 * changed its layout) is [SchemaException].
 */
internal object YtmParsers {
    class SchemaException(val where: String) : Exception("unexpected answer structure at $where")

    private const val MRLIR = "musicResponsiveListItemRenderer"
    private const val PPVR = "playlistPanelVideoRenderer"
    private const val PPVWR = "playlistPanelVideoWrapperRenderer"
    private val DOT_SEPARATOR = " • "
    private val DURATION = Regex("^(\\d+:)*\\d+:\\d+$")
    private val YEAR = Regex("^\\d{4}$")
    private val VIEWS_PREFIX = Regex("(?U)^\\D*?[\\s:\\uff1a\\u200e-\\u200f\\u202a-\\u202e]")
    private val LATIN = Regex("[a-zA-Z]")

    // -- search(query, filter="songs") ---------------------------------------------------

    /**
     * ytmusicapi `search(..., filter="songs")` on one answer page: the shelves
     * whose title names songs (or the untitled item sections), each item read
     * as a song. "No results" is an empty list.
     */
    fun songSearch(root: JsonObject): List<CatalogTrack> {
        val contents = root["contents"] as? JsonObject ?: return emptyList()
        val results = if ("tabbedSearchResultsRenderer" in contents) {
            nav(contents, "tabbedSearchResultsRenderer", "tabs", 0, "tabRenderer", "content")
                ?: throw SchemaException("search.tabs")
        } else contents
        val sectionList = nav(results, "sectionListRenderer", "contents") as? JsonArray
            ?: throw SchemaException("search.sectionListRenderer")
        if (sectionList.size == 1 && (sectionList[0] as? JsonObject)?.containsKey("itemSectionRenderer") == true &&
            !hasListItems(nav(sectionList[0], "itemSectionRenderer", "contents"))
        ) return emptyList()
        val out = mutableListOf<CatalogTrack>()
        for (res in sectionList) {
            val obj = res as? JsonObject ?: continue
            val (shelf, category) = when {
                "musicShelfRenderer" in obj -> {
                    val items = nav(obj, "musicShelfRenderer", "contents") as? JsonArray ?: continue
                    items to text(nav(obj, "musicShelfRenderer", "title", "runs", 0, "text"))
                }
                "itemSectionRenderer" in obj -> {
                    val items = nav(obj, "itemSectionRenderer", "contents") as? JsonArray ?: continue
                    items to null
                }
                // The top-result card: never typed reliably as a song in a filtered search.
                else -> continue
            }
            if (!hasListItems(shelf)) continue
            // As ytmusicapi: with a filter, a titled shelf of another kind is skipped.
            if (category != null && !category.lowercase().contains("song")) continue
            for (item in shelf) {
                val data = (item as? JsonObject)?.get(MRLIR) as? JsonObject ?: continue
                songResult(data)?.let(out::add)
            }
        }
        return out
    }

    private fun hasListItems(items: JsonElement?): Boolean =
        ((items as? JsonArray)?.firstOrNull() as? JsonObject)?.containsKey(MRLIR) == true

    /** ytmusicapi `parse_search_result(data, "song", category)`, the fields Harmony keeps. */
    private fun songResult(data: JsonObject): CatalogTrack? {
        val playNav = nav(data, "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint")
        val videoType = text(nav(playNav, "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType"))
        val videoId = text(nav(playNav, "watchEndpoint", "videoId")) ?: return null
        val title = flexText(data, 0, 0) ?: return null
        val runs = flexRuns(data, 1)?.toMutableList() ?: throw SchemaException("search.flexColumns[1]")
        flexRuns(data, 2)?.let { more -> runs += JsonObject(mapOf("text" to JsonPrimitive(""))); runs += more }
        val info = songRuns(runs, skipTypeSpec = true)
        val policy = text(data["musicItemRendererDisplayPolicy"])
        val badge = text(nav(data, "badges", 0, "musicInlineBadgeRenderer", "accessibilityData", "accessibilityData", "label"))
        return CatalogTrack(
            videoId = videoId,
            title = title,
            artists = info.artists,
            album = info.album,
            albumId = info.albumId,
            durationMs = info.durationSeconds?.times(1000L),
            kind = CatalogKind.of(videoType),
            // ytmusicapi: isExplicit = a badge label is present.
            explicit = badge != null,
            playable = policy != "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT",
            year = info.year,
            videoType = videoType,
        )
    }

    // -- get_watch_playlist(videoId, radio=True) -----------------------------------------

    /** The panel of a first `next` answer, or null when the answer has none (ytmusicapi: "No content returned"). */
    fun watchPanel(root: JsonObject): JsonObject? {
        val renderer = nav(root, "contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer")
            ?: throw SchemaException("next.watchNextTabbedResultsRenderer")
        return nav(renderer, "tabs", 0, "tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer") as? JsonObject
    }

    /** The panel of a continuation answer (`continuationContents.playlistPanelContinuation`), or null. */
    fun continuationPanel(root: JsonObject): JsonObject? =
        nav(root, "continuationContents", "playlistPanelContinuation") as? JsonObject

    /** ytmusicapi `get_continuation_params(results, "Radio")`'s token: `continuations[0].nextRadioContinuationData.continuation`. */
    fun radioContinuation(panel: JsonObject): String? =
        text(nav(panel, "continuations", 0, "nextRadioContinuationData", "continuation"))

    /** ytmusicapi `parse_watch_playlist`: unplayable items dropped, a song/video wrapper read as its song. */
    fun watchTracks(panel: JsonObject): List<CatalogTrack> {
        val contents = panel["contents"] as? JsonArray ?: return emptyList()
        val out = mutableListOf<CatalogTrack>()
        for (element in contents) {
            var result = element as? JsonObject ?: continue
            if (PPVWR in result) result = nav(result, PPVWR, "primaryRenderer") as? JsonObject ?: continue
            val data = result[PPVR] as? JsonObject ?: continue
            if ("unplayableText" in data) continue
            watchTrack(data)?.let(out::add)
        }
        return out
    }

    private fun watchTrack(data: JsonObject): CatalogTrack? {
        val videoId = text(data["videoId"]) ?: return null
        val title = text(nav(data, "title", "runs", 0, "text")) ?: return null
        val length = text(nav(data, "lengthText", "runs", 0, "text"))
        val videoType = text(nav(data, "navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType"))
        val info = (nav(data, "longBylineText", "runs") as? JsonArray)?.let { songRuns(it, skipTypeSpec = false) } ?: SongInfo()
        val badge = text(nav(data, "badges", 0, "musicInlineBadgeRenderer", "accessibilityData", "accessibilityData", "label"))
        return CatalogTrack(
            videoId = videoId,
            title = title,
            artists = info.artists,
            album = info.album,
            albumId = info.albumId,
            durationMs = parseDuration(length)?.times(1000L) ?: info.durationSeconds?.times(1000L),
            kind = CatalogKind.of(videoType),
            // Not read by ytmusicapi here: a badge says explicit, its absence says nothing.
            explicit = if (badge?.contains("explicit", ignoreCase = true) == true) true else null,
            // Unplayable items were dropped above, as ytmusicapi drops them.
            playable = true,
            year = info.year,
            videoType = videoType,
        )
    }

    // -- parse_song_runs ------------------------------------------------------------------

    data class SongInfo(
        val artists: List<String> = emptyList(),
        val album: String? = null,
        val albumId: String? = null,
        val durationSeconds: Long? = null,
        val year: String? = null,
        val views: String? = null,
    )

    /** ytmusicapi `parse_song_runs(runs, skip_type_spec)`. */
    fun songRuns(input: List<JsonElement>, skipTypeSpec: Boolean): SongInfo {
        var runs = input
        if (skipTypeSpec && runs.size > 2 && !hasNav(runs[0]) && kindOf(runs[0]) == RunKind.ARTIST &&
            text(nav(runs[1], "text")) == DOT_SEPARATOR && kindOf(runs[2]) in setOf(RunKind.ARTIST, RunKind.DURATION, RunKind.VIEWS, RunKind.YEAR)
        ) runs = runs.drop(2)
        val artists = mutableListOf<String>()
        var album: String? = null
        var albumId: String? = null
        var duration: Long? = null
        var year: String? = null
        var views: String? = null
        runs.forEachIndexed { i, run ->
            if (i % 2 == 1) return@forEachIndexed // separators
            val value = text(nav(run, "text")) ?: return@forEachIndexed
            when (kindOf(run)) {
                RunKind.ALBUM -> { album = value; albumId = browseId(run) }
                RunKind.ARTIST -> artists += value
                RunKind.DURATION -> duration = parseDuration(value)
                RunKind.YEAR -> year = value
                RunKind.VIEWS -> views = parseViews(value)
            }
        }
        return SongInfo(artists, album, albumId, duration, year, views)
    }

    private enum class RunKind { ALBUM, ARTIST, DURATION, YEAR, VIEWS }

    private fun kindOf(run: JsonElement): RunKind {
        val value = text(nav(run, "text")).orEmpty()
        if (hasNav(run)) {
            val id = browseId(run)
            return if (id != null && (id.startsWith("MPRE") || "release_detail" in id)) RunKind.ALBUM else RunKind.ARTIST
        }
        return when {
            DURATION.matches(value) -> RunKind.DURATION
            YEAR.matches(value) -> RunKind.YEAR
            parseViews(value) != null -> RunKind.VIEWS
            else -> RunKind.ARTIST
        }
    }

    private fun hasNav(run: JsonElement): Boolean = (run as? JsonObject)?.containsKey("navigationEndpoint") == true
    private fun browseId(run: JsonElement): String? = text(nav(run, "navigationEndpoint", "browseEndpoint", "browseId"))

    /** ytmusicapi `parse_views`. */
    fun parseViews(input: String): String? {
        var text = input
        var prefixed = false
        if (!LATIN.containsMatchIn(text)) {
            val stripped = VIEWS_PREFIX.replaceFirst(text, "")
            prefixed = stripped != text
            text = stripped
        }
        if (text.isEmpty() || !text[0].isDigit()) return null
        if (!prefixed && text.all { it.code < 128 } && ' ' !in text) return null
        val head = text.split(' ')[0].split('\u00a0')
        return head.take(2).joinToString("\u00a0")
    }

    /** ytmusicapi `parse_duration`: "3:45" → 225, "1:02:03" → 3723; anything else null. */
    fun parseDuration(value: String?): Long? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(':')
        if (parts.any { p -> p.isEmpty() || !p.all(Char::isDigit) }) return null
        return parts.reversed().zip(listOf(1L, 60L, 3600L)).sumOf { (p, m) -> p.toLong() * m }
    }

    // -- navigation -----------------------------------------------------------------------

    private fun flexRuns(data: JsonObject, index: Int): List<JsonElement>? {
        val columns = data["flexColumns"] as? JsonArray ?: return null
        val column = (columns.getOrNull(index) as? JsonObject)?.get("musicResponsiveListItemFlexColumnRenderer") as? JsonObject ?: return null
        return nav(column, "text", "runs") as? JsonArray
    }

    private fun flexText(data: JsonObject, index: Int, run: Int): String? =
        flexRuns(data, index)?.getOrNull(run)?.let { text(nav(it, "text")) }

    /** ytmusicapi `nav(root, path, none_if_absent=True)`: strings are keys, ints are indexes. */
    fun nav(root: JsonElement?, vararg path: Any): JsonElement? {
        var node = root
        for (step in path) {
            node = when (step) {
                is String -> (node as? JsonObject)?.get(step)
                is Int -> (node as? JsonArray)?.getOrNull(step)
                else -> null
            } ?: return null
        }
        return node
    }

    fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
