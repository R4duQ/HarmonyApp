package com.harmony.data.analysis.di

import com.harmony.data.analysis.AnalysisRepositoryImpl
import com.harmony.data.analysis.MoodRepositoryImpl
import com.harmony.domain.shuffle.repository.MoodRepository
import com.harmony.domain.analysis.repository.AnalysisRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataAnalysisModule {

    @Binds
    @Singleton
    abstract fun bindAnalysisRepository(impl: AnalysisRepositoryImpl): AnalysisRepository

    @Binds
    @Singleton
    abstract fun bindMoodRepository(impl: MoodRepositoryImpl): MoodRepository
}
