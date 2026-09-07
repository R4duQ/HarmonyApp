package com.harmony.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.harmony.core.database.dao.AnalysisDao
import com.harmony.core.database.dao.CollectionDao
import com.harmony.core.database.dao.HistoryDao
import com.harmony.core.database.dao.DownloadRecordDao
import com.harmony.core.database.dao.PlaylistDao
import com.harmony.core.database.dao.SongDao
import com.harmony.core.database.entity.AlbumEntity
import com.harmony.core.database.entity.AnalysisResultEntity
import com.harmony.core.database.entity.ArtistEntity
import com.harmony.core.database.entity.FavoriteEntity
import com.harmony.core.database.entity.DownloadRecordEntity
import com.harmony.core.database.entity.PlayHistoryEntity
import com.harmony.core.database.entity.PlaylistEntity
import com.harmony.core.database.entity.PlaylistSongCrossRef
import com.harmony.core.database.entity.SongEntity
import com.harmony.core.database.entity.SongEditEntity
import com.harmony.core.database.entity.SongEffectiveView
import com.harmony.core.database.entity.SongFtsEntity

/**
 * Version 1 — nothing shipped yet, so no migrations. From the first release
 * onward every schema change requires a Migration + a MigrationTest (Phase 10
 * adds the harness); exportSchema is on so schema JSONs land in version
 * control for exactly that purpose.
 */
@Database(
    version = 3,
    exportSchema = true,
    entities = [
        SongEntity::class,
        SongFtsEntity::class,
        AlbumEntity::class,
        ArtistEntity::class,
        AnalysisResultEntity::class,
        PlaylistEntity::class,
        PlaylistSongCrossRef::class,
        PlayHistoryEntity::class,
        FavoriteEntity::class,
        SongEditEntity::class,
        DownloadRecordEntity::class,
    ],
    views = [SongEffectiveView::class],
)
abstract class HarmonyDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun collectionDao(): CollectionDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun analysisDao(): AnalysisDao
    abstract fun songEditDao(): com.harmony.core.database.dao.SongEditDao
    abstract fun downloadRecordDao(): DownloadRecordDao
}
