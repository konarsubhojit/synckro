package com.konarsubhojit.synckro.domain.auth

/**
 * Platform-neutral handle for the host that can present an interactive
 * sign-in UI. On Android this is implemented by an Activity-backed adapter
 * in the platform layer; the domain module sees only this opaque marker so
 * it remains free of `android.*` imports.
 *
 * Concrete implementations belong in the platform / UI layer and are
 * expected to be downcast by provider-specific [AuthManager]s that need
 * access to platform primitives (Intents, ActivityResultLaunchers, …).
 */
interface AuthUiHost
