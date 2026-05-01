package com.konarsubhojit.synckro.providers.gdrive

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
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
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.konarsubhojit.synckro.BuildConfig
import com.konarsubhojit.synckro.R
import com.konarsubhojit.synckro.domain.auth.Account
import com.konarsubhojit.synckro.domain.auth.AuthManager
import com.konarsubhojit.synckro.domain.auth.AuthResult
import com.konarsubhojit.synckro.domain.auth.AuthUiHost
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import com.konarsubhojit.synckro.ui.auth.ActivityAuthUiHost
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Google Drive [AuthManager] backed by Credential Manager (Google Identity Services)
 * for sign-in and [Identity.getAuthorizationClient] for OAuth 2.0 Drive-scope
 * authorization.
 *
 * Sign-in flow:
 *  1. [CredentialManager.getCredential] with [GetGoogleIdOption] → Google ID token.
 *  2. [Identity.getAuthorizationClient] + [AuthorizationRequest] for
 *     `https://www.googleapis.com/auth/drive.file` → access token (may show a
 *     consent screen on first use via a [PendingIntent]).
 *
 * Token refresh ([acquireAccessToken]):
 *  - Calls [Identity.getAuthorizationClient] silently (application context, no
 *    activity). Google Play Services caches and refreshes the refresh token
 *    transparently. Returns [AuthResult.NeedsInteractiveSignIn] when consent
 *    must be re-obtained.
 *
 * Account metadata is persisted in [EncryptedSharedPreferences] so it survives
 * process restarts. The OAuth tokens themselves stay inside Google Play Services'
 * own encrypted storage; no raw tokens are written to disk by this class.
 */
@Singleton
class GoogleDriveAuthManager private constructor(
    private val context: Context,
    private val prefsOverride: SharedPreferences?,
) : AuthManager {

    companion object {
        private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val PREFS_NAME = "gdrive_account"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_EMAIL = "email"

        /**
         * Creates an instance backed by a plain [SharedPreferences] instead of
         * [EncryptedSharedPreferences]. Use this in unit tests (Robolectric) where
         * the Android Keystore is unavailable.
         */
        internal fun forTest(context: Context, testPrefs: SharedPreferences) =
            GoogleDriveAuthManager(context, prefsOverride = testPrefs)
    }

    /** Hilt-injected constructor (production path). */
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, prefsOverride = null)

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

    @Suppress("DEPRECATION")
    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
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

    override suspend fun isConfigured(): Boolean = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    override suspend fun signIn(host: AuthUiHost): AuthResult<Account> {
        if (!isConfigured()) {
            return AuthResult.NotConfigured(context.getString(R.string.gdrive_not_configured))
        }

        val activity = (host as? ActivityAuthUiHost)?.activity
            ?: run {
                Timber.e("GoogleDriveAuthManager.signIn: host is not ActivityAuthUiHost")
                return AuthResult.Error(context.getString(R.string.accounts_host_unavailable))
            }

        // Step 1: Obtain Google ID token via Credential Manager.
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setAutoSelectEnabled(false)
            .build()

        val idTokenCredential: GoogleIdTokenCredential = try {
            val response = credentialManager.getCredential(
                context = activity,
                request = GetCredentialRequest.Builder()
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
        val authRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
            .build()

        val authorizationResult = Identity.getAuthorizationClient(activity)
            .authorize(authRequest)
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
            val pendingIntent = authorizationResult.pendingIntent
                ?: return AuthResult.Error(context.getString(R.string.gdrive_signin_failed))

            val granted = awaitConsentResult(activity, pendingIntent)
            if (!granted) {
                Timber.i("GoogleDriveAuthManager.signIn: user declined Drive consent")
                return AuthResult.Cancelled
            }
        }

        val account = Account(
            id = idTokenCredential.id,
            provider = CloudProviderType.GOOGLE_DRIVE,
            displayName = idTokenCredential.displayName ?: idTokenCredential.id,
            email = idTokenCredential.id,
        )
        storeAccount(account)
        Timber.i("GoogleDriveAuthManager.signIn: success for ${account.email}")
        return AuthResult.Success(account)
    }

    override suspend fun signOut(account: Account): AuthResult<Unit> {
        Timber.i("GoogleDriveAuthManager.signOut: account=${account.id}")
        clearStoredAccount()
        return AuthResult.Success(Unit)
    }

    override suspend fun currentAccounts(): List<Account> {
        val id = accountPrefs.getString(KEY_ACCOUNT_ID, null) ?: return emptyList()
        val storedDisplayName = accountPrefs.getString(KEY_DISPLAY_NAME, null) ?: id
        val email = accountPrefs.getString(KEY_EMAIL, null)
        return listOf(
            Account(
                id = id,
                provider = CloudProviderType.GOOGLE_DRIVE,
                displayName = storedDisplayName,
                email = email,
            ),
        )
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

        Timber.d("GoogleDriveAuthManager.acquireAccessToken: silent acquisition for ${account.id}")

        val authRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
            .build()

        val authorizationResult = Identity.getAuthorizationClient(context)
            .authorize(authRequest)
            .awaitTask()
            .getOrElse { e ->
                Timber.e(e, "GoogleDriveAuthManager.acquireAccessToken: authorization error")
                return AuthResult.Error(e.message ?: "Token acquisition failed", e)
            }

        if (authorizationResult.hasResolution()) {
            Timber.w("GoogleDriveAuthManager.acquireAccessToken: re-authorization required")
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

    private fun storeAccount(account: Account) {
        accountPrefs.edit()
            .putString(KEY_ACCOUNT_ID, account.id)
            .putString(KEY_DISPLAY_NAME, account.displayName)
            .putString(KEY_EMAIL, account.email)
            .apply()
    }

    private fun clearStoredAccount() {
        accountPrefs.edit().clear().apply()
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
    ): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val key = "gdrive_consent_${System.nanoTime()}"
            var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
            launcher = activity.activityResultRegistry.register(
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
