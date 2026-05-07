package com.synckro.data.worker

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Tests for [SyncWorker.canPostNotifications] on API < 33 (pre-TIRAMISU).
 *
 * On these API levels POST_NOTIFICATIONS is not a runtime permission, so the helper
 * must always return `true` and never suppress notification updates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class SyncWorkerNotificationGuardBelowApi33Test {
    @Test
    fun `canPostNotifications returns true below API 33 regardless of grant state`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // Deny the permission name at the shadow level – this must have no effect before API 33.
        Shadows
            .shadowOf(context.applicationContext as Application)
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(
            "Should always return true on API < 33 (POST_NOTIFICATIONS is not a runtime permission)",
            SyncWorker.canPostNotifications(context),
        )
    }
}

/**
 * Tests for [SyncWorker.canPostNotifications] on API 33 (TIRAMISU and above).
 *
 * POST_NOTIFICATIONS becomes a runtime permission on these API levels, so the helper
 * must reflect the actual grant state and return `false` when the permission is denied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncWorkerNotificationGuardApi33Test {
    @Test
    fun `canPostNotifications returns true when POST_NOTIFICATIONS is granted`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        Shadows
            .shadowOf(context.applicationContext as Application)
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(
            "Should return true when POST_NOTIFICATIONS is granted on API 33+",
            SyncWorker.canPostNotifications(context),
        )
    }

    @Test
    fun `canPostNotifications returns false when POST_NOTIFICATIONS is denied`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        Shadows
            .shadowOf(context.applicationContext as Application)
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(
            "Should return false when POST_NOTIFICATIONS is not granted on API 33+",
            SyncWorker.canPostNotifications(context),
        )
    }
}
