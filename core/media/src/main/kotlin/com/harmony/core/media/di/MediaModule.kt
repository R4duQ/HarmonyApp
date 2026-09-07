package com.harmony.core.media.di

import com.harmony.core.common.coroutines.DefaultDispatcherProvider
import com.harmony.core.common.coroutines.DispatcherProvider
import com.harmony.core.media.scanner.LibraryScanner
import com.harmony.domain.library.gateway.MediaScanGateway
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaBindsModule {

    @Binds
    @Singleton
    abstract fun bindMediaScanGateway(impl: LibraryScanner): MediaScanGateway
}

@Module
@InstallIn(SingletonComponent::class)
object MediaProvidesModule {

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()
}
