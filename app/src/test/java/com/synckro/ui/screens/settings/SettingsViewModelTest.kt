package com.synckro.ui.screens.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.synckro.data.repository.AppLanguagePreference
import com.synckro.data.repository.AutoSyncSchedule
import com.synckro.data.repository.DarkModePreference
import com.synckro.data.repository.InternetConnectionScope
import com.synckro.data.repository.SettingsRepository
import com.synckro.data.repository.SyncPairRepository
import com.synckro.data.worker.SyncScheduler
import com.synckro.domain.model.ConflictPolicy
import com.synckro.util.logging.LogExportConfig
import com.synckro.util.logging.LogExporter
import com.synckro.util.logging.LogVisibilityConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [SettingsViewModel]. Drives the view-model against a real
 * (temp-file) DataStore-backed [SettingsRepository] so each toggle's
 * persistence is exercised end-to-end — mocks would not catch a key-typo or
 * an enum-name mismatch.
 *
 * Side-effect collaborators (sync rescheduling, log export) are mocked because
 * their own behaviour is unit-tested elsewhere; this file only verifies that
 * the view-model wires the right preferences and methods together.
 *
 * An [UnconfinedTestDispatcher] is shared by [Dispatchers.setMain] and the
 * DataStore actor's scope so writes launched on `viewModelScope` complete
 * eagerly without needing to manually coordinate two schedulers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var repo: SettingsRepository
    private lateinit var syncPairRepository: SyncPairRepository
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var logExporter: LogExporter
    private lateinit var ctx: Context
    private lateinit var filesDir: File
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        filesDir = tempFolder.newFolder("files")
        cacheDir = tempFolder.newFolder("cache")
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { tempFolder.newFile("settings_test.preferences_pb") },
            )
        repo = SettingsRepository(dataStore)
        syncPairRepository =
            mockk(relaxed = true) {
                coEvery { observeAll(any()) } returns flowOf(emptyList())
            }
        syncScheduler = mockk(relaxed = true)
        logExporter = mockk(relaxed = true)
        ctx = mockk(relaxed = true)
        every { ctx.filesDir } returns filesDir
        every { ctx.cacheDir } returns cacheDir
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun newVm(): SettingsViewModel =
        SettingsViewModel(
            context = ctx,
            settingsRepository = repo,
            syncPairRepository = syncPairRepository,
            syncScheduler = syncScheduler,
            logExporter = logExporter,
        )

    @Test
    fun `state mirrors repository defaults`() =
        testScope.runTest {
            val vm = newVm()
            // Subscribe to state so stateIn(WhileSubscribed) starts collecting upstream.
            val s = vm.state.value
            // After subscribing, the initial value should still match defaults because
            // each upstream flow's first emission == the repository default.
            // (We re-read after touching the flow so the value reflects the latest combine.)
            vm.state.value.let {
                assertTrue(it.globalAutoSyncEnabled)
                assertTrue(it.defaultWifiOnly)
                assertFalse(it.defaultChargingOnly)
                assertEquals(ConflictPolicy.NEWEST_WINS, it.defaultConflictPolicy)
                assertEquals(3, it.maxConcurrentTransfers)
                assertTrue(it.warnOnMobileNetworkSync)
                assertTrue(it.retryAutomaticallyAfterError)
                assertEquals(15, it.retryWaitMinutes)
                assertEquals(3, it.retryMaxAttempts)
                assertEquals(3, it.parallelUploads)
                assertEquals(3, it.parallelDownloads)
                assertEquals(AutoSyncSchedule.EVERY_30_MINUTES, it.autoSyncSchedule)
                assertFalse(it.autoSyncChargingOnly)
                assertEquals(20, it.autoSyncBatteryThresholdPercent)
                assertEquals(InternetConnectionScope.WIFI_AND_MOBILE, it.internetConnectionScope)
                assertFalse(it.syncOnMeteredWifi)
                assertTrue(it.allowedWifiNetworks.isEmpty())
                assertTrue(it.disallowedWifiNetworks.isEmpty())
                assertFalse(it.syncOnMobileRoaming)
                assertFalse(it.syncOnSlow2g)
                assertEquals(DarkModePreference.SYSTEM, it.darkMode)
                assertEquals(AppLanguagePreference.SYSTEM, it.appLanguage)
                assertFalse(it.dynamicColor)
                assertTrue(it.respectFontScale)
                assertFalse(it.notifyOnSuccess)
                assertTrue(it.notifyOnFailure)
                assertTrue(it.enableHaptics)
                assertFalse(it.pinProtectionEnabled)
                assertEquals(2, it.pinTimeoutMinutes)
                assertFalse(it.unlockWithBiometrics)
                assertFalse(it.protectSettingsOnly)
                assertEquals(30, it.logRetentionDays)
            }
            // Reference s so the linter doesn't complain.
            @Suppress("UNUSED_EXPRESSION")
            s
        }

    @Test
    fun `setGlobalAutoSync persists value`() =
        testScope.runTest {
            val vm = newVm()
            vm.setGlobalAutoSync(false)
            assertFalse(repo.globalAutoSyncEnabled.first())
        }

    @Test
    fun `setDefaultWifiOnly persists value`() =
        testScope.runTest {
            val vm = newVm()
            vm.setDefaultWifiOnly(false)
            assertFalse(repo.defaultWifiOnly.first())
        }

    @Test
    fun `setDefaultChargingOnly persists value`() =
        testScope.runTest {
            val vm = newVm()
            vm.setDefaultChargingOnly(true)
            assertTrue(repo.defaultChargingOnly.first())
        }

    @Test
    fun `setDefaultConflictPolicy persists enum`() =
        testScope.runTest {
            val vm = newVm()
            vm.setDefaultConflictPolicy(ConflictPolicy.KEEP_BOTH)
            assertEquals(ConflictPolicy.KEEP_BOTH, repo.defaultConflictPolicy.first())
        }

    @Test
    fun `setMaxConcurrentTransfers persists value`() =
        testScope.runTest {
            val vm = newVm()
            vm.setMaxConcurrentTransfers(3)
            assertEquals(3, repo.maxConcurrentTransfers.first())
        }

    @Test
    fun `setDarkMode persists enum`() =
        testScope.runTest {
            val vm = newVm()
            vm.setDarkMode(DarkModePreference.DARK)
            assertEquals(DarkModePreference.DARK, repo.darkMode.first())
        }

    @Test
    fun `setAppLanguage persists enum`() =
        testScope.runTest {
            val vm = newVm()
            vm.setAppLanguage(AppLanguagePreference.ENGLISH)
            assertEquals(AppLanguagePreference.ENGLISH, repo.appLanguage.first())
        }

    @Test
    fun `sync redesign settings persist values`() =
        testScope.runTest {
            val vm = newVm()
            vm.setWarnOnMobileNetworkSync(false)
            vm.setRetryAutomaticallyAfterError(false)
            vm.setRetryWaitMinutes(30)
            vm.setRetryMaxAttempts(5)
            vm.setParallelUploads(4)
            vm.setParallelDownloads(2)
            vm.setAutoSyncSchedule(AutoSyncSchedule.HOURLY)
            vm.setAutoSyncChargingOnly(true)
            vm.setAutoSyncBatteryThresholdPercent(35)
            vm.setInternetConnectionScope(InternetConnectionScope.WIFI_ONLY)
            vm.setSyncOnMeteredWifi(true)
            vm.setAllowedWifiNetworks(setOf("Office", "Home"))
            vm.setDisallowedWifiNetworks(setOf("Airport"))
            vm.setSyncOnMobileRoaming(true)
            vm.setSyncOnSlow2g(true)

            assertFalse(repo.warnOnMobileNetworkSync.first())
            assertFalse(repo.retryAutomaticallyAfterError.first())
            assertEquals(30, repo.retryWaitMinutes.first())
            assertEquals(5, repo.retryMaxAttempts.first())
            assertEquals(4, repo.parallelUploads.first())
            assertEquals(2, repo.parallelDownloads.first())
            assertEquals(AutoSyncSchedule.HOURLY, repo.autoSyncSchedule.first())
            assertTrue(repo.autoSyncChargingOnly.first())
            assertEquals(35, repo.autoSyncBatteryThresholdPercent.first())
            assertEquals(InternetConnectionScope.WIFI_ONLY, repo.internetConnectionScope.first())
            assertTrue(repo.syncOnMeteredWifi.first())
            assertEquals(setOf("Office", "Home"), repo.allowedWifiNetworks.first())
            assertEquals(setOf("Airport"), repo.disallowedWifiNetworks.first())
            assertTrue(repo.syncOnMobileRoaming.first())
            assertTrue(repo.syncOnSlow2g.first())
        }

    @Test
    fun `setDynamicColor persists value`() =
        testScope.runTest {
            val vm = newVm()
            vm.setDynamicColor(true)
            assertTrue(repo.dynamicColor.first())
        }

    @Test
    fun `setRespectFontScale persists value`() =
        testScope.runTest {
            val vm = newVm()
            vm.setRespectFontScale(false)
            assertFalse(repo.respectFontScale.first())
        }

    @Test
    fun `setNotifyOnSuccess persists value`() =
        testScope.runTest {
            val vm = newVm()
            vm.setNotifyOnSuccess(true)
            assertTrue(repo.notifyOnSuccess.first())
        }

    @Test
    fun `setNotifyOnFailure persists value`() =
        testScope.runTest {
            val vm = newVm()
            vm.setNotifyOnFailure(false)
            assertFalse(repo.notifyOnFailure.first())
        }

    @Test
    fun `setEnableHaptics persists value`() =
        testScope.runTest {
            val vm = newVm()
            vm.setEnableHaptics(false)
            assertFalse(repo.enableHaptics.first())
        }

    @Test
    fun `security settings persist values`() =
        testScope.runTest {
            val vm = newVm()
            vm.setPinProtectionEnabled(true)
            vm.setPinTimeoutMinutes(5)
            vm.setUnlockWithBiometrics(true)
            vm.setProtectSettingsOnly(true)

            assertTrue(repo.pinProtectionEnabled.first())
            assertEquals(5, repo.pinTimeoutMinutes.first())
            assertTrue(repo.unlockWithBiometrics.first())
            assertTrue(repo.protectSettingsOnly.first())
        }

    @Test
    fun `setLogRetentionDays persists value`() =
        testScope.runTest {
            val vm = newVm()
            vm.setLogRetentionDays(90)
            assertEquals(90, repo.logRetentionDays.first())
        }

    @Test
    fun `resolveFeedbackEmail returns configured address when non-blank`() {
        val resolved = SettingsViewModel.resolveFeedbackEmail(" support@synckro.dev ")
        assertEquals("support@synckro.dev", resolved.address)
        assertTrue(resolved.isConfigured)
    }

    @Test
    fun `resolveFeedbackEmail falls back to placeholder when blank`() {
        val resolved = SettingsViewModel.resolveFeedbackEmail("   ")
        assertEquals("feedback@example.com", resolved.address)
        assertFalse(resolved.isConfigured)
    }

    @Test
    fun `resolveFeedbackEmail falls back to placeholder when invalid`() {
        val resolved = SettingsViewModel.resolveFeedbackEmail("not-an-email")
        assertEquals("feedback@example.com", resolved.address)
        assertFalse(resolved.isConfigured)
    }

    @Test
    fun `sendFeedback exports using active redaction config`() =
        testScope.runTest {
            val vm = newVm()
            val exportUri = mockk<android.net.Uri>(relaxed = true)
            val redaction = LogExportConfig(redactPaths = true, redactAccountIds = true)
            val originalExportConfig = LogVisibilityConfig.currentExportConfig()
            try {
                LogVisibilityConfig.setExportConfig(redaction)
                coEvery { logExporter.export(redaction) } returns exportUri

                val eventAwait = async { vm.events.first() }
                vm.sendFeedback()

                val event = eventAwait.await()
                assertEquals(SettingsViewModel.UiEvent.ComposeFeedback(exportUri), event)
                coVerify(exactly = 1) { logExporter.export(redaction) }
            } finally {
                LogVisibilityConfig.setExportConfig(originalExportConfig)
            }
        }

    @Test
    fun `exportLogs emits share event on success`() =
        testScope.runTest {
            val vm = newVm()
            val exportUri = mockk<android.net.Uri>(relaxed = true)
            coEvery { logExporter.export() } returns exportUri

            val eventAwait = async { vm.events.first() }
            vm.exportLogs()

            assertEquals(SettingsViewModel.UiEvent.ShareLogs(exportUri), eventAwait.await())
            coVerify(exactly = 1) { logExporter.export() }
        }

    @Test
    fun `clearCache deletes cache contents and emits freed size`() =
        testScope.runTest {
            val vm = newVm()
            File(cacheDir, "a.tmp").writeText("abc")
            val nestedDir = File(cacheDir, "nested").apply { mkdirs() }
            File(nestedDir, "b.tmp").writeText("defg")

            val eventAwait = async { vm.events.first() }
            vm.clearCache()

            assertEquals(SettingsViewModel.UiEvent.CacheCleared(7), eventAwait.await())
            assertTrue(cacheDir.listFiles().isNullOrEmpty())
        }

    @Test
    fun `backupSettings creates backup and emits success event`() =
        testScope.runTest {
            val vm = newVm()
            val settingsFile = File(filesDir, "datastore/settings.preferences_pb")
            settingsFile.parentFile?.mkdirs()
            settingsFile.writeText("theme=dark")

            val eventAwait = async { vm.events.first() }
            vm.backupSettings()

            assertEquals(SettingsViewModel.UiEvent.SettingsBackedUp, eventAwait.await())
            val backupFile = File(filesDir, SettingsViewModel.SETTINGS_BACKUP_FILENAME)
            assertTrue(backupFile.exists())
            assertEquals("theme=dark", backupFile.readText())
        }

    @Test
    fun `backupSettings emits no-settings event when datastore file does not exist`() =
        testScope.runTest {
            val vm = newVm()

            val eventAwait = async { vm.events.first() }
            vm.backupSettings()

            assertEquals(SettingsViewModel.UiEvent.SettingsBackupNoSettings, eventAwait.await())
        }

    @Test
    fun `restoreSettings restores last backup and emits success event`() =
        testScope.runTest {
            val vm = newVm()
            val settingsFile = File(filesDir, "datastore/settings.preferences_pb")
            settingsFile.parentFile?.mkdirs()
            settingsFile.writeText("theme=light")

            val backupFile = File(filesDir, SettingsViewModel.SETTINGS_BACKUP_FILENAME)
            backupFile.writeText("theme=dark")

            val eventAwait = async { vm.events.first() }
            vm.restoreSettings()

            assertEquals(SettingsViewModel.UiEvent.SettingsRestored, eventAwait.await())
            assertEquals("theme=dark", settingsFile.readText())
        }

    @Test
    fun `restoreSettings emits no-backup event when backup does not exist`() =
        testScope.runTest {
            val vm = newVm()

            val eventAwait = async { vm.events.first() }
            vm.restoreSettings()

            assertEquals(SettingsViewModel.UiEvent.SettingsRestoreNoBackup, eventAwait.await())
        }

    @Test
    fun `resetHints clears seen tooltips`() =
        testScope.runTest {
            val vm = newVm()
            repo.markTooltipSeen("pairs_fab")
            vm.resetHints()
            assertTrue(repo.seenTooltips.first().isEmpty())
        }
}
