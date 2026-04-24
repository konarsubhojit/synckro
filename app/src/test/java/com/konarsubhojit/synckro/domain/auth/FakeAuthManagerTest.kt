package com.konarsubhojit.synckro.domain.auth

import android.app.Activity
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeAuthManagerTest {

    private val activity: Activity = mockk(relaxed = true)

    @Test
    fun `signIn returns NotConfigured when not configured`() = runTest {
        val mgr = FakeAuthManager(CloudProviderType.ONEDRIVE, configured = false)
        val result = mgr.signIn(activity)
        assertTrue(result is AuthResult.NotConfigured)
    }

    @Test
    fun `signIn creates account when configured`() = runTest {
        val mgr = FakeAuthManager(CloudProviderType.GOOGLE_DRIVE)
        val result = mgr.signIn(activity)
        assertTrue(result is AuthResult.Success)
        val listed = mgr.currentAccounts()
        assertEquals(1, listed.size)
        assertEquals(CloudProviderType.GOOGLE_DRIVE, listed.single().provider)
    }

    @Test
    fun `nextSignInResult override is returned only once`() = runTest {
        val mgr = FakeAuthManager(CloudProviderType.ONEDRIVE)
        mgr.nextSignInResult = AuthResult.Cancelled
        val first = mgr.signIn(activity)
        val second = mgr.signIn(activity)
        assertTrue(first is AuthResult.Cancelled)
        assertTrue(second is AuthResult.Success)
    }

    @Test
    fun `acquireAccessToken needs interactive sign in for unknown account`() = runTest {
        val mgr = FakeAuthManager(CloudProviderType.ONEDRIVE)
        val unknown = Account("missing", CloudProviderType.ONEDRIVE, "x", null)
        val result = mgr.acquireAccessToken(unknown)
        assertTrue(result is AuthResult.NeedsInteractiveSignIn)
    }

    @Test
    fun `signOut removes the account`() = runTest {
        val mgr = FakeAuthManager(CloudProviderType.GOOGLE_DRIVE)
        val acct = (mgr.signIn(activity) as AuthResult.Success).value
        val out = mgr.signOut(acct)
        assertTrue(out is AuthResult.Success)
        assertTrue(mgr.currentAccounts().isEmpty())
    }
}
