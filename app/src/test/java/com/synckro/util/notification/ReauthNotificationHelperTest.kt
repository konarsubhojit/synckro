package com.synckro.util.notification

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.math.absoluteValue

/**
 * Unit tests for [ReauthNotificationHelper].
 *
 * Uses Robolectric so that [NotificationManager] and [Context] behave like on a real device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReauthNotificationHelperTest {

    private lateinit var context: Context
    private lateinit var nm: NotificationManager

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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        nm = context.getSystemService(NotificationManager::class.java)
        // Grant POST_NOTIFICATIONS permission for API 33 tests.
        Shadows
            .shadowOf(context.applicationContext as Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    // ── notificationIdForAccount ──────────────────────────────────────────────

    @Test
    fun `notificationIdForAccount returns value in expected range`() {
        val id = ReauthNotificationHelper.notificationIdForAccount("any-account")
        // Range: [100_000, 104_095]
        assert(id >= 100_000) { "id=$id is below lower bound 100_000" }
        assert(id < 104_096) { "id=$id is above upper bound 104_095" }
    }

    @Test
    fun `notificationIdForAccount is deterministic for same input`() {
        val id1 = ReauthNotificationHelper.notificationIdForAccount("account-abc")
        val id2 = ReauthNotificationHelper.notificationIdForAccount("account-abc")
        assertEquals("Same accountId must always produce the same notification ID", id1, id2)
    }

    @Test
    fun `notificationIdForAccount differs for different account IDs`() {
        val id1 = ReauthNotificationHelper.notificationIdForAccount("account-1")
        val id2 = ReauthNotificationHelper.notificationIdForAccount("account-2")
        // Not strictly guaranteed (hash collisions possible) but highly likely for
        // simple sequential IDs, confirming the function is actually hashing.
        assert(id1 != id2) { "Different accountIds should produce different notification IDs" }
    }

    @Test
    fun `notificationIdForAccount matches manual hash computation`() {
        val accountId = "some-id"
        val expected = 100_000 + (accountId.hashCode().absoluteValue % 4_096)
        val actual = ReauthNotificationHelper.notificationIdForAccount(accountId)
        assertEquals(expected, actual)
    }

    // ── postReauthNotification ────────────────────────────────────────────────

    @Test
    fun `postReauthNotification posts notification for pair`() {
        ReauthNotificationHelper.postReauthNotification(context, fakePair)

        val shadowNm = Shadows.shadowOf(nm)
        assert(shadowNm.size() > 0) { "Expected at least one active notification after posting" }
    }

    @Test
    fun `postReauthNotification posts notification with expected ID`() {
        ReauthNotificationHelper.postReauthNotification(context, fakePair)

        val expectedId = ReauthNotificationHelper.notificationIdForAccount(fakePair.accountId!!)
        val notification = Shadows.shadowOf(nm).getNotification(expectedId)
        assertNotNull("Expected notification with id=$expectedId to be posted", notification)
    }

    @Test
    fun `postReauthNotification uses provider name as fallback when accountId is null`() {
        val pairNoAccount = fakePair.copy(accountId = null)
        // Should not throw and should post at least the individual + summary notifications.
        ReauthNotificationHelper.postReauthNotification(context, pairNoAccount)

        val shadowNm = Shadows.shadowOf(nm)
        assert(shadowNm.size() > 0) { "Expected notifications even without accountId" }
    }

    @Test
    fun `postReauthNotification also posts group summary`() {
        ReauthNotificationHelper.postReauthNotification(context, fakePair)

        val summaryId = 100_000 - 1 // REAUTH_GROUP_SUMMARY_ID
        val summaryNotification = Shadows.shadowOf(nm).getNotification(summaryId)
        assertNotNull("Expected group summary notification with id=$summaryId", summaryNotification)
    }

    // ── cancelReauthNotification ──────────────────────────────────────────────

    @Test
    fun `cancelReauthNotification removes the notification for the given account`() {
        ReauthNotificationHelper.postReauthNotification(context, fakePair)
        val expectedId = ReauthNotificationHelper.notificationIdForAccount(fakePair.accountId!!)

        ReauthNotificationHelper.cancelReauthNotification(context, fakePair.accountId!!)

        val notification = Shadows.shadowOf(nm).getNotification(expectedId)
        assertNull("Notification with id=$expectedId should have been cancelled", notification)
    }

    @Test
    fun `cancelReauthNotification removes group summary when no individual notifications remain`() {
        ReauthNotificationHelper.postReauthNotification(context, fakePair)
        val summaryId = 100_000 - 1

        ReauthNotificationHelper.cancelReauthNotification(context, fakePair.accountId!!)

        val summaryNotification = Shadows.shadowOf(nm).getNotification(summaryId)
        assertNull("Group summary should be cancelled when the last individual notification is removed", summaryNotification)
    }

    @Test
    fun `cancelReauthNotification keeps group summary when other accounts still need reauth`() {
        val pair2 =
            fakePair.copy(
                id = 2L,
                displayName = "Work Files",
                accountId = "second-account-id",
            )
        ReauthNotificationHelper.postReauthNotification(context, fakePair)
        ReauthNotificationHelper.postReauthNotification(context, pair2)

        // Cancel only the first account.
        ReauthNotificationHelper.cancelReauthNotification(context, fakePair.accountId!!)

        val summaryId = 100_000 - 1
        val summaryNotification = Shadows.shadowOf(nm).getNotification(summaryId)
        assertNotNull("Group summary should remain while second account's notification is still active", summaryNotification)
    }

    @Test
    fun `cancelReauthNotification is safe when no notification exists`() {
        // Should not throw even if nothing was ever posted.
        ReauthNotificationHelper.cancelReauthNotification(context, "non-existent-account")
    }
}

