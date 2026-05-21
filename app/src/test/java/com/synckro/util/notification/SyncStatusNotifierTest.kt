package com.synckro.util.notification

import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.synckro.SynckroApp
import com.synckro.data.repository.SettingsRepository
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncStatusNotifierTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val fakePair =
        SyncPair(
            id = 1L,
            displayName = "My Docs",
            localTreeUri = "content://local/path",
            provider = CloudProviderType.GOOGLE_DRIVE,
            accountId = "test-account-id",
            remoteFolderId = "remote-folder-id",
            direction = SyncDirection.BIDIRECTIONAL,
            conflictPolicy = ConflictPolicy.NEWEST_WINS,
            wifiOnly = false,
            autoSyncEnabled = true,
            scheduleIntervalMinutes = 60,
        )

    private fun buildRepository(): SettingsRepository {
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { tempFolder.newFile("sync_status_notifier.preferences_pb") },
            )
        return SettingsRepository(dataStore)
    }

    @Test
    fun `app startup registers sync status channel`() {
        val app = ApplicationProvider.getApplicationContext<SynckroApp>()
        val nm = app.getSystemService(NotificationManager::class.java)

        val channel =
            requireNotNull(nm.getNotificationChannel(SyncStatusNotifier.SYNC_STATUS_CHANNEL_ID))

        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun `notifyFailure does not post notification when disabled`() =
        testScope.runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val nm = context.getSystemService(NotificationManager::class.java)
            val repo = buildRepository().also { it.setNotifyOnFailure(false) }
            val notifier = SyncStatusNotifier(repo)

            notifier.notifyFailure(fakePair, "boom")

            assertEquals(0, Shadows.shadowOf(nm).size())
        }

    @Test
    fun `notifySuccessSummary remains a no-op when enabled`() =
        testScope.runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val nm = context.getSystemService(NotificationManager::class.java)
            val repo = buildRepository().also { it.setNotifyOnSuccess(true) }
            val notifier = SyncStatusNotifier(repo)

            notifier.notifySuccessSummary(fakePair, 3, 1)

            assertEquals(0, Shadows.shadowOf(nm).size())
        }
}
