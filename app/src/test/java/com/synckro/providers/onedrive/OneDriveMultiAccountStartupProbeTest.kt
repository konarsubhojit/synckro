package com.synckro.providers.onedrive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.synckro.R
import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTag
import com.synckro.util.error.UserMessageReporter
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OneDriveMultiAccountStartupProbeTest {
    private lateinit var context: Context
    private lateinit var syncEventRepository: SyncEventRepository
    private lateinit var userMessages: UserMessageReporter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("startup_probes", Context.MODE_PRIVATE).edit().clear().commit()
        syncEventRepository = mockk(relaxed = true)
        userMessages = mockk(relaxed = true)
    }

    @Test
    fun `runIfNeeded does nothing after the one-shot flag is set`() =
        runTest {
            context.getSharedPreferences("startup_probes", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("onedrive_multi_account_probe_complete", true)
                .commit()
            val checker = FakeChecker(Result.failure(IllegalStateException("boom")))

            createProbe(checker).runIfNeeded()

            assertEquals(0, checker.calls)
            coVerify(exactly = 0) { syncEventRepository.log(any(), any(), any(), any()) }
            verify(exactly = 0) { userMessages.reportError(any(), any(), any(), any()) }
        }

    @Test
    fun `runIfNeeded marks probe complete without logging when cache read succeeds`() =
        runTest {
            context.getSharedPreferences("startup_probes", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("onedrive_multi_account_probe_complete", false)
                .commit()
            val checker = FakeChecker(Result.success(Unit))

            createProbe(checker).runIfNeeded()

            assertEquals(1, checker.calls)
            coVerify(exactly = 0) { syncEventRepository.log(any(), any(), any(), any()) }
            verify(exactly = 0) { userMessages.reportError(any(), any(), any(), any()) }
        }

    @Test
    fun `runIfNeeded logs structured auth error and user message when MSAL throws`() =
        runTest {
            context.getSharedPreferences("startup_probes", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("onedrive_multi_account_probe_complete", false)
                .commit()
            val error = IllegalStateException("Cache schema mismatch")
            val checker = FakeChecker(Result.failure(error))

            createProbe(checker).runIfNeeded()

            coVerify {
                syncEventRepository.log(
                    null,
                    SyncEventLevel.ERROR,
                    SyncEventTag.AUTH,
                    match {
                        it.contains("OneDrive MSAL cache compatibility probe failed") &&
                            it.contains("Cache schema mismatch")
                    },
                )
            }
            verify {
                userMessages.reportError(
                    context.getString(R.string.onedrive_multi_account_probe_failed),
                    error,
                    null,
                    null,
                )
            }
        }

    private fun createProbe(checker: OneDriveCacheCompatibilityChecker) =
        OneDriveMultiAccountStartupProbe(
            context = context,
            checker = checker,
            syncEventRepository = syncEventRepository,
            userMessages = userMessages,
        )

    private class FakeChecker(
        private val result: Result<Unit>,
    ) : OneDriveCacheCompatibilityChecker {
        var calls: Int = 0
            private set

        override suspend fun probeMultiAccountCacheRead(): Result<Unit> {
            calls += 1
            return result
        }
    }
}
