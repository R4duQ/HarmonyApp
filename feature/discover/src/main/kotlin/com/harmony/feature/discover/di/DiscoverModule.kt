package com.harmony.feature.discover.di

import com.harmony.feature.discover.provider.DiscoveryCatalog
import com.harmony.feature.discover.provider.DiscoveryClock
import com.harmony.feature.discover.provider.DiscoveryConnectivity
import com.harmony.feature.discover.provider.LiveSongCatalog
import com.harmony.feature.discover.provider.LocalFileProbe
import com.harmony.feature.discover.provider.LocalLibraryRecommendationProvider
import com.harmony.feature.discover.provider.RecommendationProvider
import com.harmony.feature.discover.provider.SystemDiscoveryClock
import com.harmony.feature.discover.ui.ContentResolverFileProbe
import com.harmony.feature.discover.ui.ExoPreviewPlayerFactory
import com.harmony.feature.discover.ui.InternetDiscoveryConnectivity
import com.harmony.feature.discover.ui.PreviewPlayerFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bindings for Discover. The album discovery keeps its local provider; the
 * song flow gets the public catalogs, the preview player and the platform
 * edges (connectivity, file access, clock) behind interfaces so the flow can
 * be tested without Android.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiscoverModule {

    @Binds
    @Singleton
    abstract fun bindRecommendationProvider(impl: LocalLibraryRecommendationProvider): RecommendationProvider

    @Binds
    abstract fun bindCatalog(impl: LiveSongCatalog): DiscoveryCatalog

    @Binds
    abstract fun bindPreviewPlayers(impl: ExoPreviewPlayerFactory): PreviewPlayerFactory

    @Binds
    abstract fun bindFileProbe(impl: ContentResolverFileProbe): LocalFileProbe

    @Binds
    abstract fun bindConnectivity(impl: InternetDiscoveryConnectivity): DiscoveryConnectivity

    @Binds
    abstract fun bindClock(impl: SystemDiscoveryClock): DiscoveryClock
}
