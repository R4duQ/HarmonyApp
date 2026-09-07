package com.harmony.feature.discover.navigation

/**
 * The Discover destination's route, owned by the feature rather than by the
 * app's `Routes` table.
 *
 * Harmony registers every screen inline in `HarmonyApp`, and that stays true —
 * this just means the app module refers to a constant the feature publishes
 * instead of re-typing the string, so renaming the route is a one-file change.
 * There is no NavGraphBuilder extension here on purpose: adding one would pull
 * navigation-compose into a module that otherwise needs nothing but Compose.
 */
object DiscoverRoute {
    const val ROUTE = "discover"
}
