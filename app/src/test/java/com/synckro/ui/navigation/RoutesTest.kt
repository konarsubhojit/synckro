package com.synckro.ui.navigation

import com.synckro.domain.model.CloudProviderType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoutesTest {
    @Test
    fun `pickRemoteFolder omits accountId when null`() {
        assertEquals(
            "pick_remote_folder?provider=GOOGLE_DRIVE",
            Routes.pickRemoteFolder(CloudProviderType.GOOGLE_DRIVE, null),
        )
    }

    @Test
    fun `pickRemoteFolder omits accountId when blank`() {
        assertEquals(
            "pick_remote_folder?provider=GOOGLE_DRIVE",
            Routes.pickRemoteFolder(CloudProviderType.GOOGLE_DRIVE, " "),
        )
    }

    @Test
    fun `pickRemoteFolder appends encoded accountId when present`() {
        assertEquals(
            "pick_remote_folder?provider=GOOGLE_DRIVE&accountId=user%40example.com",
            Routes.pickRemoteFolder(CloudProviderType.GOOGLE_DRIVE, "user@example.com"),
        )
    }
}
