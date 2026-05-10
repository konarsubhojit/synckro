package com.synckro.providers.gdrive

import io.mockk.mockk
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class GoogleDriveProviderFactoryTest {
    @Test
    fun `providerFor reuses same instance for same account id and isolates different accounts`() {
        val factory = GoogleDriveProviderFactory(authManager = mockk(), restClient = mockk(relaxed = true))

        val a1 = factory.providerFor("acct-a")
        val a2 = factory.providerFor("acct-a")
        val b1 = factory.providerFor("acct-b")

        assertSame(a1, a2)
        assertNotSame(a1, b1)
    }
}
