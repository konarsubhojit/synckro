package com.synckro.providers.onedrive

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import io.mockk.mockk

class OneDriveProviderFactoryTest {
    @Test
    fun `providerFor reuses same instance for same account id and isolates different accounts`() {
        val factory = OneDriveProviderFactory(authManager = mockk(), graphClient = mockk(relaxed = true))

        val a1 = factory.providerFor("acct-a")
        val a2 = factory.providerFor("acct-a")
        val b1 = factory.providerFor("acct-b")

        assertSame(a1, a2)
        assertNotSame(a1, b1)
    }
}
