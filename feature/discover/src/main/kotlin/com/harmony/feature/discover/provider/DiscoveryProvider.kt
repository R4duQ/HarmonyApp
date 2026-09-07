package com.harmony.feature.discover.provider

import com.harmony.feature.discover.model.DiscoveryDestination
import com.harmony.feature.discover.model.DiscoveryDestinationId
import com.harmony.feature.discover.model.ShflUrlPolicy
import com.harmony.feature.discover.ui.DiscoverStrings

/**
 * Public navigation shortcuts and URL policy for the browser handoff.
 * The native album deck is provided separately by ShflAlbumCatalog; Harmony
 * supplies its own listening notes and keeps full reviews at their source.
 */
interface DiscoveryProvider {

    /** Stable id, for a future provider preference. */
    val id: String

    /** The service's own name, shown in attribution text. */
    val displayName: String

    /** The provider's front door — the primary "surprise me" action. */
    val shuffle: DiscoveryDestination

    /** Curated entry points, in display order. */
    val curatedPaths: List<DiscoveryDestination>

    /** The provider's own "about" page. */
    val about: DiscoveryDestination

    /**
     * Final gate before anything is opened. Implementations must refuse
     * anything they did not themselves publish.
     */
    fun permits(url: String): Boolean
}

/**
 * SHFL as an external destination.
 *
 * Every URL comes from [ShflUrlPolicy], which is also what [permits]
 * consults, so there is one list of addresses in the codebase rather than a
 * UI copy and a validation copy that can drift apart.
 */
object ShflDiscoveryProvider : DiscoveryProvider {

    override val id: String = "shfl"

    override val displayName: String = "SHFL"

    override val shuffle: DiscoveryDestination = DiscoveryDestination(
        id = DiscoveryDestinationId.SHUFFLE,
        title = DiscoverStrings.SHUFFLE_ACTION,
        subtitle = DiscoverStrings.HERO_BODY,
        url = ShflUrlPolicy.HOME,
    )

    override val curatedPaths: List<DiscoveryDestination> = listOf(
        DiscoveryDestination(
            id = DiscoveryDestinationId.GUIDES,
            title = DiscoverStrings.GUIDES_TITLE,
            subtitle = DiscoverStrings.GUIDES_SUBTITLE,
            url = ShflUrlPolicy.GUIDES,
        ),
        DiscoveryDestination(
            id = DiscoveryDestinationId.COLLECTIONS,
            title = DiscoverStrings.COLLECTIONS_TITLE,
            subtitle = DiscoverStrings.COLLECTIONS_SUBTITLE,
            url = ShflUrlPolicy.COLLECTIONS,
        ),
        DiscoveryDestination(
            id = DiscoveryDestinationId.BEST_OF,
            title = DiscoverStrings.BEST_OF_TITLE,
            subtitle = DiscoverStrings.BEST_OF_SUBTITLE,
            url = ShflUrlPolicy.BEST_OF,
        ),
    )

    override val about: DiscoveryDestination = DiscoveryDestination(
        id = DiscoveryDestinationId.ABOUT,
        title = DiscoverStrings.ABOUT_ACTION,
        subtitle = "",
        url = ShflUrlPolicy.ABOUT,
    )

    override fun permits(url: String): Boolean = ShflUrlPolicy.isAllowedDestination(url)
}
