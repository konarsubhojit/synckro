package com.synckro.providers.onedrive

import android.content.Context
import com.synckro.R
import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.model.SyncEventLevel
import com.synckro.domain.model.SyncEventTag
import com.synckro.util.error.UserMessageReporter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot startup probe that checks whether the existing MSAL cache can still
 * be read after switching `msal_config.json` to `account_mode = MULTIPLE`.
 *
 * If MSAL throws while enumerating cached accounts, we log a structured Auth
 * event and prompt the user to sign in again from the Accounts screen.
 */
@Singleton
class OneDriveMultiAccountStartupProbe
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val checker: OneDriveCacheCompatibilityChecker,
        private val syncEventRepository: SyncEventRepository,
        private val userMessages: UserMessageReporter,
    ) {
        suspend fun runIfNeeded() {
            if (prefs.getBoolean(KEY_PROBE_COMPLETE, false)) return
            prefs.edit().putBoolean(KEY_PROBE_COMPLETE, true).apply()

            val error =
                runCatching { checker.probeMultiAccountCacheRead() }
                    .getOrElse { Result.failure(it) }
                    .exceptionOrNull()
                    ?: return

            syncEventRepository.log(
                pairId = null,
                level = SyncEventLevel.ERROR,
                tag = SyncEventTag.AUTH,
                message =
                    "OneDrive MSAL cache compatibility probe failed after multi-account migration: " +
                        (error.message ?: error.javaClass.simpleName),
            )
            userMessages.reportError(
                context.getString(R.string.onedrive_multi_account_probe_failed),
                error,
            )
        }

        private val prefs by lazy {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        private companion object {
            private const val PREFS_NAME = "startup_probes"
            private const val KEY_PROBE_COMPLETE = "onedrive_multi_account_probe_complete"
        }
    }
