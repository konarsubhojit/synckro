package com.konarsubhojit.synckro.ui.auth

import androidx.activity.ComponentActivity
import com.konarsubhojit.synckro.domain.auth.AuthUiHost

/**
 * Android adapter around a [ComponentActivity] used by the platform-layer
 * [com.konarsubhojit.synckro.domain.auth.AuthManager] implementations that
 * need to launch MSAL / Credential Manager flows. The domain layer only
 * sees [AuthUiHost]; this class keeps the `android.app.Activity` reference
 * on the platform side so the domain module stays Android-free.
 */
class ActivityAuthUiHost(val activity: ComponentActivity) : AuthUiHost
