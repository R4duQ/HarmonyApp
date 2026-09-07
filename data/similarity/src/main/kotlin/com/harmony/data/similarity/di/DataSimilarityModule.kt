package com.harmony.data.similarity.di

import com.harmony.data.similarity.SimilarityRepositoryImpl
import com.harmony.domain.similarity.repository.SimilarityRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataSimilarityModule {

    @Binds
    @Singleton
    abstract fun bindSimilarityRepository(impl: SimilarityRepositoryImpl): SimilarityRepository
}
