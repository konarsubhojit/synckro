package com.synckro.providers.gdrive

import android.accounts.Account as AndroidAccount
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.util.Patterns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.synckro.BuildConfig
import com.synckro.R
import com.synckro.domain.auth.Account
import com.synckro.domain.auth.AuthManager
import com.synckro.domain.auth.AuthResult
import com.synckro.domain.auth.AuthUiHost
import com.synckro.domain.model.CloudProviderType
import com.synckro.ui.auth.ActivityAuthUiHost
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Base64
import kotlin.coroutines.resume

/**
 * Google Drive [AuthManager] backed by Credential Manager (Google Identity Services)
 * for sign-in and [Identity.getAuthorizationClient] for OAuth 2.0 Drive-scope
 * authorization.
 *
 * Sign-in flow:
 *  1. [CredentialManager.getCredential] with [GetGoogleIdOption] → Google ID token.
 *  2. [Identity.getAuthorizationClient] + [AuthorizationRequest] for
 *     `https://www.googleapis.com/auth/drive` → access token (may show a
 *     consent screen on first use via a [PendingIntent]).
 *
 * Token refresh ([acquireAccessToken]):
 *  - Calls [Identity.getAuthorizationClient] silently (application context, no
 *    activity). Google Play Services caches and refreshes the refresh token
 *    transparently. Returns [AuthResult.NeedsInteractiveSignIn] when consent
 *    must be re-obtained.
 *
 * Account metadata is persisted in [EncryptedSharedPreferences] as a multi-account
 * JSON list so it survives process restarts. The OAuth tokens themselves stay
 * inside Google Play Services' own encrypted storage; no raw tokens are written
 * to disk by this class.
 */
@Singleton
class GoogleDriveAuthManager private constructor(
    private val context: Context,
    private val prefsOverride: SharedPreferences?,
    private val webClientId: String,
) : AuthManager {
    companion object {
        private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
        private const val PREFS_NAME = "gdrive_account"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_EMAIL = "email"

        /**
         * Creates an instance backed by a plain [SharedPreferences] instead of
         * [EncryptedSharedPreferences]. Use this in unit tests (Robolectric) where
         * the Android Keystore is unavailable.
         *
         * Passes an empty [webClientId] by default so [isConfigured] returns `false`
         * regardless of what [BuildConfig.GOOGLE_WEB_CLIENT_ID] contains in the CI
         * build environment.
         */
        internal fun forTest(
            context: Context,
            testPrefs: SharedPreferences,
            webClientId: String = "",
        ) = GoogleDriveAuthManager(context, prefsOverride = testPrefs, webClientId = webClientId)
    }

    /** Hilt-injected constructor (production path). */
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) :
        this(context, prefsOverride = null, webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID)

    override val providerType: CloudProviderType = CloudProviderType.GOOGLE_DRIVE
    override val displayName: String = "Google Drive"

    private val credentialManager: CredentialManager = CredentialManager.create(context)

    /**
     * Lazily-created [EncryptedSharedPreferences] for persisting account metadata
     * (id, displayName, email) across process restarts. Replaced by a plain
     * [SharedPreferences] in unit tests via [prefsOverride].
     */
    private val accountPrefs: SharedPreferences by lazy {
        prefsOverride ?: createEncryptedPrefs()
    }

    @Suppress("DEPRECATION") // MasterKey.Builder / EncryptedSharedPreferences.create are deprecated
    // in security-crypto 1.1.0 but no stable non-deprecated replacement exists for the 5-arg
    // static factory yet. The functionality is identical; suppress to keep clean builds.
    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey =
            MasterKey
                .Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun isConfigured(): Boolean = webClientId.isNotBlank()

    override suspend fun signIn(host: AuthUiHost): AuthResult<Account> {
        if (!isConfigured()) {
            return AuthResult.NotConfigured(context.getString(R.string.gdrive_not_configured))
        }

        val activity =
            (host as? ActivityAuthUiHost)?.activity
                ?: run {
                    Timber.e("GoogleDriveAuthManager.signIn: host is not ActivityAuthUiHost")
                    return AuthResult.Error(context.getString(R.string.accounts_host_unavailable))
                }

        // Step 1: Obtain Google ID token via Credential Manager.
        val googleIdOption =
            GetGoogleIdOption
                .Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(false)
                .build()

        val idTokenCredential: GoogleIdTokenCredential =
            try {
                val response =
                    credentialManager.getCredential(
                        context = activity,
                        request =
                            GetCredentialRequest
                                .Builder()
                                .addCredentialOption(googleIdOption)
                                .build(),
                    )
                GoogleIdTokenCredential.createFrom(response.credential.data)
            } catch (e: GetCredentialCancellationException) {
                Timber.i("GoogleDriveAuthManager.signIn: user cancelled credential picker")
                return AuthResult.Cancelled
            } catch (e: NoCredentialException) {
                Timber.w(e, "GoogleDriveAuthManager.signIn: no credential available")
                return AuthResult.Error(context.getString(R.string.gdrive_no_credential), e)
            } catch (e: GetCredentialException) {
                Timber.e(e, "GoogleDriveAuthManager.signIn: credential exception")
                return AuthResult.Error(e.message ?: context.getString(R.string.gdrive_signin_failed), e)
            } catch (e: GoogleIdTokenParsingException) {
                Timber.e(e, "GoogleDriveAuthManager.signIn: failed to parse Google ID token")
                return AuthResult.Error(e.message ?: context.getString(R.string.gdrive_signin_failed), e)
            }

        // Step 2: Request Drive authorization (shows consent screen if needed).
        val accountEmail = resolveGoogleAccountEmail(idTokenCredential.id, idTokenCredential.idToken)
        val authorizationResult =
            Identity
                .getAuthorizationClient(activity)
                .authorize(buildDriveAuthRequest(accountEmail = accountEmail))
                .awaitTask()
                .getOrElse { e ->
                    Timber.e(e, "GoogleDriveAuthManager.signIn: authorization failed")
                    return AuthResult.Error(
                        e.message ?: context.getString(R.string.gdrive_signin_failed),
                        e,
                    )
                }

        if (authorizationResult.hasResolution()) {
            // A consent screen must be shown. Launch the pending intent and wait.
            val pendingIntent =
                authorizationResult.pendingIntent
                    ?: return AuthResult.Error(context.getString(R.string.gdrive_signin_failed))

            val granted = awaitConsentResult(activity, pendingIntent)
            if (!granted) {
                Timber.i("GoogleDriveAuthManager.signIn: user declined Drive consent")
                return AuthResult.Cancelled
            }
        }

        val account =
            Account(
                id = idTokenCredential.id,
                provider = CloudProviderType.GOOGLE_DRIVE,
                displayName = resolveDisplayName(idTokenCredential.displayName, accountEmail, idTokenCredential.id),
                email = accountEmail,
            )
        storeAccount(account)
        Timber.i("GoogleDriveAuthManager.signIn: success for ${account.email}")
        return AuthResult.Success(account)
    }

    override suspend fun signOut(account: Account): AuthResult<Unit> {
        Timber.i("GoogleDriveAuthManager.signOut: account=${account.id}")
        clearCachedAuthorization(account)
        removeStoredAccount(account.id)
        return AuthResult.Success(Unit)
    }

    override suspend fun currentAccounts(): List<Account> {
        return readStoredAccounts()
    }

    /**
     * Attempts a silent token acquisition via [Identity.getAuthorizationClient].
     * Google Play Services will use its cached refresh token to obtain a fresh
     * access token without user interaction. Returns [AuthResult.NeedsInteractiveSignIn]
     * if the refresh token has been revoked or consent must be re-granted.
     */
    override suspend fun acquireAccessToken(account: Account): AuthResult<String> {
        if (!isConfigured()) {
            return AuthResult.NotConfigured(context.getString(R.string.gdrive_not_configured))
        }

        val storedAccount =
            readStoredAccounts().firstOrNull { it.id == account.id }
                ?: return AuthResult.NeedsInteractiveSignIn
        val email = resolveSilentAuthEmail(storedAccount, account) ?: return AuthResult.NeedsInteractiveSignIn
        val resolvedStoredAccount =
            if (storedAccount.email == email) {
                storedAccount
            } else {
                storedAccount.copy(email = email).also { storeAccount(it) }
            }

        Timber.d("GoogleDriveAuthManager.acquireAccessToken: silent acquisition for ${account.id}")

        val authorizationResult =
            Identity
                .getAuthorizationClient(context)
                .authorize(buildDriveAuthRequest(accountEmail = email))
                .awaitTask()
                .getOrElse { e ->
                    Timber.e(e, "GoogleDriveAuthManager.acquireAccessToken: authorization error")
                    return AuthResult.Error(e.message ?: "Token acquisition failed", e)
                }

        if (authorizationResult.hasResolution()) {
            Timber.w("GoogleDriveAuthManager.acquireAccessToken: re-authorization required")
            return AuthResult.NeedsInteractiveSignIn
        }

        if (!authorizationResult.matchesRequestedAccount(resolvedStoredAccount)) {
            Timber.w("GoogleDriveAuthManager.acquireAccessToken: silent result resolved a different account")
            return AuthResult.NeedsInteractiveSignIn
        }

        val token = authorizationResult.accessToken
        return if (token.isNullOrBlank()) {
            Timber.e("GoogleDriveAuthManager.acquireAccessToken: token null after silent authorization")
            AuthResult.NeedsInteractiveSignIn
        } else {
            Timber.d("GoogleDriveAuthManager.acquireAccessToken: acquired token successfully")
            AuthResult.Success(token)
        }
    }

    // ---- Private helpers ----

    internal fun storeAccount(account: Account) {
        val updatedAccounts =
            readStoredAccounts()
                .filterNot { it.id == account.id }
                .toMutableList()
                .apply { add(account) }
        writeStoredAccounts(updatedAccounts)
    }

    private fun removeStoredAccount(accountId: String) {
        val updatedAccounts = readStoredAccounts().filterNot { it.id == accountId }
        writeStoredAccounts(updatedAccounts)
    }

    private fun buildDriveAuthRequest(accountEmail: String? = null): AuthorizationRequest =
        AuthorizationRequest
            .builder()
            .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
            .apply {
                // Interactive sign-in may not yet know which account to pin, but silent
                // token acquisition always supplies a concrete email to target.
                if (!accountEmail.isNullOrBlank()) {
                    // Pin silent authorization to the requested Google account so we never
                    // accept a token resolved for a different principal on the device.
                    setAccount(AndroidAccount(accountEmail, GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE))
                }
            }.build()

    internal fun resolveGoogleAccountEmail(
        credentialId: String,
        idToken: String?,
    ): String? =
        listOfNotNull(
            credentialId.takeIf(::hasEmailShape),
            extractEmailFromIdToken(idToken),
        ).firstOrNull()

    internal fun resolveSilentAuthEmail(
        storedAccount: Account,
        requestedAccount: Account,
    ): String? =
        listOfNotNull(
            storedAccount.email?.takeIf(::hasEmailShape),
            requestedAccount.email?.takeIf(::hasEmailShape),
            requestedAccount.id.takeIf(::hasEmailShape),
            storedAccount.id.takeIf(::hasEmailShape),
        ).firstOrNull()

    internal fun extractEmailFromIdToken(idToken: String?): String? {
        if (idToken.isNullOrBlank()) return null
        val payloadPart = idToken.split('.').getOrNull(1) ?: return null
        return runCatching {
            // This claim is decoded only as a fallback account hint when CredentialManager does
            // not surface an email-like id directly. The idToken value originates from Google
            // CredentialManager/Play Services (not app-provided input), and Drive authorization
            // still relies on Play Services token APIs. We do not treat this decoded claim as
            // standalone identity proof.
            val payloadJson = String(Base64.getUrlDecoder().decode(payloadPart))
            JSONObject(payloadJson).optString("email").takeIf(::hasEmailShape)
        }.getOrNull()
    }

    private fun resolveDisplayName(
        providerDisplayName: String?,
        resolvedEmail: String?,
        fallbackId: String,
    ): String = providerDisplayName ?: resolvedEmail ?: fallbackId

    internal fun hasEmailShape(value: String?): Boolean {
        val trimmed = value?.trim().orEmpty()
        return trimmed.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()
    }

    private fun readStoredAccounts(): List<Account> {
        val stored = accountPrefs.getString(KEY_ACCOUNTS, null)
        if (stored.isNullOrBlank()) {
            return migrateLegacyAccountIfNeeded()
        }

        return try {
            val json = JSONArray(stored)
            buildList(json.length()) {
                repeat(json.length()) { index ->
                    val item = json.optJSONObject(index) ?: return@repeat
                    val id = item.optString(KEY_ACCOUNT_ID)
                    if (id.isBlank()) return@repeat
                    add(
                        Account(
                            id = id,
                            provider = CloudProviderType.GOOGLE_DRIVE,
                            displayName = item.optString(KEY_DISPLAY_NAME).ifBlank { id },
                            email = item.optString(KEY_EMAIL).ifEmpty { null },
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "GoogleDriveAuthManager: failed to parse stored accounts")
            emptyList()
        }
    }

    private fun writeStoredAccounts(accounts: List<Account>) {
        val json =
            JSONArray().apply {
                accounts.forEach { account ->
                    put(
                        JSONObject()
                            .put(KEY_ACCOUNT_ID, account.id)
                            .put(KEY_DISPLAY_NAME, account.displayName)
                            .put(KEY_EMAIL, account.email),
                    )
                }
            }

        accountPrefs
            .edit()
            .putString(KEY_ACCOUNTS, json.toString())
            .remove(KEY_ACCOUNT_ID)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_EMAIL)
            .apply()
    }

    private fun migrateLegacyAccountIfNeeded(): List<Account> {
        val id = accountPrefs.getString(KEY_ACCOUNT_ID, null) ?: return emptyList()
        val migratedAccount =
            Account(
                id = id,
                provider = CloudProviderType.GOOGLE_DRIVE,
                displayName = accountPrefs.getString(KEY_DISPLAY_NAME, null) ?: id,
                email = accountPrefs.getString(KEY_EMAIL, null),
            )
        writeStoredAccounts(listOf(migratedAccount))
        return listOf(migratedAccount)
    }

    private suspend fun clearCachedAuthorization(account: Account) {
        val token =
            when (val result = acquireAccessToken(account)) {
                is AuthResult.Success -> result.value
                else -> return
            }

        withContext(Dispatchers.IO) {
            try {
                GoogleAuthUtil.clearToken(context, token)
            } catch (e: Exception) {
                Timber.w(e, "GoogleDriveAuthManager.signOut: failed to clear cached token for ${account.id}")
            }
        }
    }

    private fun AuthorizationResult.matchesRequestedAccount(account: Account): Boolean {
        val resolvedAccount = toGoogleSignInAccount()
        return matchesRequestedAccount(
            account = account,
            resolvedAccountId = resolvedAccount?.id,
            resolvedEmail = resolvedAccount?.email,
        )
    }

    internal fun matchesRequestedAccount(
        account: Account,
        @Suppress("UNUSED_PARAMETER")
        resolvedAccountId: String?,
        resolvedEmail: String?,
    ): Boolean {
        // toGoogleSignInAccount() often yields null/blank email for silent Drive-scope-only
        // authorization results. Because buildDriveAuthRequest() already pins authorization to
        // the requested account via setAccount(), unverifiable silent results should be trusted.
        // Also, GoogleSignInAccount.id is an opaque Google account id, not the user's email.
        val requestedEmail = account.email ?: account.id
        return resolvedEmail.isNullOrBlank() || resolvedEmail.equals(requestedEmail, ignoreCase = true)
    }

    /**
     * Converts a Play Services [Task] into a coroutine [Result], resuming the
     * caller as soon as the task completes on the Play Services thread.
     */
    private suspend fun <T> Task<T>.awaitTask(): Result<T> =
        suspendCancellableCoroutine { cont ->
            addOnSuccessListener { result ->
                if (cont.isActive) cont.resume(Result.success(result))
            }
            addOnFailureListener { e ->
                if (cont.isActive) cont.resume(Result.failure(e))
            }
        }

    /**
     * Launches the OAuth consent [pendingIntent] via [ComponentActivity.activityResultRegistry]
     * (no pre-registration required) and suspends until the user grants or denies access.
     *
     * @return `true` if the user granted access ([Activity.RESULT_OK]), `false` otherwise.
     */
    private suspend fun awaitConsentResult(
        activity: ComponentActivity,
        pendingIntent: PendingIntent,
    ): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val key = "gdrive_consent_${java.util.UUID.randomUUID()}"
                var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
                launcher =
                    activity.activityResultRegistry.register(
                        key,
                        ActivityResultContracts.StartIntentSenderForResult(),
                    ) { result ->
                        launcher?.unregister()
                        if (cont.isActive) cont.resume(result.resultCode == Activity.RESULT_OK)
                    }
                cont.invokeOnCancellation { launcher?.unregister() }
                launcher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
            }
        }
}
