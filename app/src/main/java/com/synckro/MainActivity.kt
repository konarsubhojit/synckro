package com.synckro

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.synckro.data.repository.DarkModePreference
import com.synckro.data.repository.SettingsRepository
import com.synckro.ui.navigation.SynckroNavHost
import com.synckro.ui.theme.SynckroTheme
import com.synckro.util.error.LocalUserMessageReporter
import com.synckro.util.error.UserMessage
import com.synckro.util.error.UserMessageReporter
import com.synckro.util.navigation.AppNavEvent
import com.synckro.util.navigation.AppNavigationDispatcher
import com.synckro.util.notification.ReauthNotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var userMessages: UserMessageReporter
    @Inject lateinit var appNavigationDispatcher: AppNavigationDispatcher
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Forward any deep-link navigation action present at launch time.
        dispatchNavigationIntent(intent)
        setContent {
            // Observe appearance preferences so theme changes apply immediately
            // without requiring an app restart. The initial values match the
            // SettingsRepository defaults so the first composition before the
            // DataStore flow has emitted does not flash a different theme.
            val darkMode by settingsRepository.darkMode.collectAsState(initial = DarkModePreference.SYSTEM)
            val dynamicColor by settingsRepository.dynamicColor.collectAsState(initial = false)
            val respectFontScale by settingsRepository.respectFontScale.collectAsState(initial = true)

            SynckroTheme(
                darkMode = darkMode,
                dynamicColor = dynamicColor,
                respectFontScale = respectFontScale,
            ) {
                val snackbarHostState = remember { SnackbarHostState() }

                // Route every reported UserMessage to the snackbar host. We
                // use `collectLatest` so that a burst of errors doesn't leave
                // the user stuck dismissing outdated messages — the newest
                // one always wins.
                LaunchedEffect(snackbarHostState) {
                    userMessages.messages.collectLatest { message ->
                        val result =
                            snackbarHostState.showSnackbar(
                                message = message.text,
                                actionLabel = message.actionLabel,
                                withDismissAction = message.actionLabel == null,
                                duration =
                                    when (message.severity) {
                                        UserMessage.Severity.ERROR -> SnackbarDuration.Long
                                        else -> SnackbarDuration.Short
                                    },
                            )
                        if (result == SnackbarResult.ActionPerformed) {
                            message.onAction?.invoke()
                        }
                    }
                }

                CompositionLocalProvider(LocalUserMessageReporter provides userMessages) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        snackbarHost = {
                            SnackbarHost(
                                hostState = snackbarHostState,
                                modifier = Modifier.semantics {
                                    liveRegion = LiveRegionMode.Polite
                                },
                            ) { data ->
                                // Using the default snackbar with Material3 theming;
                                // colors are driven by the explicit dark/light scheme.
                                Snackbar(snackbarData = data)
                            }
                        },
                    ) { padding ->
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(padding),
                        ) {
                            SynckroNavHost(
                                activity = this@MainActivity,
                                appNavigationDispatcher = appNavigationDispatcher,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Called when the Activity is already running and receives a new intent (e.g. the user taps a
     * notification while the app is in the foreground or in the back-stack).
     * Forwards any navigation action to [appNavigationDispatcher] so the Compose NavHost can react.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dispatchNavigationIntent(intent)
    }

    /**
     * Extracts a navigation destination from [intent] and posts it to [appNavigationDispatcher].
     * Currently handles [ReauthNotificationHelper.ACTION_OPEN_ACCOUNTS] and
     * [com.synckro.util.notification.SyncStatusNotifier.ACTION_OPEN_LOGS].
     */
    private fun dispatchNavigationIntent(intent: Intent) {
        when (intent.action) {
            ReauthNotificationHelper.ACTION_OPEN_ACCOUNTS -> {
                val accountId = intent.getStringExtra(ReauthNotificationHelper.EXTRA_ACCOUNT_ID)
                appNavigationDispatcher.navigateTo(AppNavEvent.OpenAccounts(accountId = accountId))
            }

            com.synckro.util.notification.SyncStatusNotifier.ACTION_OPEN_LOGS -> {
                val pairId = intent.getLongExtra(com.synckro.util.notification.SyncStatusNotifier.EXTRA_PAIR_ID, 0L)
                if (pairId > 0L) {
                    appNavigationDispatcher.navigateTo(AppNavEvent.OpenLogs(pairId = pairId))
                }
            }
        }
    }
}
