package com.harmony.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.harmony.core.database.di.DatabaseModule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Verifies every shipped migration against the exported schemas.
 *
 * The module already declared `room.schemaLocation`, `room-testing` and an
 * instrumentation runner, but no test ever used them — so the hand-written
 * SQL in [DatabaseModule] was only ever validated by installing a build over
 * an older one and seeing whether it crashed. `runMigrationsAndValidate`
 * compares the migrated database against the schema Room generates from the
 * entities, column by column and index by index, which is the check that
 * actually catches a typo in a CREATE TABLE.
 *
 * Requires `schemas/3.json` to exist. It is produced by any build of this
 * module (`gradlew :core:database:kspDebugKotlin`) because `exportSchema` is
 * on; the file was missing from the v1.0.0 tree, which is exactly why the
 * 2 -> 3 migration had never been verified.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HarmonyDatabase::class.java,
    )

    @Test
    @Throws(IOException::class)
    fun migrate1To2AddsSongEditsAndRecreatesTheView() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 2, true, DatabaseModule.MIGRATION_1_2)
    }

    @Test
    @Throws(IOException::class)
    fun migrate2To3AddsDownloadRecords() {
        helper.createDatabase(TEST_DB, 2).close()
        helper.runMigrationsAndValidate(TEST_DB, 3, true, DatabaseModule.MIGRATION_2_3)
    }

    /** Same schema; only songs with mis-decoded tags are marked to be read again. */
    @Test
    @Throws(IOException::class)
    fun migrate3To4MarksMisDecodedTagsForRescan() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            fun insert(id: Long, title: String) = db.execSQL(
                "INSERT INTO songs (id, uri, title, artist, album, albumId, durationMs, fileSizeBytes, fileHash, " +
                    "lastModified, dateAdded, storageVolume) VALUES (?, ?, ?, 'A', 'B', 1, 1, 1, 'h', 500, 1, 'v')",
                arrayOf<Any>(id, "content://$id", title),
            )
            insert(1, "Bucureºti")
            insert(2, "Ștefan")
            insert(3, "Hello")
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, DatabaseModule.MIGRATION_3_4)
        db.query("SELECT id, lastModified FROM songs ORDER BY id").use { c ->
            val marked = buildMap { while (c.moveToNext()) put(c.getLong(0), c.getLong(1)) }
            org.junit.Assert.assertEquals(mapOf(1L to -1L, 2L to 500L, 3L to 500L), marked)
        }
    }

    /**
     * The path a user upgrading from the very first build actually takes.
     * Running the migrations back to back catches the case where each step is
     * fine on its own but the pair leaves the schema in a state Room rejects.
     */
    @Test
    @Throws(IOException::class)
    fun migrateAllTheWayFrom1() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            DatabaseModule.MIGRATION_1_2,
            DatabaseModule.MIGRATION_2_3,
            DatabaseModule.MIGRATION_3_4,
        )
    }

    private companion object {
        const val TEST_DB = "harmony-migration-test.db"
    }
}
