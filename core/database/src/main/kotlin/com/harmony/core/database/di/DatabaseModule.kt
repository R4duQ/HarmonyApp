package com.harmony.core.database.di

import android.content.Context
import androidx.room.Room
import com.harmony.core.database.entity.SONGS_EFFECTIVE_SQL
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.harmony.core.database.HarmonyDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): HarmonyDatabase =
        Room.databaseBuilder(context, HarmonyDatabase::class.java, "harmony.db")
            // WAL (the default) lets the UI read while the scanner writes.
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    /**
     * Adds the manual tag-edit table.
     *
     * A real migration rather than a destructive fallback: dropping the
     * database here would wipe playlists, favourites and play counts to add
     * one table, and those can't be rebuilt by rescanning.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS song_edits (
                    songId INTEGER NOT NULL PRIMARY KEY,
                    title TEXT,
                    artist TEXT,
                    album TEXT,
                    albumArtist TEXT,
                    artworkUri TEXT,
                    artworkCleared INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )

            // The view has to be created here too. Room only creates views
            // on a FRESH install; on an upgrade it just verifies they exist
            // and throws if one is missing, which is what crashed build 20.
            //
            // The SQL below must match SongEffectiveView's @DatabaseView
            // text exactly — Room compares the stored definition against the
            // expected one, so any difference, whitespace included, fails
            // the same way. Keep the two in sync.
            db.execSQL("DROP VIEW IF EXISTS songs_effective")
            db.execSQL("CREATE VIEW `songs_effective` AS $SONGS_EFFECTIVE_SQL")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS download_records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    uri TEXT NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    source TEXT NOT NULL,
                    provider TEXT,
                    format TEXT,
                    bitDepth INTEGER,
                    sampleRateHz INTEGER,
                    fileSizeBytes INTEGER NOT NULL,
                    downloadedAt INTEGER NOT NULL,
                    soulseekUsername TEXT,
                    originalTrackId TEXT,
                    isrc TEXT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_download_records_uri ON download_records(uri)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_download_records_downloadedAt ON download_records(downloadedAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_download_records_source ON download_records(source)")
        }
    }

    @Provides fun songDao(db: HarmonyDatabase) = db.songDao()
    @Provides fun collectionDao(db: HarmonyDatabase) = db.collectionDao()
    @Provides fun playlistDao(db: HarmonyDatabase) = db.playlistDao()
    @Provides fun historyDao(db: HarmonyDatabase) = db.historyDao()
    @Provides fun analysisDao(db: HarmonyDatabase) = db.analysisDao()
    @Provides fun songEditDao(db: HarmonyDatabase) = db.songEditDao()
    @Provides fun downloadRecordDao(db: HarmonyDatabase) = db.downloadRecordDao()
}
