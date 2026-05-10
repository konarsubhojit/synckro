package com.synckro.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration11To12InstrumentedTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            SynckroDatabase::class.java.canonicalName,
            FrameworkSQLiteOpenHelperFactory(),
        )

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DB)
    }

    @Test
    fun migrate11To12_backfillsMatchingAccountAndCreatesIndex() {
        helper.createDatabase(TEST_DB, 11).apply {
            insertAccount(id = "acc-onedrive-1", providerType = "ONEDRIVE")
            insertSyncPair(displayName = "OneDrive Pair", provider = "ONEDRIVE")
            close()
        }

        val migrated =
            helper.runMigrationsAndValidate(
                TEST_DB,
                12,
                true,
                SynckroDatabase.MIGRATION_11_12,
            )

        assertEquals("acc-onedrive-1", accountIdFor(migrated, "OneDrive Pair"))
        assertTrue(indexNames(migrated).contains("index_sync_pair_accountId"))
    }

    private fun SupportSQLiteDatabase.insertAccount(
        id: String,
        providerType: String,
    ) {
        execSQL(
            "INSERT INTO account (id, providerType, displayName, email, createdAtMillis) " +
                "VALUES ('$id', '$providerType', '$id-name', NULL, 1)",
        )
    }

    private fun SupportSQLiteDatabase.insertSyncPair(
        displayName: String,
        provider: String,
    ) {
        execSQL(
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
    ): String? =
        db
            .query(
                "SELECT accountId FROM sync_pair WHERE displayName = '$displayName'",
                emptyArray(),
            ).use {
                assertTrue(it.moveToFirst())
                if (it.isNull(0)) null else it.getString(0)
            }

    private fun indexNames(db: SupportSQLiteDatabase): Set<String> =
        db
            .query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'sync_pair'",
                emptyArray(),
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0))
                    }
                }
            }

    private companion object {
        private const val TEST_DB = "migration-11-12-android.db"
    }
}
