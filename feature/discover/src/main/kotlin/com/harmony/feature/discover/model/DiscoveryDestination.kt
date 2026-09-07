package com.harmony.feature.discover.model

import androidx.compose.runtime.Immutable

/**
 * Stable identity for a destination, so the UI can key off something other
 * than a display string and a future provider can map its own entries onto
 * the same slots.
 */
enum class DiscoveryDestinationId {
    /** The provider's front door — a random album. */
    SHUFFLE,
    GUIDES,
    COLLECTIONS,
    BEST_OF,
    ABOUT,
}

/**
 * An external navigation shortcut. Individual album recommendations use
 * DiscoverAlbum and retain a separate, verified source URL.
 */
@Immutable
data class DiscoveryDestination(
    val id: DiscoveryDestinationId,
    val title: String,
    val subtitle: String,
    /** Must be one of [ShflUrlPolicy.ALLOWED_DESTINATIONS] for the SHFL provider. */
    val url: String,
)
