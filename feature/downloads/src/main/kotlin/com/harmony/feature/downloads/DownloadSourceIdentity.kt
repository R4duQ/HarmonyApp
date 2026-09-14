package com.harmony.feature.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.amberPalette

/**
 * Everything the Downloads screen needs to know about "which engine am I on",
 * in one place.
 *
 * Before this, the screen asked `preferredDownloadSource == SPOTIFLAC` at 20+
 * call sites and picked a string inline. That made a third source (Internet
 * Archive, Bandcamp, anything) a 20-site edit, and it meant the screen looked
 * byte-identical whichever engine was armed — the only signal was which chip
 * happened to be filled.
 *
 * Now the screen reads `state.preferredDownloadSource.identity()` once and
 * derives copy AND color from it. Adding a source is one `when` branch here.
 */
@Immutable
data class DownloadSourceIdentity(
    val source: DownloadSource,
    /** Full name, as shown in the selector and the active badge. */
    val displayName: String,
    /** Two-letter monogram for the compact badge. */
    val monogram: String,
    /** One line under the selector explaining what this engine actually is. */
    val tagline: String,
    /** Section label above the query card. */
    val searchSectionLabel: String,
    /** Sentence at the top of the query card. */
    val searchHelp: String,
    /** OutlinedTextField label. */
    val queryFieldLabel: String,
    /** Placeholder inside the query field. */
    val queryPlaceholder: String,
    /** Fine print under the query field. */
    val queryFooter: String,
    /** Text on the pill that starts the download of an identified track. */
    val downloadActionLabel: String,
    /** Text on the search pill at rest. */
    val searchActionLabel: String,
    /** Text on the search pill while a search is running. */
    val searchingLabel: String,
    /** Badge over a result row. */
    val resultBadge: String,
    /**
     * The saturated hue that identifies this engine in the source selector.
     * Fixed regardless of theme or which engine is currently armed — since
     * [palette] now always renders as the same amber as Library (the
     * Downloads screen is meant to look like Library, not like a
     * differently-branded screen per engine), this is the only remaining
     * way to tell SpotiFLAC and Soulseek apart by colour at all.
     */
    val brand: Color,
    /** Text/icon colour that reads on [brand]. */
    val onBrand: Color,
    /** The screen's field colors while this engine is armed — always Library's amber. */
    val palette: EditorialPalette,
)

/**
 * Resolve the identity for a source.
 *
 * Call this ONCE at the top of DownloadsScreen and pass it down. Calling it
 * per-widget is harmless but pointless.
 */
@Composable
fun DownloadSource.identity(): DownloadSourceIdentity {
    return when (this) {
        DownloadSource.SPOTIFLAC -> DownloadSourceIdentity(
            source = this,
            displayName = "SpotiFLAC",
            monogram = "SF",
            tagline = "Lossless extension providers, one per request (Tidal / Qobuz / Deezer / Amazon).",
            searchSectionLabel = "Search SpotiFLAC",
            searchHelp = "Type the artist and song you want to download.",
            queryFieldLabel = "Artist - Song",
            queryPlaceholder = "The Weeknd - Blinding Lights",
            queryFooter = "Harmony performs a real metadata search and rejects weak matches. " +
                "Enter at least 3 letters/numbers; artist - song gives the most accurate results.",
            downloadActionLabel = "Download with SpotiFLAC",
            searchActionLabel = "Search SpotiFLAC",
            searchingLabel = "Searching…",
            resultBadge = "Verified track",
            brand = Color(0xFFE8654F),
            onBrand = Color(0xFF1F0A06),
            palette = amberPalette(),
        )

        DownloadSource.SOULSEEK -> DownloadSourceIdentity(
            source = this,
            displayName = "Soulseek",
            monogram = "SK",
            tagline = "Peer-to-peer search with explicit peer and file selection.",
            searchSectionLabel = "Search Soulseek",
            searchHelp = "Type an artist, album or song to search on the Soulseek network.",
            queryFieldLabel = "Artist, album or song",
            queryPlaceholder = "The Weeknd - Blinding Lights",
            queryFooter = "If Soulseek is not connected yet, enter your account in Peer Search " +
                "below, then use this same query.",
            downloadActionLabel = "Continue with Soulseek",
            searchActionLabel = "Search Soulseek",
            searchingLabel = "Searching…",
            resultBadge = "Detected",
            brand = Color(0xFF17A2A2),
            onBrand = Color(0xFF041717),
            palette = amberPalette(),
        )
        DownloadSource.YTCONVERTER -> DownloadSourceIdentity(
            source = this,
            displayName = "YT Converter",
            monogram = "YT",
            tagline = "Converts a YouTube link to FLAC or MP3 on your phone with yt-dlp and FFmpeg.",
            searchSectionLabel = "Convert a YouTube link",
            searchHelp = "Paste the YouTube URL of the track you want to convert.",
            queryFieldLabel = "YouTube URL",
            queryPlaceholder = "https://www.youtube.com/watch?v=…",
            queryFooter = "Nothing leaves your phone except the request to YouTube itself — " +
                "the conversion runs locally. Only convert material you're permitted to.",
            downloadActionLabel = "Convert and save",
            searchActionLabel = "Identify link",
            searchingLabel = "Identifying…",
            resultBadge = "Identified",
            brand = Color(0xFF6C4BE8),
            onBrand = Color(0xFFFFFFFF),
            palette = amberPalette(),
        )
    }
}

/** The engine Harmony would offer if [this] one has just failed. */
fun DownloadSource.alternative(): DownloadSource = when (this) {
    DownloadSource.SPOTIFLAC -> DownloadSource.SOULSEEK
    DownloadSource.SOULSEEK -> DownloadSource.SPOTIFLAC
    // The converter's failures are almost never "this engine can't get it" —
    // they're a bad link or a yt-dlp/YouTube protocol break, which no other
    // engine fixes. SpotiFLAC is the closest thing to a useful fallback
    // since it takes an artist/title rather than a URL.
    DownloadSource.YTCONVERTER -> DownloadSource.SPOTIFLAC
}

/**
 * True when this source works from a pasted URL rather than a text search.
 *
 * Worth a named helper rather than `== YTCONVERTER` scattered around: it's
 * the distinction that decides whether the query box holds a link or a
 * search term, and inlining the comparison makes every one of those call
 * sites silently wrong the day a second URL-based source appears.
 */
val DownloadSource.isUrlBased: Boolean
    get() = this == DownloadSource.YTCONVERTER
