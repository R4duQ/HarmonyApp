package com.harmony.feature.discover.ui

/**
 * Copy for the public SHFL navigation shortcuts and browser launch errors.
 *
 * Harmony keeps its copy in Kotlin rather than `strings.xml` — `app`'s
 * resource file holds only `app_name`, and every other screen writes its text
 * inline. The native album deck follows that convention; its original
 * listening notes are stored separately in ShflAlbumCatalog.
 *
 * The attribution wording is deliberate. Harmony is not affiliated with SHFL,
 * so nothing here claims a partnership: it says where the link goes, and the
 * notice at the bottom of the screen says plainly what Harmony does and does
 * not send.
 */
object DiscoverStrings {

    const val TITLE = "Discover"
    const val SUBTITLE = "Unexpected albums, chosen by chance."
    const val ATTRIBUTION = "Explore on SHFL"

    const val HERO_TITLE = "Discover something unexpected"
    const val HERO_BODY =
        "Explore recommendations selected by music critics and musicians—not your " +
            "listening history."
    const val SHUFFLE_ACTION = "Shuffle on SHFL"
    const val SHUFFLE_ACTION_DESCRIPTION =
        "Shuffle on SHFL. Opens theshfl.com in your browser."

    const val CURATED_LABEL = "Curated paths"

    const val GUIDES_TITLE = "Guides"
    const val GUIDES_SUBTITLE = "Explore introductions to genres, scenes and musical movements."

    const val COLLECTIONS_TITLE = "Collections"
    const val COLLECTIONS_SUBTITLE = "Browse albums grouped around artists, labels and musical ideas."

    const val BEST_OF_TITLE = "Best Of"
    const val BEST_OF_SUBTITLE = "Start with popular genres, styles and decades."

    /** Suffix appended to a card's TalkBack label so the handoff is never a surprise. */
    const val OPENS_IN_BROWSER = "Opens in your browser."

    const val FLOW_LABEL = "How it fits together"
    val FLOW_STEPS = listOf(
        "Discover on SHFL",
        "Return to Harmony",
        "Search your local library",
        "Play it offline",
    )
    const val SEARCH_LIBRARY_ACTION = "Search my library"
    const val SEARCH_LIBRARY_DESCRIPTION =
        "Search my library. Opens search on Harmony's Library screen."

    const val PRIVACY_NOTICE =
        "SHFL opens in your browser. Harmony does not send your library or listening " +
            "history. Harmony is not affiliated with SHFL."

    const val ABOUT_ACTION = "About SHFL"

    /** Shown when no browser could take the link, or the link failed validation. */
    const val LAUNCH_FAILED = "SHFL could not be opened. Check your connection or browser."
}
