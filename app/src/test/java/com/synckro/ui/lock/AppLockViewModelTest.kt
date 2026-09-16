package com.synckro.ui.lock

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.synckro.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [AppLockViewModel]. A real (temp-file) DataStore-backed
 * [SettingsRepository] is used so the opt-in preference is exercised
 * end-to-end, matching the approach taken by `SettingsViewModelTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockViewModelTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { tempFolder.newFile("app_lock_test.preferences_pb") },
            )
        repo = SettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `lock disabled by default keeps content visible and never prompts`() =
        runTest(testDispatcher) {
            val vm = AppLockViewModel(repo)

            vm.onAppForegrounded()

            val state = vm.state.value
            assertTrue(state.initialized)
            assertFalse(state.lockEnabled)
            assertFalse(state.promptRequested)
            assertTrue(state.contentVisible)
            assertFalse(state.lockScreenVisible)
        }

    @Test
    fun `enabled lock hides content and requests prompt on foreground`() =
        runTest(testDispatcher) {
            repo.setBiometricAppLockEnabled(true)
            val vm = AppLockViewModel(repo)

            vm.onAppForegrounded()

            val state = vm.state.value
            assertTrue(state.lockEnabled)
            assertTrue(state.promptRequested)
            assertFalse(state.contentVisible)
            assertTrue(state.lockScreenVisible)
        }

    @Test
    fun `successful authentication unlocks the app`() =
        runTest(testDispatcher) {
            repo.setBiometricAppLockEnabled(true)
            val vm = AppLockViewModel(repo)

            vm.onAppForegrounded()
            vm.onPromptShown()
            vm.onAuthSucceeded()

            val state = vm.state.value
            assertTrue(state.unlocked)
            assertFalse(state.promptRequested)
            assertNull(state.error)
            assertTrue(state.contentVisible)
            assertFalse(state.lockScreenVisible)
        }

    @Test
    fun `cancelled authentication keeps the app locked`() =
        runTest(testDispatcher) {
            repo.setBiometricAppLockEnabled(true)
            val vm = AppLockViewModel(repo)

            vm.onAppForegrounded()
            vm.onPromptShown()
            vm.onAuthFailed(AppLockError.Cancelled)

            val state = vm.state.value
            assertFalse(state.unlocked)
            assertFalse(state.promptRequested)
            assertEquals(AppLockError.Cancelled, state.error)
            assertFalse(state.contentVisible)
            assertTrue(state.lockScreenVisible)
        }

    @Test
    fun `retry after failure requests the prompt again and clears the error`() =
        runTest(testDispatcher) {
            repo.setBiometricAppLockEnabled(true)
            val vm = AppLockViewModel(repo)

            vm.onAppForegrounded()
            vm.onPromptShown()
            vm.onAuthFailed(AppLockError.Failed)
            vm.onRetryRequested()

            val state = vm.state.value
            assertTrue(state.promptRequested)
            assertNull(state.error)
            assertTrue(state.lockScreenVisible)
        }

    @Test
    fun `backgrounding re-locks an unlocked app`() =
        runTest(testDispatcher) {
            repo.setBiometricAppLockEnabled(true)
            val vm = AppLockViewModel(repo)

            vm.onAppForegrounded()
            vm.onPromptShown()
            vm.onAuthSucceeded()
            vm.onAppBackgrounded()

            assertFalse(vm.state.value.unlocked)
            assertTrue(vm.state.value.lockScreenVisible)

            vm.onAppForegrounded()
            assertTrue(vm.state.value.promptRequested)
        }

    @Test
    fun `backgrounding does not lock when the setting is off`() =
        runTest(testDispatcher) {
            val vm = AppLockViewModel(repo)

            vm.onAppBackgrounded()
            vm.onAppForegrounded()

            assertTrue(vm.state.value.contentVisible)
            assertFalse(vm.state.value.promptRequested)
        }

    @Test
    fun `enabling the lock while running does not eject the user`() =
        runTest(testDispatcher) {
            val vm = AppLockViewModel(repo)
            vm.onAppForegrounded()

            repo.setBiometricAppLockEnabled(true)

            assertTrue(vm.state.value.lockEnabled)
            assertTrue(vm.state.value.contentVisible)

            // ... but the next foreground transition locks the app.
            vm.onAppBackgrounded()
            vm.onAppForegrounded()
            assertTrue(vm.state.value.lockScreenVisible)
            assertTrue(vm.state.value.promptRequested)
        }

    @Test
    fun `prompt is requested when the preference loads after the app is already visible`() =
        runTest(testDispatcher) {
            val preferences = MutableSharedFlow<Preferences>(replay = 1)
            val slowRepo = SettingsRepository(FakeDataStore(preferences))
            val vm = AppLockViewModel(slowRepo)

            // Foreground event arrives before DataStore has emitted anything.
            vm.onAppForegrounded()
            assertFalse(vm.state.value.initialized)
            assertFalse(vm.state.value.promptRequested)

            preferences.emit(
                mutablePreferencesOf(SettingsRepository.KEY_BIOMETRIC_APP_LOCK_ENABLED to true),
            )

            val state = vm.state.value
            assertTrue(state.initialized)
            assertTrue(state.lockScreenVisible)
            assertTrue(state.promptRequested)
        }

    @Test
    fun `disableLock turns the preference off and reveals content`() =
        runTest(testDispatcher) {
            repo.setBiometricAppLockEnabled(true)
            val vm = AppLockViewModel(repo)
            vm.onAppForegrounded()
            vm.onAuthFailed(AppLockError.Unavailable)

            vm.disableLock()

            assertFalse(repo.biometricAppLockEnabled.first())
            assertFalse(vm.state.value.lockEnabled)
            assertTrue(vm.state.value.contentVisible)
            assertNull(vm.state.value.error)
        }

    private class FakeDataStore(
        override val data: Flow<Preferences>,
    ) : DataStore<Preferences> {
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = transform(emptyPreferences())
    }
}
