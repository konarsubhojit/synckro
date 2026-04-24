package com.konarsubhojit.synckro

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.konarsubhojit.synckro.ui.navigation.SynckroNavHost
import com.konarsubhojit.synckro.ui.theme.SynckroTheme
import com.konarsubhojit.synckro.util.error.LocalUserMessageReporter
import com.konarsubhojit.synckro.util.error.UserMessage
import com.konarsubhojit.synckro.util.error.UserMessageReporter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collectLatest

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var userMessages: UserMessageReporter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SynckroTheme {
                val snackbarHostState = remember { SnackbarHostState() }

                // Route every reported UserMessage to the snackbar host. We
                // use `collectLatest` so that a burst of errors doesn't leave
                // the user stuck dismissing outdated messages — the newest
                // one always wins.
                LaunchedEffect(snackbarHostState) {
                    userMessages.messages.collectLatest { message ->
                        val result = snackbarHostState.showSnackbar(
                            message = message.text,
                            actionLabel = message.actionLabel,
                            withDismissAction = message.actionLabel == null,
                            duration = when (message.severity) {
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
                            SnackbarHost(hostState = snackbarHostState) { data ->
                                // Using the default snackbar with Material3 theming;
                                // colors are driven by the explicit dark/light scheme.
                                Snackbar(snackbarData = data)
                            }
                        },
                    ) { padding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                        ) {
                            SynckroNavHost()
                        }
                    }
                }
            }
        }
    }
}
