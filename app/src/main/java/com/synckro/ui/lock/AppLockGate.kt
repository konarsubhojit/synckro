package com.synckro.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R

/**
 * Wraps app [content] behind the optional biometric app lock.
 *
 * When the lock is disabled (the default) [content] is shown untouched. When
 * enabled, a blocking lock screen replaces the content on launch and whenever
 * the app returns to the foreground, until a `BiometricPrompt` (biometrics or
 * device credential) succeeds. Cancelling or failing the prompt leaves the lock
 * screen in place so nothing sensitive is revealed.
 */
@Composable
fun AppLockGate(
    activity: FragmentActivity,
    modifier: Modifier = Modifier,
    viewModel: AppLockViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
                    // A configuration change (e.g. rotation) is not a real
                    // background transition, so it must not re-lock the app.
                    Lifecycle.Event.ON_STOP ->
                        if (!activity.isChangingConfigurations) {
                            viewModel.onAppBackgrounded()
                        }

                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val promptTitle = stringResource(R.string.app_lock_prompt_title)
    val promptSubtitle = stringResource(R.string.app_lock_prompt_subtitle)
    val promptCancel = stringResource(R.string.app_lock_prompt_cancel)

    LaunchedEffect(state.promptRequested) {
        if (!state.promptRequested) return@LaunchedEffect
        viewModel.onPromptShown()
        showBiometricPrompt(
            activity = activity,
            title = promptTitle,
            subtitle = promptSubtitle,
            negativeButtonText = promptCancel,
            onSucceeded = viewModel::onAuthSucceeded,
            onFailed = viewModel::onAuthFailed,
        )
    }

    if (state.contentVisible) {
        content()
    } else if (state.lockScreenVisible) {
        AppLockScreen(
            error = state.error,
            onUnlockClick = viewModel::onRetryRequested,
            onDisableLockClick = viewModel::disableLock,
            modifier = modifier,
        )
    } else {
        // Preference not read yet: render an empty surface so no content leaks
        // through before we know whether the lock is enabled.
        Surface(modifier = modifier.fillMaxSize()) {}
    }
}

@Composable
private fun AppLockScreen(
    error: AppLockError?,
    onUnlockClick: () -> Unit,
    onDisableLockClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(imageVector = Icons.Filled.Lock, contentDescription = null)
            Text(
                text = stringResource(R.string.app_lock_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.app_lock_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (error != null) {
                Text(
                    text =
                        stringResource(
                            when (error) {
                                AppLockError.Cancelled -> R.string.app_lock_error_cancelled
                                AppLockError.Failed -> R.string.app_lock_error_failed
                                AppLockError.Unavailable -> R.string.app_lock_error_unavailable
                            },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Button(onClick = onUnlockClick) {
                Text(stringResource(R.string.app_lock_unlock))
            }
            if (error == AppLockError.Unavailable) {
                TextButton(onClick = onDisableLockClick) {
                    Text(stringResource(R.string.app_lock_turn_off))
                }
            }
        }
    }
}

/**
 * Shows a `BiometricPrompt`, preferring a biometric + device-credential prompt
 * and falling back to whichever authenticator class the device supports. When
 * no authenticator is enrolled the caller is notified with
 * [AppLockError.Unavailable] so the app stays locked instead of proceeding.
 */
private fun showBiometricPrompt(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    negativeButtonText: String,
    onSucceeded: () -> Unit,
    onFailed: (AppLockError) -> Unit,
) {
    val biometricManager = BiometricManager.from(activity)
    val biometricsAvailable =
        biometricManager.canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    val deviceCredentialAvailable =
        biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    if (!biometricsAvailable && !deviceCredentialAvailable) {
        onFailed(AppLockError.Unavailable)
        return
    }

    val promptInfo =
        BiometricPrompt.PromptInfo
            .Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .apply {
                if (biometricsAvailable) {
                    setAllowedAuthenticators(BIOMETRIC_WEAK)
                    setNegativeButtonText(negativeButtonText)
                } else {
                    setAllowedAuthenticators(DEVICE_CREDENTIAL)
                }
            }.build()

    val prompt =
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSucceeded()
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    val error =
                        when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_CANCELED,
                            -> AppLockError.Cancelled

                            BiometricPrompt.ERROR_NO_BIOMETRICS,
                            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                            BiometricPrompt.ERROR_HW_NOT_PRESENT,
                            BiometricPrompt.ERROR_HW_UNAVAILABLE,
                            -> AppLockError.Unavailable

                            else -> AppLockError.Failed
                        }
                    onFailed(error)
                }
            },
        )
    prompt.authenticate(promptInfo)
}
