package com.harmony.feature.discover.di

import com.harmony.feature.discover.provider.LocalLibraryRecommendationProvider
import com.harmony.feature.discover.provider.RecommendationProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the only recommendation source Harmony currently has.
 *
 * When an external metadata provider is added, this is the file that changes:
 * either swap the binding, or convert it to a multibinding set and let the
 * ViewModel merge several providers. The screen does not participate.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiscoverModule {

    @Binds
    @Singleton
    abstract fun bindRecommendationProvider(
        impl: LocalLibraryRecommendationProvider,
    ): RecommendationProvider
}
