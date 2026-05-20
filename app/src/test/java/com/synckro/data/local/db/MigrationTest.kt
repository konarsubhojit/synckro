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
    // MIGRATION_8_9 – autoSyncEnabled column on sync_pair
    // -------------------------------------------------------------------------

    @Test
    fun `MIGRATION_8_9 adds autoSyncEnabled column to sync_pair`() {
        insertSyncPair(db)
        SynckroDatabase.MIGRATION_6_7.migrate(db)
        SynckroDatabase.MIGRATION_7_8.migrate(db)
        SynckroDatabase.MIGRATION_8_9.migrate(db)

        assertTrue(
            "autoSyncEnabled column must exist in sync_pair after migration",
            "autoSyncEnabled" in columnNames(db, "sync_pair"),
        )
    }

    // -------------------------------------------------------------------------
    // MIGRATION_9_10 – retentionDays column on sync_pair
    // -------------------------------------------------------------------------

    @Test
    fun `MIGRATION_9_10 adds retentionDays column to sync_pair`() {
        insertSyncPair(db)
        SynckroDatabase.MIGRATION_6_7.migrate(db)
        SynckroDatabase.MIGRATION_7_8.migrate(db)
        SynckroDatabase.MIGRATION_8_9.migrate(db)
        SynckroDatabase.MIGRATION_9_10.migrate(db)

        assertTrue(
            "retentionDays column must exist in sync_pair after migration",
            "retentionDays" in columnNames(db, "sync_pair"),
        )
    }

    @Test
    fun `MIGRATION_9_10 existing sync_pair row has null retentionDays after migration`() {
        insertSyncPair(db)
        SynckroDatabase.MIGRATION_6_7.migrate(db)
        SynckroDatabase.MIGRATION_7_8.migrate(db)
        SynckroDatabase.MIGRATION_8_9.migrate(db)
        SynckroDatabase.MIGRATION_9_10.migrate(db)

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
    // MIGRATION_11_12 – accountId column on sync_pair (multi-account support)
    // -------------------------------------------------------------------------

    /** Brings [db] from the test bootstrap (v6) up to v11 in one shot. */
    private fun migrateToV11(db: SupportSQLiteDatabase) {
        SynckroDatabase.MIGRATION_6_7.migrate(db)
        SynckroDatabase.MIGRATION_7_8.migrate(db)
        SynckroDatabase.MIGRATION_8_9.migrate(db)
        SynckroDatabase.MIGRATION_9_10.migrate(db)
        SynckroDatabase.MIGRATION_10_11.migrate(db)
    }

    private fun insertAccount(
        db: SupportSQLiteDatabase,
        id: String,
        providerType: String,
        createdAtMillis: Long = 1L,
    ) {
        db.execSQL(
            "INSERT INTO account (id, providerType, displayName, email, createdAtMillis) " +
                "VALUES ('$id', '$providerType', '$id-name', null, $createdAtMillis)",
        )
    }

    private fun insertSyncPairWithProvider(
        db: SupportSQLiteDatabase,
        displayName: String,
        provider: String,
    ) {
        db.execSQL(
            "INSERT INTO sync_pair (displayName, localTreeUri, provider, remoteFolderId, " +
                "direction, conflictPolicy, includeGlobs, excludeGlobs, wifiOnly, " +
                "requiresCharging, scheduleIntervalMinutes) VALUES " +
                "('$displayName', 'content://test', '$provider', 'remote123', " +
                "'BIDIRECTIONAL', 'NEWEST_WINS', '', '', 1, 0, 60)",
        )
    }

    private fun accountIdFor(
        db: SupportSQLiteDatabase,
        displayName: String,
    ): String? {
        val cursor =
            db.query(
                "SELECT accountId FROM sync_pair WHERE displayName = '$displayName'",
                emptyArray<Any?>(),
            )
        return cursor.use {
            assertTrue(it.moveToFirst())
            if (it.isNull(0)) null else it.getString(0)
        }
    }

    @Test
    fun `MIGRATION_11_12 adds accountId column to sync_pair`() {
        migrateToV11(db)

        SynckroDatabase.MIGRATION_11_12.migrate(db)

        assertTrue(
            "accountId column must exist in sync_pair after migration",
            "accountId" in columnNames(db, "sync_pair"),
        )
    }

    @Test
    fun `MIGRATION_11_12 backfills accountId when provider has exactly one account`() {
        migrateToV11(db)
        insertAccount(db, id = "acc-onedrive-1", providerType = "ONEDRIVE")
        insertSyncPairWithProvider(db, displayName = "OneDrive Pair", provider = "ONEDRIVE")

        SynckroDatabase.MIGRATION_11_12.migrate(db)

        assertEquals(
            "accountId should be backfilled from the single matching account row",
            "acc-onedrive-1",
            accountIdFor(db, "OneDrive Pair"),
        )
    }

    @Test
    fun `MIGRATION_11_12 leaves accountId NULL when provider has no accounts`() {
        migrateToV11(db)
        // No account row for ONEDRIVE.
        insertSyncPairWithProvider(db, displayName = "Orphan Pair", provider = "ONEDRIVE")

        SynckroDatabase.MIGRATION_11_12.migrate(db)

        assertEquals(
            "accountId should remain NULL when no matching account exists",
            null,
            accountIdFor(db, "Orphan Pair"),
        )
    }

    @Test
    fun `MIGRATION_11_12 leaves accountId NULL when provider has multiple accounts`() {
        // When a provider already has multiple accounts at upgrade time we cannot
        // safely guess which one owns the pair; leave it for the user to re-link.
        migrateToV11(db)
        insertAccount(db, id = "acc-gdrive-1", providerType = "GOOGLE_DRIVE", createdAtMillis = 1L)
        insertAccount(db, id = "acc-gdrive-2", providerType = "GOOGLE_DRIVE", createdAtMillis = 2L)
        insertSyncPairWithProvider(db, displayName = "Ambiguous Pair", provider = "GOOGLE_DRIVE")

        SynckroDatabase.MIGRATION_11_12.migrate(db)

        assertEquals(
            "accountId must stay NULL when multiple candidate accounts exist",
            null,
            accountIdFor(db, "Ambiguous Pair"),
        )
    }

    @Test
    fun `MIGRATION_11_12 only backfills pairs whose provider matches the account`() {
        migrateToV11(db)
        insertAccount(db, id = "acc-onedrive-1", providerType = "ONEDRIVE")
        insertSyncPairWithProvider(db, displayName = "OD Pair", provider = "ONEDRIVE")
        insertSyncPairWithProvider(db, displayName = "GD Pair", provider = "GOOGLE_DRIVE")

        SynckroDatabase.MIGRATION_11_12.migrate(db)

        assertEquals("acc-onedrive-1", accountIdFor(db, "OD Pair"))
        assertEquals(
            "GOOGLE_DRIVE pair must not pick up an ONEDRIVE accountId",
            null,
            accountIdFor(db, "GD Pair"),
        )
    }

    @Test
    fun `MIGRATION_11_12 creates index on accountId`() {
        migrateToV11(db)

        SynckroDatabase.MIGRATION_11_12.migrate(db)

        val cursor =
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'sync_pair'",
                emptyArray<Any?>(),
            )
        val indexNames = mutableSetOf<String>()
        cursor.use {
            while (it.moveToNext()) indexNames.add(it.getString(0))
        }
        assertTrue(
            "index_sync_pair_accountId must exist after migration; got $indexNames",
            "index_sync_pair_accountId" in indexNames,
        )
    }

    // -------------------------------------------------------------------------
    // MIGRATION_12_13 – localDocumentId + remoteThumbnailUrl columns on file_index
    // -------------------------------------------------------------------------

    /** Brings [db] from the test bootstrap (v6) up to v12 in one shot. */
    private fun migrateToV12(db: SupportSQLiteDatabase) {
        migrateToV11(db)
        SynckroDatabase.MIGRATION_11_12.migrate(db)
    }

    @Test
    fun `MIGRATION_12_13 adds localDocumentId column to file_index`() {
        migrateToV12(db)

        SynckroDatabase.MIGRATION_12_13.migrate(db)

        assertTrue(
            "localDocumentId column must exist in file_index after migration",
            "localDocumentId" in columnNames(db, "file_index"),
        )
    }

    @Test
    fun `MIGRATION_12_13 adds remoteThumbnailUrl column to file_index`() {
        migrateToV12(db)

        SynckroDatabase.MIGRATION_12_13.migrate(db)

        assertTrue(
            "remoteThumbnailUrl column must exist in file_index after migration",
            "remoteThumbnailUrl" in columnNames(db, "file_index"),
        )
    }

    @Test
    fun `MIGRATION_12_13 existing file_index rows have null thumbnail columns after migration`() {
        migrateToV12(db)

        // Insert a sync_pair and a file_index entry at v12 (no thumbnail columns yet).
        db.execSQL(
            "INSERT INTO sync_pair (displayName, localTreeUri, provider, remoteFolderId, " +
                "direction, conflictPolicy, includeGlobs, excludeGlobs, wifiOnly, " +
                "requiresCharging, scheduleIntervalMinutes, autoSyncEnabled, " +
                "excludeSubfolders, excludeEmptyFolders) VALUES " +
                "('Test', 'content://tree/test', 'ONEDRIVE', 'root', " +
                "'BIDIRECTIONAL', 'NEWEST_WINS', '', '', 1, 0, 60, 1, 0, 0)",
        )
        val pairId =
            db.query("SELECT id FROM sync_pair LIMIT 1", emptyArray<Any?>()).use {
                it.moveToFirst(); it.getLong(0)
            }
        db.execSQL(
            "INSERT INTO file_index (pairId, relativePath, localSize, localLastModifiedMs) " +
                "VALUES ($pairId, 'photo.jpg', 1024, 1000)",
        )

        SynckroDatabase.MIGRATION_12_13.migrate(db)

        val cursor =
            db.query(
                "SELECT localDocumentId, remoteThumbnailUrl FROM file_index " +
                    "WHERE pairId = $pairId AND relativePath = 'photo.jpg'",
                emptyArray<Any?>(),
            )
        cursor.use {
            assertTrue("Expected one row", it.moveToFirst())
            assertTrue("localDocumentId should be NULL", it.isNull(0))
            assertTrue("remoteThumbnailUrl should be NULL", it.isNull(1))
        }
    }

    // -------------------------------------------------------------------------
    // Full migration chain v1 → v13
    // -------------------------------------------------------------------------

    @Test
    fun `all migrations from v1 to v13 run without error`() {
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
            SynckroDatabase.MIGRATION_9_10.migrate(v1Db)
            SynckroDatabase.MIGRATION_10_11.migrate(v1Db)
            SynckroDatabase.MIGRATION_11_12.migrate(v1Db)
            SynckroDatabase.MIGRATION_12_13.migrate(v1Db)

            val fileIndexCols = columnNames(v1Db, "file_index")
            assertTrue(
                "localDocumentId must exist in file_index after full v1→v13 migration chain",
                "localDocumentId" in fileIndexCols,
            )
            assertTrue(
                "remoteThumbnailUrl must exist in file_index after full v1→v13 migration chain",
                "remoteThumbnailUrl" in fileIndexCols,
            )
        } finally {
            v1Db.close()
            context.deleteDatabase("${TEST_DB}_v1")
        }
    }

    // -------------------------------------------------------------------------
    // Full migration chain v1 → v12
    // -------------------------------------------------------------------------

    @Test
    fun `all migrations from v1 to v12 run without error and backfill accountId`() {
        val v1Db = openAtV1(context)
        try {
            // Bring the v1 bootstrap DB up to v10 (pre-account era for sync_pair).
            SynckroDatabase.MIGRATION_1_2.migrate(v1Db)
            SynckroDatabase.MIGRATION_2_3.migrate(v1Db)
            SynckroDatabase.MIGRATION_3_4.migrate(v1Db)
            SynckroDatabase.MIGRATION_4_5.migrate(v1Db)
            SynckroDatabase.MIGRATION_5_6.migrate(v1Db)
            SynckroDatabase.MIGRATION_6_7.migrate(v1Db)
            SynckroDatabase.MIGRATION_7_8.migrate(v1Db)
            SynckroDatabase.MIGRATION_8_9.migrate(v1Db)
            SynckroDatabase.MIGRATION_9_10.migrate(v1Db)

            // Seed an account row (created by MIGRATION_1_2) and a sync_pair row
            // before applying the latest migrations, so the v11→v12 backfill SQL
            // exercises the full v1 schema's column shape rather than a
            // freshly-created v6 baseline.
            insertAccount(v1Db, id = "acc-od-1", providerType = "ONEDRIVE")
            insertSyncPairWithProvider(v1Db, displayName = "OD Pair v1Chain", provider = "ONEDRIVE")

            SynckroDatabase.MIGRATION_10_11.migrate(v1Db)
            SynckroDatabase.MIGRATION_11_12.migrate(v1Db)

            val syncPairCols = columnNames(v1Db, "sync_pair")
            assertTrue("excludeSubfolders must exist after v1→v12 chain", "excludeSubfolders" in syncPairCols)
            assertTrue("excludeEmptyFolders must exist after v1→v12 chain", "excludeEmptyFolders" in syncPairCols)
            assertTrue("accountId must exist after v1→v12 chain", "accountId" in syncPairCols)

            assertEquals(
                "accountId should be backfilled by MIGRATION_11_12 in the v1→v12 chain",
                "acc-od-1",
                accountIdFor(v1Db, "OD Pair v1Chain"),
            )
        } finally {
            v1Db.close()
            context.deleteDatabase("${TEST_DB}_v1")
        }
    }

    // -------------------------------------------------------------------------
    // Full migration chain v1 → v10
    // -------------------------------------------------------------------------

    @Test
    fun `all migrations from v1 to v10 run without error`() {
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
            SynckroDatabase.MIGRATION_9_10.migrate(v1Db)

            val syncPairCols = columnNames(v1Db, "sync_pair")
            assertTrue("autoSyncEnabled must exist in sync_pair after full migration chain", "autoSyncEnabled" in syncPairCols)
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
