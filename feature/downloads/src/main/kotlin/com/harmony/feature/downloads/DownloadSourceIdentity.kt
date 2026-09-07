package com.harmony.feature.downloads

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.harmony.core.ui.component.EditorialPalette

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
     * The saturated hue that identifies this engine, IDENTICAL in light and dark.
     *
     * [palette] flips construction between themes — in light the accent is
     * near-black and the field carries the hue, in dark it is the other way
     * round — so neither of its members is a stable brand colour. The selector
     * needs one that never moves, or the two segments stop being distinguishable
     * by colour in one of the two themes.
     */
    val brand: Color,
    /** Text/icon colour that reads on [brand]. */
    val onBrand: Color,
    /** The screen's field colors while this engine is armed. */
    val palette: EditorialPalette,
)

// ---------------------------------------------------------------------------
// Palettes
//
// Same construction rule as EditorialLook.kt: light = saturated field with
// near-black same-hue ink; dark = very low-register field of the same hue with
// light same-hue ink, muted at 0x99 and line at 0x80. Rose and teal are
// complementary and sit clear of amber (library), green (playlists), blue
// (equalizer), stone (settings) and lavender (player), so neither source can
// be mistaken for another section of the app.
// ---------------------------------------------------------------------------

private val RoseLight = EditorialPalette(
    field = Color(0xFFE8654F),
    ink = Color(0xFF1F0A06),
    muted = Color(0xA61F0A06),
    line = Color(0xFF1F0A06),
    accent = Color(0xFF1F0A06),
    onAccent = Color(0xFFE8654F),
)

private val RoseDark = EditorialPalette(
    field = Color(0xFF24100C),
    ink = Color(0xFFF7C9BE),
    muted = Color(0x99F7C9BE),
    line = Color(0x80F7C9BE),
    accent = Color(0xFFE8654F),
    onAccent = Color(0xFF1F0A06),
)

private val TealLight = EditorialPalette(
    field = Color(0xFF17A2A2),
    ink = Color(0xFF041717),
    muted = Color(0xA6041717),
    line = Color(0xFF041717),
    accent = Color(0xFF041717),
    onAccent = Color(0xFF17A2A2),
)

private val TealDark = EditorialPalette(
    field = Color(0xFF06201F),
    ink = Color(0xFFBFE9E7),
    muted = Color(0x99BFE9E7),
    line = Color(0x80BFE9E7),
    accent = Color(0xFF17A2A2),
    onAccent = Color(0xFF041717),
)

@Composable
private fun isDark(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * Resolve the identity for a source against the current light/dark scheme.
 *
 * Call this ONCE at the top of DownloadsScreen and pass it down. Calling it
 * per-widget is harmless but pointless.
 */
@Composable
fun DownloadSource.identity(): DownloadSourceIdentity {
    val dark = isDark()
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
            palette = if (dark) RoseDark else RoseLight,
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
            palette = if (dark) TealDark else TealLight,
        )
    }
}

/** The engine Harmony would offer if [this] one has just failed. */
fun DownloadSource.alternative(): DownloadSource = when (this) {
    DownloadSource.SPOTIFLAC -> DownloadSource.SOULSEEK
    DownloadSource.SOULSEEK -> DownloadSource.SPOTIFLAC
}
