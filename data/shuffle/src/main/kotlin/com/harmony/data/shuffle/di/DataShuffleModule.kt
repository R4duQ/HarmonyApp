package com.harmony.data.shuffle.di

import android.content.Context
import androidx.room.Room
import com.harmony.data.shuffle.AcousticVectorStoreImpl
import com.harmony.data.shuffle.AndroidShuffleBrainLog
import com.harmony.data.shuffle.NativeShuffleBrain
import com.harmony.data.shuffle.SmartShuffleDatabase
import com.harmony.domain.analysis.repository.AcousticVectorStore
import com.harmony.domain.shuffle.brain.ShuffleBrain
import com.harmony.domain.shuffle.brain.ShuffleBrainLog
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataShuffleModule {

    @Binds
    @Singleton
    abstract fun bindShuffleBrain(impl: NativeShuffleBrain): ShuffleBrain

    @Binds
    @Singleton
    abstract fun bindShuffleBrainLog(impl: AndroidShuffleBrainLog): ShuffleBrainLog

    @Binds
    @Singleton
    abstract fun bindAcousticVectorStore(impl: AcousticVectorStoreImpl): AcousticVectorStore
}

@Module
@InstallIn(SingletonComponent::class)
object SmartShuffleDatabaseModule {
    @Provides
    @Singleton
    fun provideSmartShuffleDatabase(@ApplicationContext context: Context): SmartShuffleDatabase =
        Room.databaseBuilder(context, SmartShuffleDatabase::class.java, SmartShuffleDatabase.FILE_NAME).build()
}
