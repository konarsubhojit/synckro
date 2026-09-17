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
class Migration17To18InstrumentedTest {
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
    fun migrate17To18_addsExcludedRelativePathsAndRemoteContentHashColumns() {
        helper.createDatabase(TEST_DB, 17).apply {
            insertSyncPair(displayName = "Migration Test")
            close()
        }

        val migrated =
            helper.runMigrationsAndValidate(
                TEST_DB,
                18,
                true,
                SynckroDatabase.MIGRATION_17_18,
            )

        assertTrue(columnNames(migrated, "sync_pair").contains("excludedRelativePaths"))
        assertEquals("", excludedRelativePathsFor(migrated, "Migration Test"))
        assertTrue(columnNames(migrated, "local_index").contains("remoteContentHash"))
    }

    private fun SupportSQLiteDatabase.insertSyncPair(displayName: String) {
        execSQL(
            "INSERT INTO sync_pair (displayName, localTreeUri, provider, remoteFolderId, " +
                "direction, conflictPolicy, includeGlobs, excludeGlobs, wifiOnly, " +
                "requiresCharging, autoSyncEnabled, scheduleIntervalMinutes, " +
                "excludeSubfolders, excludeEmptyFolders, instantSyncEnabled) VALUES " +
                "(?, 'content://test', 'GOOGLE_DRIVE', 'remote123', " +
                "'BIDIRECTIONAL', 'NEWEST_WINS', '', '', 1, 0, 1, 60, 0, 0, 0)",
            arrayOf<Any?>(displayName),
        )
    }

    private fun excludedRelativePathsFor(
        db: SupportSQLiteDatabase,
        displayName: String,
    ): String? =
        db
            .query(
                "SELECT excludedRelativePaths FROM sync_pair WHERE displayName = ?",
                arrayOf<Any?>(displayName),
            ).use {
                assertTrue(it.moveToFirst())
                if (it.isNull(0)) null else it.getString(0)
            }

    private fun columnNames(
        db: SupportSQLiteDatabase,
        table: String,
    ): Set<String> =
        db.query("PRAGMA table_info(`$table`)", emptyArray()).use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    add(cursor.getString(nameIndex))
                }
            }
        }

    private companion object {
        private const val TEST_DB = "migration-17-18-android.db"
    }
}
