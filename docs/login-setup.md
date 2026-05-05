# End-to-end login setup — Google Drive & OneDrive

This document walks you through every step required to sign into Synckro with
**Google Drive** and **OneDrive** on a fresh clone of the repository, including
the provider-side console setup needed before the app can authenticate.

> **Prerequisite**: Complete **[docs/debug-auth-setup.md](debug-auth-setup.md)**
> first. It covers creating a pinned debug keystore and obtaining the SHA-1 /
> SHA-256 fingerprints that both providers require. References to "your SHA-1"
> and "your base64 signature hash" in this document refer to values produced in
> that guide.

---

## Table of contents

1. [Google Drive](#google-drive)
   - [1. Create a Google Cloud project and enable the Drive API](#1-create-a-google-cloud-project-and-enable-the-drive-api)
   - [2. Configure the OAuth consent screen](#2-configure-the-oauth-consent-screen)
   - [3. Create OAuth 2.0 client IDs](#3-create-oauth-20-client-ids)
   - [4. Add webClientId to your local build](#4-add-webclientid-to-your-local-build)
   - [5. Required scope — drive](#5-required-scope--drive)
   - [6. First-run sign-in flow in the app](#6-first-run-sign-in-flow-in-the-app)
   - [7. Troubleshooting Google Drive](#7-troubleshooting-google-drive)
2. [OneDrive](#onedrive)
   - [1. Register the app in Microsoft Entra ID](#1-register-the-app-in-microsoft-entra-id)
   - [2. Add the Android redirect URI](#2-add-the-android-redirect-uri)
   - [3. Grant API permissions](#3-grant-api-permissions)
   - [4. Create msal_config.json](#4-create-msal_configjson)
   - [5. First-run sign-in flow in the app](#5-first-run-sign-in-flow-in-the-app)
   - [6. Troubleshooting OneDrive](#6-troubleshooting-onedrive)
3. [Common operations](#common-operations)
   - [Sign out](#sign-out)
   - [Switch accounts](#switch-accounts)
   - [Revoked tokens](#revoked-tokens)

---

## Google Drive

The app uses **Credential Manager + Google Identity Services (GIS)** for
sign-in, and `Identity.getAuthorizationClient(...).authorize(...)` for the
`drive` OAuth scope.

### 1. Create a Google Cloud project and enable the Drive API

1. Open [Google Cloud Console](https://console.cloud.google.com/) and sign in.
2. Click the project dropdown at the top and choose **New project**.
   - Name: anything you like (e.g. `Synckro`).
   - Click **Create**.
3. Make sure the new project is selected in the dropdown.
4. Go to **APIs & Services → Library**.
5. Search for **Google Drive API** and click **Enable**.
6. Optionally also enable the **People API** (used to fetch the user's profile
   picture / name, though the app works without it).

### 2. Configure the OAuth consent screen

1. Go to **APIs & Services → OAuth consent screen**.
2. User type: choose **External** (works for any Google account).
3. Fill in the required fields:
   - App name: `Synckro` (or a name that identifies your build).
   - User support email: your email.
   - Developer contact email: your email.
4. Click **Save and continue** through the **Scopes** and **Test users** pages.
   - On the **Test users** page, add the Google account(s) you will use during
     testing (required while the app is in "Testing" status).
5. Click **Back to dashboard**. The consent screen is now configured.

> **Publishing status**: While the app is in "Testing", only the added test
> users can sign in. You do not need to submit for verification to test locally.

### 3. Create OAuth 2.0 client IDs

You need **two** client ID entries: a Web client (used by Credential Manager to
obtain the ID token) and an Android client (used by Google to verify the APK's
signature).

#### Web client

1. Go to **APIs & Services → Credentials**.
2. Click **Create credentials → OAuth client ID**.
3. Application type: **Web application**.
4. Name: `Synckro web` (or any label — this is for your reference only).
5. Leave Authorized redirect URIs empty.
6. Click **Create**.
7. Copy the **Client ID** shown in the dialog
   (format: `123456789-xxxx.apps.googleusercontent.com`).
   This is your `GOOGLE_WEB_CLIENT_ID`.

#### Android client (debug build)

1. Click **Create credentials → OAuth client ID** again.
2. Application type: **Android**.
3. Name: `Synckro Android debug`.
4. Package name: `com.synckro.debug`
   (the `.debug` suffix comes from `applicationIdSuffix ".debug"` in the debug
   build type).
5. SHA-1 certificate fingerprint: paste the **SHA-1** from your debug
   keystore (see `debug-auth-setup.md` Step 1).
6. Click **Create**.

> You do **not** need to note down the Android client ID. Google matches it
> automatically by package name + SHA-1 fingerprint at runtime.

#### Android client (release build, if applicable)

Repeat the Android client steps with:
- Package name: `com.synckro`
- SHA-1: your release keystore's SHA-1.

### 4. Add webClientId to your local build

The Web client ID must be available to the app at compile time. It flows via
`BuildConfig.GOOGLE_WEB_CLIENT_ID` (see `app/build.gradle.kts`).

**Option A — `local.properties`** (recommended for local development)

Open (or create) `local.properties` in the repository root and add:

```properties
GOOGLE_WEB_CLIENT_ID=123456789-xxxx.apps.googleusercontent.com
```

**Option B — environment variable** (used in CI)

```bash
export GOOGLE_WEB_CLIENT_ID=123456789-xxxx.apps.googleusercontent.com
./gradlew assembleDebug
```

In GitHub Actions, store the value as the repository secret
`GOOGLE_WEB_CLIENT_ID` (see `debug-auth-setup.md` Step 5).

`GoogleDriveAuthManager` reads this value via `BuildConfig.GOOGLE_WEB_CLIENT_ID`
and returns `AuthResult.NotConfigured` from `signIn()` if it is blank, so the
app will not crash — it will just show a "not configured" message in the
Accounts screen.

### 5. Required scope — drive

The app requests the `https://www.googleapis.com/auth/drive` scope, which
grants full access to the user's Google Drive. This is required so Synckro can
list arbitrary existing folders and sync files to any location the user selects
in Drive.

The scope is requested during `signIn()` by:

```kotlin
// GoogleDriveAuthManager.kt (simplified)
val driveAuthRequest = AuthorizationRequest.builder()
    .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive")))
    .build()

Identity.getAuthorizationClient(activity).authorize(driveAuthRequest)
```

If the user has not previously granted the scope a consent screen appears
before sign-in completes.

### 6. First-run sign-in flow in the app

1. Open the app and go to **Accounts** (bottom nav or settings).
2. Tap **Sign in** under **Google Drive**.
3. The Credential Manager bottom sheet appears — select your Google account.
4. If this is the first sign-in (or the scope was revoked), a consent screen
   appears asking to allow "Synckro" to view and manage all files in your
   Google Drive. Tap **Allow**.
5. Sign-in completes. The **Accounts** screen now shows your Google account
   row with your display name and email.
6. After adding a sync pair that points to Google Drive, Synckro can read and
   write files in the folder you choose inside Drive.

### 7. Troubleshooting Google Drive

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| `DEVELOPER_ERROR` during sign-in | SHA-1 mismatch or wrong package name in the Android OAuth client | Re-check package name (`…synckro.debug` for debug builds) and that the SHA-1 in Google Cloud matches the keystore you are signing with. |
| Credential Manager sheet is empty / "No accounts found" | The Android OAuth client is missing or the Play Services version on the device is outdated | Ensure you created the **Android** client (not just Web), and update Google Play Services on the device. |
| `NotConfigured` error in the app | `GOOGLE_WEB_CLIENT_ID` is blank in `BuildConfig` | Set `GOOGLE_WEB_CLIENT_ID` in `local.properties` and rebuild. |
| Consent screen loop (re-shows on every sign-in) | The Web client ID doesn't match the one in Google Cloud | Make sure you are using the **Web** client ID, not the Android one. |
| `GetCredentialException: type not registered` | Missing or outdated Credential Manager Play Services module | Update Google Play Services / Play Services Auth (min version required: 20.x). |

---

## OneDrive

The app uses **MSAL (Microsoft Authentication Library)** in single-account mode
backed by `R.raw.msal_config`.

### 1. Register the app in Microsoft Entra ID

1. Open [Microsoft Entra admin center](https://entra.microsoft.com/) and sign in.
2. Go to **App registrations → New registration**.
   - Name: `Synckro debug` (or a descriptive name).
   - Supported account types: **Accounts in any organizational directory and
     personal Microsoft accounts** (the "common" endpoint — required to support
     both work/school and personal accounts).
   - Redirect URI: leave blank for now.
   - Click **Register**.
3. On the overview page, copy the **Application (client) ID**
   (a UUID, e.g. `aaaabbbb-cccc-dddd-eeee-ffffgggggggg`).
   This is your `MS_CLIENT_ID`.

### 2. Add the Android redirect URI

MSAL uses a custom URI scheme to return the auth token back to the app after
the system browser completes the sign-in flow.

#### Compute your Base64-encoded signature hash

Convert your colon-separated SHA-1 fingerprint (from `debug-auth-setup.md`
Step 1) to the URL-safe base64 format that Azure expects:

```bash
# Replace "AA:BB:CC:…" with your actual SHA-1 fingerprint (40 hex chars, colon-separated)
echo "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD" \
  | xxd -r -p | base64
```

The output is a 28-character base64 string (e.g. `qqqwww...==`).

#### Register the Android platform in Azure

1. In the app registration, go to **Authentication → Add a platform → Android**.
2. Package name: `com.synckro.debug`.
3. Signature hash: paste the base64 string from the previous step.
4. Click **Configure**.
5. Azure displays a **redirect URI** of the form:

   ```
   msauth://com.synckro.debug/<base64-hash>
   ```

   Copy this **entire URI verbatim** (URL-encoded, do not modify).
   This is your `MSAL_REDIRECT_URI`.

#### Wire the redirect URI into AndroidManifest.xml via Gradle

The redirect URI is split into `host` and `path` components and injected as
manifest placeholders by the Gradle build scripts. You do not need to edit
`AndroidManifest.xml` manually. Instead, add the following to `local.properties`
(or the corresponding CI secrets):

```properties
MS_CLIENT_ID=aaaabbbb-cccc-dddd-eeee-ffffgggggggg
MSAL_REDIRECT_URI=msauth://com.synckro.debug/<base64-hash>
```

The app's `build.gradle.kts` reads these values and passes them as
`${msalHost}` / `${msalPath}` placeholders to the `BrowserTabActivity`
`intent-filter` in `AndroidManifest.xml`:

```xml
<!-- AndroidManifest.xml (already present, no manual edit required) -->
<activity
    android:name="com.microsoft.identity.client.BrowserTabActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data
            android:scheme="msauth"
            android:host="${msalHost}"
            android:path="${msalPath}" />
    </intent-filter>
</activity>
```

**Build-time validation rules (debug builds only):**

| State | Result |
|-------|--------|
| Both `MS_CLIENT_ID` and `MSAL_REDIRECT_URI` unset | Build succeeds with a `WARNING:` line; OneDrive sign-in shows "not configured in this build" at runtime with no MSAL stack trace. |
| Exactly one of the two set | **Build fails** with a message naming the missing variable and pointing at `docs/login-setup.md`. |
| Both set but `MSAL_REDIRECT_URI` missing `msauth://` prefix, empty host, or empty path | **Build fails** with a descriptive error. |
| Both set, URI valid | Build succeeds normally. |

### 3. Grant API permissions

1. In the app registration, go to **API permissions → Add a permission →
   Microsoft Graph → Delegated permissions**.
2. Add all three permissions:
   - `Files.ReadWrite` — read and write files the app creates.
   - `User.Read` — read the signed-in user's profile.
   - `offline_access` — obtain a refresh token for background sync.
3. Click **Add permissions**.

> You do **not** need to click "Grant admin consent" for personal Microsoft
> accounts or most organizational accounts — the user grants consent
> interactively on first sign-in.

### 4. Create msal_config.json

MSAL requires a configuration file at `app/src/main/res/raw/msal_config.json`.
Create the file with the following content, replacing the placeholders:

```json
{
  "client_id": "aaaabbbb-cccc-dddd-eeee-ffffgggggggg",
  "authorization_user_agent": "DEFAULT",
  "redirect_uri": "msauth://com.synckro.debug/<base64-hash>",
  "account_mode": "SINGLE",
  "authorities": [
    {
      "type": "AAD",
      "audience": {
        "type": "AzureADandPersonalMicrosoftAccount",
        "tenant_id": "common"
      }
    }
  ]
}
```

> **Important**: The `client_id` and `redirect_uri` in this file must exactly
> match the values registered in Azure and set in `local.properties`. A mismatch
> causes MSAL to fail at initialisation with an `MsalClientException`.

`OneDriveAuthManager` reads this file via `R.raw.msal_config` (the resource ID
is resolved by the Android resource system at runtime). If the file is missing
the MSAL `PublicClientApplication.createSingleAccountPublicClientApplication`
call fails and `isConfigured()` returns `false`, causing the app to show a
"not configured" message in the Accounts screen.

### 5. First-run sign-in flow in the app

1. Open the app and go to **Accounts**.
2. Tap **Sign in** under **OneDrive**.
3. The system browser opens and loads the Microsoft login page.
4. Sign in with a personal Microsoft account or a work/school account.
5. The consent screen lists the requested permissions
   (`Files.ReadWrite`, `User.Read`, `offline_access`). Tap **Accept**.
6. The browser redirects back to the app via the `msauth://` URI.
7. Sign-in completes. The **Accounts** screen now shows your Microsoft account
   row with your email address.

### 6. Troubleshooting OneDrive

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| `MsalClientException: Intent filter for: BrowserTabActivity is missing` | `MSAL_REDIRECT_URI` or `MS_CLIENT_ID` was not set at build time, or a stale APK is installed | Uninstall the APK, set both values in `local.properties`, rebuild with `./gradlew assembleDebug`, and reinstall. The build will now fail loudly if either value is missing or malformed. |
| "OneDrive sign-in is not configured in this build." in the app | Both `MS_CLIENT_ID` and `MSAL_REDIRECT_URI` were blank when the APK was built | Set both values in `local.properties` (or CI secrets) and rebuild. |
| Browser doesn't redirect back to app | Redirect URI mismatch between Azure, `msal_config.json`, and `local.properties` | Copy the URI from Azure **exactly as shown** (URL-encoded). Check that all three places use the same string. |
| `MsalClientException: Configuration error` | `client_id` or `redirect_uri` in `msal_config.json` doesn't match Azure | Re-copy values from the Azure overview / Authentication pages. |
| Sign-in succeeds but sync fails with 401 | `offline_access` permission missing → no refresh token | Add `offline_access` to API permissions in Azure (see Step 3). |
| Debug build works but release build doesn't | Signature hash for release keystore not registered | Add a second Android platform entry in Azure with the release SHA-1 base64 hash. |
| "No account signed in" error after reinstall | MSAL token cache cleared (expected on reinstall) | Sign in again from the Accounts screen. |

---

## Common operations

### Sign out

1. Go to **Accounts** in the app.
2. Tap the account row you want to sign out of.
3. Tap **Sign out**.

**What is cleared:**

- **Google Drive**: account metadata (id, display name, email) is removed from
  `EncryptedSharedPreferences`. The OAuth token itself is stored inside Google
  Play Services' own encrypted storage and is cleared by GIS when the user
  revokes access or signs out through the app.
- **OneDrive**: the MSAL token cache is cleared via
  `ISingleAccountPublicClientApplication.signOut()`. The account hint (email)
  stored in `EncryptedSharedPreferences` is also cleared.

In both cases, any pending or running sync workers for pairs that used that
account are cancelled and removed from WorkManager. The sync pair records
remain in the Room database; they must be deleted or re-configured manually.

### Switch accounts

The app currently supports **one account per provider** (single-account mode
for OneDrive; single Google account stored in encrypted prefs for Google Drive).

To switch to a different account:

1. Sign out of the current account (see above).
2. Tap **Sign in** under the same provider and authenticate with the new
   account.

Any sync pairs that were linked to the old account will fail to authenticate
after the switch. Delete and recreate those pairs to use the new account.

### Revoked tokens

If the user revokes the app's access from the provider's account settings
(e.g. Google Account → Security → Third-party apps; or Microsoft account →
Privacy → App permissions) without signing out from within Synckro:

- **Google Drive**: the next `acquireAccessToken()` call via GIS will return
  `AuthResult.NeedsInteractiveSignIn`. The sync worker will surface a
  "re-authentication required" notification and pause syncing until the user
  signs in again.
- **OneDrive**: MSAL's silent `acquireTokenSilentAsync()` returns
  `MsalUiRequiredException`, which is mapped to
  `AuthResult.NeedsInteractiveSignIn`. The same notification flow applies.

Tapping the notification or opening **Accounts** and tapping **Sign in** again
resumes normal operation. For more detail see
[issue #8](https://github.com/konarsubhojit/synckro/issues/8).
