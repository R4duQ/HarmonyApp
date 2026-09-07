package com.harmony.data.library.di

import com.harmony.data.library.FavoritesRepositoryImpl
import com.harmony.data.library.LibraryRepositoryImpl
import com.harmony.data.library.LibraryWriteGatewayImpl
import com.harmony.data.library.PlaybackHistoryRepositoryImpl
import com.harmony.data.library.PlaylistRepositoryImpl
import com.harmony.domain.library.gateway.LibraryWriteGateway
import com.harmony.domain.library.repository.FavoritesRepository
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.library.repository.PlaybackHistoryRepository
import com.harmony.domain.library.repository.PlaylistRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataLibraryModule {

    @Binds @Singleton
    abstract fun bindAlbumJourneys(impl: com.harmony.data.library.AlbumJourneyRepositoryImpl):
        com.harmony.domain.library.repository.AlbumJourneyRepository

    @Binds @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds
    abstract fun bindSongEditRepository(
        impl: com.harmony.data.library.SongEditRepositoryImpl,
    ): com.harmony.domain.library.repository.SongEditRepository

    @Binds @Singleton
    abstract fun bindLibraryWriteGateway(impl: LibraryWriteGatewayImpl): LibraryWriteGateway

    @Binds @Singleton
    abstract fun bindPlaylistRepository(impl: PlaylistRepositoryImpl): PlaylistRepository

    @Binds @Singleton
    abstract fun bindHistoryRepository(impl: PlaybackHistoryRepositoryImpl): PlaybackHistoryRepository

    @Binds @Singleton
    abstract fun bindFavoritesRepository(impl: FavoritesRepositoryImpl): FavoritesRepository
}
