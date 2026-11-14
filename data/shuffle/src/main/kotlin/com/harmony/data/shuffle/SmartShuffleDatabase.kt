package com.harmony.data.shuffle

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Transaction
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Smart Shuffle's own database (smart_shuffle.db), next to harmony.db:
 *
 *  - acoustic_vectors: each song's 24 raw acoustic features, measured by the
 *    background indexer, keyed to the file's hash so a changed file is
 *    measured again;
 *  - markov_transitions: "the listener finished B right after A", weighted;
 *  - zone_bandit: the contextual bandit's pulls and rewards (10 x 10: the
 *    session's sonic zone, the pick's zone);
 *  - taste_edges: what YouTube Music's radios, read while online mixes ran,
 *    put together (a song with a song, an artist with an artist), for the
 *    offline mix ([TasteMemory]).
 *
 * Play counts, skips and favourites are not repeated here: they live in
 * harmony.db's play history and favourites, which Smart Shuffle reads.
 *
 * Its own file so its schema can move with Smart Shuffle without migrating
 * the library database. Every change of version needs a Migration (see [MIGRATIONS]).
 */
@Database(
    version = 2,
    exportSchema = true,
    entities = [AcousticVectorEntity::class, MarkovTransitionEntity::class, ZoneBanditEntity::class, TasteEdgeEntity::class],
)
abstract class SmartShuffleDatabase : RoomDatabase() {
    abstract fun acousticVectors(): AcousticVectorDao
    abstract fun markov(): MarkovTransitionDao
    abstract fun bandit(): ZoneBanditDao
    abstract fun taste(): TasteDao

    companion object {
        const val FILE_NAME = "smart_shuffle.db"

        /** 1 -> 2: the taste memory's table. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `taste_edges` (`kind` INTEGER NOT NULL, `fromKey` TEXT NOT NULL, " +
                        "`toKey` TEXT NOT NULL, `weight` REAL NOT NULL, `seenAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`kind`, `fromKey`, `toKey`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_taste_edges_seenAt` ON `taste_edges` (`seenAt`)")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}

/**
 * One thing an online radio put with another: [kind] [SONG] links a seed
 * song ("id:<song id>") to a recording ([TrackIdentity] key), [ARTIST] an
 * artist to an artist (normalized lead credits). [weight] 0..1 grows each
 * time a radio says it again; [seenAt] is the last time.
 */
@Entity(tableName = "taste_edges", primaryKeys = ["kind", "fromKey", "toKey"], indices = [Index("seenAt")])
data class TasteEdgeEntity(
    val kind: Int,
    val fromKey: String,
    val toKey: String,
    val weight: Float,
    val seenAt: Long,
) {
    companion object {
        const val SONG = 0
        const val ARTIST = 1
    }
}

@Dao
abstract class TasteDao {
    @Query("SELECT * FROM taste_edges")
    abstract suspend fun all(): List<TasteEdgeEntity>

    @Query("SELECT * FROM taste_edges WHERE kind = :kind AND fromKey = :fromKey")
    abstract suspend fun from(kind: Int, fromKey: String): List<TasteEdgeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(edges: List<TasteEdgeEntity>)

    /** What changed, cheaply: the number of edges and the latest write. */
    @Query("SELECT COUNT(*) AS count, COALESCE(MAX(seenAt), 0) AS latest FROM taste_edges")
    abstract suspend fun stamp(): Stamp

    data class Stamp(val count: Int, val latest: Long)

    /** The [count] edges heard of longest ago go (the memory stays bounded). */
    @Query("DELETE FROM taste_edges WHERE rowid IN (SELECT rowid FROM taste_edges ORDER BY seenAt ASC LIMIT :count)")
    abstract suspend fun pruneOldest(count: Int)
}

@Entity(tableName = "acoustic_vectors")
data class AcousticVectorEntity(
    @PrimaryKey val songId: Long,
    /** The file the vector was measured from (SongEntity.fileHash). */
    val fileHash: String,
    /** AcousticVectorStore.ACOUSTIC_VERSION at measuring time. */
    val extractorVersion: Int,
    /** 24 little-endian floats, or null: measured, but too short or silent to describe. */
    val vector: ByteArray?,
    val measuredAt: Long,
) {
    override fun equals(other: Any?): Boolean = other is AcousticVectorEntity && other.songId == songId
    override fun hashCode(): Int = songId.hashCode()

    companion object {
        const val DIMS = 24

        fun encode(values: FloatArray): ByteArray {
            require(values.size == DIMS) { "expected $DIMS features, got ${values.size}" }
            val buffer = ByteBuffer.allocate(DIMS * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { buffer.putFloat(it) }
            return buffer.array()
        }

        /** The 24 floats, or null for a missing or malformed blob. */
        fun decode(blob: ByteArray?): FloatArray? {
            if (blob == null || blob.size != DIMS * 4) return null
            val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(DIMS) { buffer.getFloat() }
        }
    }
}

@Entity(
    tableName = "markov_transitions",
    primaryKeys = ["fromSongId", "toSongId"],
    indices = [Index("fromSongId")],
)
data class MarkovTransitionEntity(
    val fromSongId: Long,
    val toSongId: Long,
    val weight: Float,
    val updatedAt: Long,
)

@Entity(tableName = "zone_bandit", primaryKeys = ["context", "arm"])
data class ZoneBanditEntity(
    /** The session's sonic zone (0..9: 31 Hz .. 16 kHz). */
    val context: Int,
    /** The zone of the song Smart Shuffle picked. */
    val arm: Int,
    val pulls: Float,
    /** 1 per song finished, 0.5 per song left midway, 0 per fast skip. */
    val rewards: Float,
)

@Dao
interface AcousticVectorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AcousticVectorEntity)

    @Query("SELECT songId, fileHash, extractorVersion FROM acoustic_vectors")
    suspend fun keys(): List<KeyRow>

    data class KeyRow(val songId: Long, val fileHash: String, val extractorVersion: Int)

    @Query("SELECT * FROM acoustic_vectors WHERE vector IS NOT NULL")
    suspend fun measured(): List<AcousticVectorEntity>

    @Query("SELECT COUNT(*) FROM acoustic_vectors")
    suspend fun count(): Int

    /** How many vectors there are and when the newest was written: changes whenever one is. */
    @Query("SELECT COUNT(*) AS count, COALESCE(MAX(measuredAt), 0) AS latest FROM acoustic_vectors")
    suspend fun stamp(): Stamp

    data class Stamp(val count: Int, val latest: Long)

    /** Every vector written at or after [since], measured or not (a failed measurement is NULL). */
    @Query("SELECT * FROM acoustic_vectors WHERE measuredAt >= :since")
    suspend fun writtenSince(since: Long): List<AcousticVectorEntity>
}

@Dao
abstract class MarkovTransitionDao {
    @Query("SELECT * FROM markov_transitions")
    abstract suspend fun all(): List<MarkovTransitionEntity>

    @Query("INSERT OR IGNORE INTO markov_transitions (fromSongId, toSongId, weight, updatedAt) VALUES (:from, :to, 0, :now)")
    abstract suspend fun ensure(from: Long, to: Long, now: Long)

    @Query("UPDATE markov_transitions SET weight = weight + :delta, updatedAt = :now WHERE fromSongId = :from AND toSongId = :to")
    abstract suspend fun increment(from: Long, to: Long, delta: Float, now: Long)

    /** Adds [delta] to the transition, creating it (SQLite on Android 10 has no UPSERT). */
    @Transaction
    open suspend fun add(from: Long, to: Long, delta: Float, now: Long) {
        ensure(from, to, now)
        increment(from, to, delta, now)
    }

    @Query("SELECT COUNT(*) FROM markov_transitions")
    abstract suspend fun count(): Int

    /** Keeps the table bounded: drops the oldest light-weight habits. */
    @Query(
        """
        DELETE FROM markov_transitions WHERE rowid IN (
            SELECT rowid FROM markov_transitions ORDER BY weight ASC, updatedAt ASC LIMIT :count
        )
        """
    )
    abstract suspend fun pruneWeakest(count: Int)
}

@Dao
abstract class ZoneBanditDao {
    @Query("SELECT * FROM zone_bandit")
    abstract suspend fun all(): List<ZoneBanditEntity>

    @Query("INSERT OR IGNORE INTO zone_bandit (context, arm, pulls, rewards) VALUES (:context, :arm, 0, 0)")
    abstract suspend fun ensure(context: Int, arm: Int)

    @Query("UPDATE zone_bandit SET pulls = pulls + :pulls, rewards = rewards + :reward WHERE context = :context AND arm = :arm")
    abstract suspend fun increment(context: Int, arm: Int, pulls: Float, reward: Float)

    @Transaction
    open suspend fun add(context: Int, arm: Int, pulls: Float, reward: Float) {
        ensure(context, arm)
        increment(context, arm, pulls, reward)
    }
}
