package com.synckro.ui.screens.onboarding

import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.repository.AccountRepository
import com.synckro.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/**
 * Gateway that decides whether the multi-step onboarding pager needs to be
 * shown.
 *
 * Onboarding is considered **already complete** (and should be skipped) when
 * any one of the following is true:
 * - The user previously tapped **Skip** or the final CTA and
 *   [SettingsRepository.onboardingCompletedAtMs] is non-null.
 * - At least one account already exists (e.g. after a backup restore).
 * - At least one sync pair already exists (e.g. after a backup restore).
 *
 * This class is intentionally free of any UI / Android-framework dependencies
 * so it can be exercised in plain JVM unit tests.
 */
class OnboardingGateway
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val accountRepository: AccountRepository,
        private val syncPairDao: SyncPairDao,
    ) {
        /**
         * Returns a [Flow] that emits `true` while onboarding is still required
         * and `false` once it has been completed (explicitly or implicitly).
         */
        fun isRequired(): Flow<Boolean> =
            combine(
                settingsRepository.onboardingCompletedAtMs,
                accountRepository.observeAll(),
                syncPairDao.observeAll(),
            ) { completedAtMs, accounts, pairs ->
                completedAtMs == null && accounts.isEmpty() && pairs.isEmpty()
            }

        /**
         * Marks onboarding as explicitly completed at the current system time.
         * Subsequent calls to [isRequired] will emit `false`.
         */
        suspend fun complete() {
            settingsRepository.setOnboardingCompletedAt(System.currentTimeMillis())
        }
    }
