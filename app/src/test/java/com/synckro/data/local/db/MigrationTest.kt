package com.synckro.data.local.db

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM migration tests for [SynckroDatabase] using Robolectric.
 *
 * Each test manually constructs a database at the old schema version using
 * [FrameworkSQLiteOpenHelperFactory], runs the target [Migration], and then
 * asserts the resulting schema via SQLite PRAGMA queries.
 *
 * This approach keeps tests self-contained without requiring assets or build
 * configuration changes for schema files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** The database under test (opened in [setUp], closed in [tearDown]). */
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DB)
        db = openAtV6(context)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized && db.isOpen) db.close()
        context.deleteDatabase(TEST_DB)
        context.deleteDatabase("${TEST_DB}_v1")
    }

    // -------------------------------------------------------------------------
    // MIGRATION_6_7 – sync_pair changes
    // -------------------------------------------------------------------------

    @Test
    fun `MIGRATION_6_7 adds lastFullScanAtMs column to sync_pair`() {
        insertSyncPair(db)

        SynckroDatabase.MIGRATION_6_7.migrate(db)

        assertTrue(
            "lastFullScanAtMs column must exist in sync_pair after migration",
            "lastFullScanAtMs" in columnNames(db, "sync_pair"),
        )
    }

    @Test
    fun `MIGRATION_6_7 existing sync_pair row has null lastFullScanAtMs after migration`() {
        insertSyncPair(db)

        SynckroDatabase.MIGRATION_6_7.migrate(db)

        val cursor: Cursor =
            db.query(
                "SELECT lastFullScanAtMs FROM sync_pair WHERE displayName = 'Migration Test'",
                emptyArray<Any?>(),
            )
        cursor.use {
            assertTrue(it.moveToFirst())
            assertTrue(
                "lastFullScanAtMs should be NULL for pre-migration rows",
                it.isNull(0),
            )
        }
    }

    // -------------------------------------------------------------------------
    // MIGRATION_6_7 – local_index table
    // -------------------------------------------------------------------------

    @Test
    fun `MIGRATION_6_7 creates local_index table`() {
        SynckroDatabase.MIGRATION_6_7.migrate(db)

        assertTrue(
            "local_index table must exist after migration",
            "local_index" in tableNames(db),
        )
    }

    @Test
    fun `MIGRATION_6_7 local_index table has all required columns`() {
        SynckroDatabase.MIGRATION_6_7.migrate(db)

        val cols = columnNames(db, "local_index")
        assertTrue("pairId" in cols)
        assertTrue("relativePath" in cols)
        assertTrue("sizeBytes" in cols)
        assertTrue("mtimeMs" in cols)
        assertTrue("contentHash" in cols)
        assertTrue("remoteId" in cols)
    }

    @Test
    fun `MIGRATION_6_7 local_index accepts insert and rejects duplicate primary key`() {
        insertSyncPair(db)
        SynckroDatabase.MIGRATION_6_7.migrate(db)

        val pairId = firstPairId(db)
        db.execSQL(
            "INSERT INTO local_index (pairId, relativePath, sizeBytes, mtimeMs) " +
                "VALUES ($pairId, 'file.txt', 100, 12345)",
        )

        // Upsert (REPLACE) should succeed for the same PK
        db.execSQL(
            "INSERT OR REPLACE INTO local_index (pairId, relativePath, sizeBytes, mtimeMs) " +
                "VALUES ($pairId, 'file.txt', 200, 99999)",
        )

        val cursor: Cursor =
            db.query(
                "SELECT sizeBytes FROM local_index WHERE pairId = $pairId",
                emptyArray<Any?>(),
            )
        cursor.use {
            it.moveToFirst()
            assertEquals("sizeBytes should be updated to 200", 200L, it.getLong(0))
            assertEquals("Only one row should exist for the same PK", 1, it.count)
        }
    }

    @Test
    fun `MIGRATION_6_7 local_index CASCADE-deletes rows when parent sync_pair is removed`() {
        insertSyncPair(db)
        SynckroDatabase.MIGRATION_6_7.migrate(db)

        val pairId = firstPairId(db)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            "INSERT INTO local_index (pairId, relativePath, sizeBytes, mtimeMs) " +
                "VALUES ($pairId, 'file.txt', 100, 12345)",
        )

        db.execSQL("DELETE FROM sync_pair WHERE id = $pairId")

        val cursor: Cursor =
            db.query(
                "SELECT COUNT(*) FROM local_index WHERE pairId = $pairId",
                emptyArray<Any?>(),
            )
        cursor.use {
            it.moveToFirst()
            assertEquals(
                "local_index rows should be CASCADE-deleted with their parent pair",
                0,
                it.getInt(0),
            )
        }
    }

    // -------------------------------------------------------------------------
    // MIGRATION_7_8 – local_index remote metadata columns
    // -------------------------------------------------------------------------

    @Test
    fun `MIGRATION_7_8 adds remoteSizeBytes column to local_index`() {
        SynckroDatabase.MIGRATION_6_7.migrate(db)
        SynckroDatabase.MIGRATION_7_8.migrate(db)

        assertTrue(
            "remoteSizeBytes column must exist in local_index after migration",
            "remoteSizeBytes" in columnNames(db, "local_index"),
        )
    }

    @Test
    fun `MIGRATION_7_8 adds remoteMtimeMs column to local_index`() {
        SynckroDatabase.MIGRATION_6_7.migrate(db)
        SynckroDatabase.MIGRATION_7_8.migrate(db)

        assertTrue(
            "remoteMtimeMs column must exist in local_index after migration",
            "remoteMtimeMs" in columnNames(db, "local_index"),
        )
    }

    @Test
    fun `MIGRATION_7_8 adds remoteEtag column to local_index`() {
        SynckroDatabase.MIGRATION_6_7.migrate(db)
        SynckroDatabase.MIGRATION_7_8.migrate(db)

        assertTrue(
            "remoteEtag column must exist in local_index after migration",
            "remoteEtag" in columnNames(db, "local_index"),
        )
    }

    @Test
    fun `MIGRATION_7_8 existing local_index row has null remote columns after migration`() {
        insertSyncPair(db)
        SynckroDatabase.MIGRATION_6_7.migrate(db)

        val pairId = firstPairId(db)
        db.execSQL(
            "INSERT INTO local_index (pairId, relativePath, sizeBytes, mtimeMs) " +
                "VALUES ($pairId, 'notes.txt', 512, 1000000)",
        )

        SynckroDatabase.MIGRATION_7_8.migrate(db)

        val cursor =
            db.query(
                "SELECT remoteSizeBytes, remoteMtimeMs, remoteEtag FROM local_index WHERE pairId = $pairId",
                emptyArray<Any?>(),
            )
        cursor.use {
            assertTrue(it.moveToFirst())
            assertTrue("remoteSizeBytes should be NULL for pre-migration rows", it.isNull(0))
            assertTrue("remoteMtimeMs should be NULL for pre-migration rows", it.isNull(1))
            assertTrue("remoteEtag should be NULL for pre-migration rows", it.isNull(2))
        }
    }

    // -------------------------------------------------------------------------
    // MIGRATION_8_9 – retentionDays column on sync_pair
    // -------------------------------------------------------------------------

    @Test
    fun `MIGRATION_8_9 adds retentionDays column to sync_pair`() {
        insertSyncPair(db)
        SynckroDatabase.MIGRATION_6_7.migrate(db)
        SynckroDatabase.MIGRATION_7_8.migrate(db)
        SynckroDatabase.MIGRATION_8_9.migrate(db)

        assertTrue(
            "retentionDays column must exist in sync_pair after migration",
            "retentionDays" in columnNames(db, "sync_pair"),
        )
    }

    @Test
    fun `MIGRATION_8_9 existing sync_pair row has null retentionDays after migration`() {
        insertSyncPair(db)
        SynckroDatabase.MIGRATION_6_7.migrate(db)
        SynckroDatabase.MIGRATION_7_8.migrate(db)
        SynckroDatabase.MIGRATION_8_9.migrate(db)

        val cursor: Cursor =
            db.query(
                "SELECT retentionDays FROM sync_pair WHERE displayName = 'Migration Test'",
                emptyArray<Any?>(),
            )
        cursor.use {
            assertTrue(it.moveToFirst())
            assertTrue(
                "retentionDays should be NULL for pre-migration rows",
                it.isNull(0),
            )
        }
    }

    // -------------------------------------------------------------------------
    // Full migration chain v1 → v9
    // -------------------------------------------------------------------------

    @Test
    fun `all migrations from v1 to v9 run without error`() {
        val v1Db = openAtV1(context)
        try {
            SynckroDatabase.MIGRATION_1_2.migrate(v1Db)
            SynckroDatabase.MIGRATION_2_3.migrate(v1Db)
            SynckroDatabase.MIGRATION_3_4.migrate(v1Db)
            SynckroDatabase.MIGRATION_4_5.migrate(v1Db)
            SynckroDatabase.MIGRATION_5_6.migrate(v1Db)
            SynckroDatabase.MIGRATION_6_7.migrate(v1Db)
            SynckroDatabase.MIGRATION_7_8.migrate(v1Db)
            SynckroDatabase.MIGRATION_8_9.migrate(v1Db)

            val syncPairCols = columnNames(v1Db, "sync_pair")
            assertTrue("retentionDays must exist in sync_pair after full migration chain", "retentionDays" in syncPairCols)
            val localIndexCols = columnNames(v1Db, "local_index")
            assertTrue("remoteSizeBytes must exist after full migration chain", "remoteSizeBytes" in localIndexCols)
            assertTrue("remoteMtimeMs must exist after full migration chain", "remoteMtimeMs" in localIndexCols)
            assertTrue("remoteEtag must exist after full migration chain", "remoteEtag" in localIndexCols)
        } finally {
            v1Db.close()
            context.deleteDatabase("${TEST_DB}_v1")
        }
    }

    // -------------------------------------------------------------------------
    // Full migration chain v1 → v8
    // -------------------------------------------------------------------------

    @Test
    fun `all migrations from v1 to v8 run without error`() {
        val v1Db = openAtV1(context)
        try {
            SynckroDatabase.MIGRATION_1_2.migrate(v1Db)
            SynckroDatabase.MIGRATION_2_3.migrate(v1Db)
            SynckroDatabase.MIGRATION_3_4.migrate(v1Db)
            SynckroDatabase.MIGRATION_4_5.migrate(v1Db)
            SynckroDatabase.MIGRATION_5_6.migrate(v1Db)
            SynckroDatabase.MIGRATION_6_7.migrate(v1Db)
            SynckroDatabase.MIGRATION_7_8.migrate(v1Db)

            val cols = columnNames(v1Db, "local_index")
            assertTrue("remoteSizeBytes must exist after full migration chain", "remoteSizeBytes" in cols)
            assertTrue("remoteMtimeMs must exist after full migration chain", "remoteMtimeMs" in cols)
            assertTrue("remoteEtag must exist after full migration chain", "remoteEtag" in cols)
        } finally {
            v1Db.close()
            context.deleteDatabase("${TEST_DB}_v1")
        }
    }

    // -------------------------------------------------------------------------
    // Full migration chain v1 → v7
    // -------------------------------------------------------------------------

    @Test
    fun `all migrations from v1 to v7 run without error`() {
        val v1Db = openAtV1(context)
        try {
            SynckroDatabase.MIGRATION_1_2.migrate(v1Db)
            SynckroDatabase.MIGRATION_2_3.migrate(v1Db)
            SynckroDatabase.MIGRATION_3_4.migrate(v1Db)
            SynckroDatabase.MIGRATION_4_5.migrate(v1Db)
            SynckroDatabase.MIGRATION_5_6.migrate(v1Db)
            SynckroDatabase.MIGRATION_6_7.migrate(v1Db)

            val tables = tableNames(v1Db)
            assertTrue("account must exist after full migration chain", "account" in tables)
            assertTrue("sync_pair must exist after full migration chain", "sync_pair" in tables)
            assertTrue("file_index must exist after full migration chain", "file_index" in tables)
            assertTrue("sync_event must exist after full migration chain", "sync_event" in tables)
            assertTrue(
                "conflict_record must exist after full migration chain",
                "conflict_record" in tables,
            )
            assertTrue(
                "local_index must exist after full migration chain",
                "local_index" in tables,
            )
            assertTrue(
                "lastFullScanAtMs must exist in sync_pair after full migration chain",
                "lastFullScanAtMs" in columnNames(v1Db, "sync_pair"),
            )
        } finally {
            v1Db.close()
            context.deleteDatabase("${TEST_DB}_v1")
        }
    }

    // -------------------------------------------------------------------------
    // Schema helpers
    // -------------------------------------------------------------------------

    /** Returns the set of column names for [table] using PRAGMA table_info. */
    private fun columnNames(
        db: SupportSQLiteDatabase,
        table: String,
    ): Set<String> {
        val result = mutableSetOf<String>()
        val cursor: Cursor = db.query("PRAGMA table_info(`$table`)", emptyArray<Any?>())
        cursor.use {
            val nameIdx = it.getColumnIndexOrThrow("name")
            while (it.moveToNext()) {
                result.add(it.getString(nameIdx))
            }
        }
        return result
    }

    /** Returns the set of non-system table names in the database. */
    private fun tableNames(db: SupportSQLiteDatabase): Set<String> {
        val result = mutableSetOf<String>()
        val cursor: Cursor =
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
                emptyArray<Any?>(),
            )
        cursor.use {
            while (it.moveToNext()) {
                result.add(it.getString(0))
            }
        }
        return result
    }

    /** Returns the `id` of the first row in `sync_pair`. */
    private fun firstPairId(db: SupportSQLiteDatabase): Long {
        val cursor: Cursor = db.query("SELECT id FROM sync_pair LIMIT 1", emptyArray<Any?>())
        return cursor.use {
            it.moveToFirst()
            it.getLong(0)
        }
    }

    // -------------------------------------------------------------------------
    // Database factory helpers
    // -------------------------------------------------------------------------

    private fun insertSyncPair(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO sync_pair (displayName, localTreeUri, provider, remoteFolderId, " +
                "direction, conflictPolicy, includeGlobs, excludeGlobs, wifiOnly, " +
                "requiresCharging, scheduleIntervalMinutes) VALUES " +
                "('Migration Test', 'content://test', 'ONEDRIVE', 'remote123', " +
                "'BIDIRECTIONAL', 'NEWEST_WINS', '', '', 1, 0, 60)",
        )
    }

    companion object {
        private const val TEST_DB = "synckro_migration_test.db"

        /**
         * Opens a [SupportSQLiteDatabase] whose schema matches the v6 export in
         * `app/schemas/.../6.json`.  Tables are created in [onCreate]; migrations are
         * intentionally left empty so that [SynckroDatabase.MIGRATION_6_7] can be
         * applied manually in tests.
         */
        fun openAtV6(context: Context): SupportSQLiteDatabase =
            FrameworkSQLiteOpenHelperFactory()
                .create(
                    SupportSQLiteOpenHelper.Configuration
                        .builder(context)
                        .name(TEST_DB)
                        .callback(
                            object : SupportSQLiteOpenHelper.Callback(6) {
                                override fun onCreate(db: SupportSQLiteDatabase) = createV6Schema(db)

                                override fun onUpgrade(
                                    db: SupportSQLiteDatabase,
                                    oldVersion: Int,
                                    newVersion: Int,
                                ) = Unit
                            },
                        ).build(),
                ).writableDatabase

        /**
         * Opens a [SupportSQLiteDatabase] whose schema matches the v1 export in
         * `app/schemas/.../1.json`.
         */
        private fun openAtV1(context: Context): SupportSQLiteDatabase =
            FrameworkSQLiteOpenHelperFactory()
                .create(
                    SupportSQLiteOpenHelper.Configuration
                        .builder(context)
                        .name("${TEST_DB}_v1")
                        .callback(
                            object : SupportSQLiteOpenHelper.Callback(1) {
                                override fun onCreate(db: SupportSQLiteDatabase) = createV1Schema(db)

                                override fun onUpgrade(
                                    db: SupportSQLiteDatabase,
                                    oldVersion: Int,
                                    newVersion: Int,
                                ) = Unit
                            },
                        ).build(),
                ).writableDatabase

        // -----------------------------------------------------------------
        // Schema creation SQL (derived from exported JSON schema files)
        // -----------------------------------------------------------------

        /** Recreates the v1 schema (sync_pair + file_index). */
        private fun createV1Schema(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sync_pair` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`displayName` TEXT NOT NULL, `localTreeUri` TEXT NOT NULL, " +
                    "`provider` TEXT NOT NULL, `remoteFolderId` TEXT NOT NULL, " +
                    "`direction` TEXT NOT NULL, `conflictPolicy` TEXT NOT NULL, " +
                    "`includeGlobs` TEXT NOT NULL, `excludeGlobs` TEXT NOT NULL, " +
                    "`wifiOnly` INTEGER NOT NULL, `requiresCharging` INTEGER NOT NULL, " +
                    "`lastDeltaToken` TEXT, `lastSyncAtMs` INTEGER)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `file_index` (" +
                    "`pairId` INTEGER NOT NULL, `relativePath` TEXT NOT NULL, " +
                    "`localSize` INTEGER NOT NULL, `localLastModifiedMs` INTEGER NOT NULL, " +
                    "`localHash` TEXT, `remoteId` TEXT, `remoteETag` TEXT, " +
                    "`remoteSize` INTEGER, `remoteLastModifiedMs` INTEGER, " +
                    "PRIMARY KEY(`pairId`, `relativePath`), " +
                    "FOREIGN KEY(`pairId`) REFERENCES `sync_pair`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
        }

        /** Recreates the v6 schema (all tables present at version 6). */
        private fun createV6Schema(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `account` (" +
                    "`id` TEXT NOT NULL, `providerType` TEXT NOT NULL, " +
                    "`displayName` TEXT NOT NULL, `email` TEXT, " +
                    "`createdAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sync_pair` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`displayName` TEXT NOT NULL, `localTreeUri` TEXT NOT NULL, " +
                    "`provider` TEXT NOT NULL, `remoteFolderId` TEXT NOT NULL, " +
                    "`direction` TEXT NOT NULL, `conflictPolicy` TEXT NOT NULL, " +
                    "`includeGlobs` TEXT NOT NULL, `excludeGlobs` TEXT NOT NULL, " +
                    "`wifiOnly` INTEGER NOT NULL, `requiresCharging` INTEGER NOT NULL, " +
                    "`lastDeltaToken` TEXT, `lastSyncAtMs` INTEGER, " +
                    "`lastSyncResult` TEXT, `scheduleIntervalMinutes` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `file_index` (" +
                    "`pairId` INTEGER NOT NULL, `relativePath` TEXT NOT NULL, " +
                    "`localSize` INTEGER NOT NULL, `localLastModifiedMs` INTEGER NOT NULL, " +
                    "`localHash` TEXT, `remoteId` TEXT, `remoteETag` TEXT, " +
                    "`remoteSize` INTEGER, `remoteLastModifiedMs` INTEGER, `mimeType` TEXT, " +
                    "PRIMARY KEY(`pairId`, `relativePath`), " +
                    "FOREIGN KEY(`pairId`) REFERENCES `sync_pair`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sync_event` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`pairId` INTEGER, `timestampMs` INTEGER NOT NULL, " +
                    "`level` TEXT NOT NULL, `tag` TEXT NOT NULL, `message` TEXT NOT NULL, " +
                    "FOREIGN KEY(`pairId`) REFERENCES `sync_pair`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sync_event_pairId` " +
                    "ON `sync_event` (`pairId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sync_event_timestampMs` " +
                    "ON `sync_event` (`timestampMs`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `conflict_record` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`pairId` INTEGER NOT NULL, `relativePath` TEXT NOT NULL, " +
                    "`localLastModifiedMs` INTEGER NOT NULL, " +
                    "`remoteLastModifiedMs` INTEGER NOT NULL, " +
                    "`detectedAtMs` INTEGER NOT NULL, `resolution` TEXT, " +
                    "FOREIGN KEY(`pairId`) REFERENCES `sync_pair`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_conflict_record_pairId` " +
                    "ON `conflict_record` (`pairId`)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_conflict_record_pairId_relativePath` " +
                    "ON `conflict_record` (`pairId`, `relativePath`)",
            )
        }
    }
}
