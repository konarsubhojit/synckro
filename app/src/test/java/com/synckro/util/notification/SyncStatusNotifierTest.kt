package com.synckro.util.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.synckro.R
import com.synckro.SynckroApp
import com.synckro.data.repository.SettingsRepository
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncStatusNotifierTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
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

    private fun buildNotifier(
        context: Context,
        repository: SettingsRepository,
        scope: CoroutineScope,
    ): SyncStatusNotifier =
        SyncStatusNotifier(
            context = context,
            settingsRepository = repository,
            workerCompletionAggregator = WorkerCompletionAggregator(scope),
        )

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
            val notifier = buildNotifier(context, repo, this)

            notifier.notifyFailure(fakePair, "boom")

            assertEquals(0, Shadows.shadowOf(nm).size())
        }

    @Test
    fun `notifyFailure posts notification with logs deep link and replaces duplicate`() =
        testScope.runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val nm = context.getSystemService(NotificationManager::class.java)
            shadowOf(context.applicationContext as Application)
                .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            val repo = buildRepository().also { it.setNotifyOnFailure(true) }
            val notifier = buildNotifier(context, repo, this)

            notifier.notifyFailure(fakePair, "Authentication failed: timeout during token refresh")
            notifier.notifyFailure(fakePair, "Rate limited (retry in 5000 ms)")

            assertEquals(1, shadowOf(nm).size())
            val active = nm.activeNotifications.single()
            val notification = active.notification

            assertEquals(fakePair.id.toInt(), active.id)
            assertEquals(fakePair.displayName, notification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(
                context.getString(R.string.sync_failure_rate_limited_summary),
                notification.extras.getString(Notification.EXTRA_TEXT),
            )

            val pendingIntent = notification.contentIntent
            assertNotNull(pendingIntent)
            val intent = shadowOf(requireNotNull(pendingIntent)).savedIntent
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP, intent.flags)
            assertEquals(SyncStatusNotifier.ACTION_OPEN_LOGS, intent.action)
            assertEquals(fakePair.id, intent.getLongExtra(SyncStatusNotifier.EXTRA_PAIR_ID, -1L))
        }

    @Test
    fun `notifySuccessSummary does not post notification when disabled`() =
        testScope.runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val nm = context.getSystemService(NotificationManager::class.java)
            shadowOf(context.applicationContext as Application)
                .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            val repo = buildRepository().also { it.setNotifyOnSuccess(false) }
            val notifier = buildNotifier(context, repo, this)

            notifier.notifySuccessSummary(fakePair, 3, 1)
            runCurrent()
            advanceTimeBy(WorkerCompletionAggregator.DEFAULT_WINDOW_MS)
            advanceUntilIdle()

            assertEquals(0, Shadows.shadowOf(nm).size())
        }

    @Test
    fun `notifySuccessSummary suppresses no-op runs`() =
        testScope.runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val nm = context.getSystemService(NotificationManager::class.java)
            shadowOf(context.applicationContext as Application)
                .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            val repo = buildRepository().also { it.setNotifyOnSuccess(true) }
            val notifier = buildNotifier(context, repo, this)

            notifier.notifySuccessSummary(fakePair, 0, 0)
            runCurrent()
            advanceTimeBy(WorkerCompletionAggregator.DEFAULT_WINDOW_MS)
            advanceUntilIdle()

            assertEquals(0, Shadows.shadowOf(nm).size())
        }

    @Test
    fun `notifySuccessSummary groups multiple pair completions into one summary`() =
        testScope.runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val nm = context.getSystemService(NotificationManager::class.java)
            shadowOf(context.applicationContext as Application)
                .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            val repo = buildRepository().also { it.setNotifyOnSuccess(true) }
            val notifier = buildNotifier(context, repo, this)
            val secondPair = fakePair.copy(id = 2L, displayName = "Work Files")

            notifier.notifySuccessSummary(fakePair, 3, 0)
            notifier.notifySuccessSummary(secondPair, 2, 0)
            runCurrent()
            advanceTimeBy(WorkerCompletionAggregator.DEFAULT_WINDOW_MS)
            advanceUntilIdle()

            val shadowNm = Shadows.shadowOf(nm)
            assertEquals(3, shadowNm.size())

            val summary = requireNotNull(shadowNm.getNotification(0))
            assertEquals(
                context.getString(R.string.sync_success_notification_summary, 2, 5),
                summary.extras.getString(Notification.EXTRA_TITLE),
            )

            val firstChild = requireNotNull(shadowNm.getNotification(fakePair.id.toInt()))
            assertEquals(fakePair.displayName, firstChild.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(
                context.getString(R.string.sync_success_notification_child_text, 3),
                firstChild.extras.getString(Notification.EXTRA_TEXT),
            )

            val secondChild = requireNotNull(shadowNm.getNotification(secondPair.id.toInt()))
            assertEquals(secondPair.displayName, secondChild.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(
                context.getString(R.string.sync_success_notification_child_text, 2),
                secondChild.extras.getString(Notification.EXTRA_TEXT),
            )
        }
}
