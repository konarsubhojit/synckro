# Debug auth setup — Google & Microsoft

This document explains how to make Google (Credential Manager + GIS) and
Microsoft (MSAL) authentication work in a CI-built debug APK.

Both providers verify that the APK signing certificate matches a hash
registered in their respective consoles.  Because AGP generates a fresh random
debug keystore on every new CI runner, authentication fails nondeterministically
unless you:

1. Create a long-lived debug keystore once, and
2. Supply it to every CI run via repository secrets.

Follow the steps below in order.  They are one-time tasks for the repo owner;
contributors who only build locally and never need cloud auth can skip this.

---

## Step 1 — Create a pinned debug keystore

Run the following command **once** on your local machine.  This creates a
keystore that you will commit to the repository as a secret, not as a file.

```bash
keytool -genkeypair \
  -keystore debug.keystore \
  -alias androiddebugkey \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass android \
  -keypass android \
  -dname "CN=Android Debug,O=Android,C=US"
```

> **Password choice**: The command above uses `android` for both passwords,
> which is the same value AGP uses for its auto-generated debug keystore.
> You can choose any password; just be consistent with what you store in the
> secrets below.

### Get the SHA-1 and SHA-256 fingerprints

You will need these fingerprints when registering the app in Google Cloud and
Azure.

```bash
keytool -list -v \
  -keystore debug.keystore \
  -alias androiddebugkey \
  -storepass android
```

The output includes lines like:

```
Certificate fingerprints:
   SHA1: AA:BB:CC:DD:...
   SHA256: 11:22:33:44:...
```

Keep these fingerprints handy; you will need them in Steps 2 and 3.

### Encode the keystore as base64

```bash
# macOS / Linux
base64 -i debug.keystore | tr -d '\n'

# Git Bash / Windows
certutil -encode debug.keystore tmp.b64 && grep -v "CERTIFICATE" tmp.b64 | tr -d '\r\n'
```

Copy the entire output string — you will store it as `DEBUG_KEYSTORE_BASE64`
in Step 5.

> **Security note**: The debug keystore is used only for non-production builds.
> Do **not** use it as your release signing key.

---

## Step 2 — Register the Android app in Google Cloud

These steps configure Google Credential Manager / GIS so it accepts a login
from the CI-built debug APK.

1. Open [Google Cloud Console](https://console.cloud.google.com/) and select
   (or create) the project you use for this app.

2. Go to **APIs & Services → Credentials**.

3. **Create the Web OAuth client** (if you have not done this already):
   - Click **Create credentials → OAuth client ID**.
   - Application type: **Web application**.
   - Give it a name (e.g. `Synckro web`).
   - Click **Create**.
   - Copy the **Client ID** — this is your `GOOGLE_WEB_CLIENT_ID` secret.

4. **Create the Android OAuth client for the debug build**:
   - Click **Create credentials → OAuth client ID** again.
   - Application type: **Android**.
   - Package name: `com.konarsubhojit.synckro.debug`
     (the `.debug` suffix comes from the `applicationIdSuffix` in the debug
     build type).
   - SHA-1 certificate fingerprint: paste the SHA-1 from Step 1.
   - Click **Create**.

   > You do **not** need to copy the Client ID for the Android entry; Google
   > matches it automatically by package name + SHA-1.

5. Make sure the **Google Drive API** (and, optionally, the
   **People API**) is enabled for the project:
   **APIs & Services → Library → search "Google Drive API" → Enable**.

---

## Step 3 — Register the Android app in Microsoft Entra (Azure AD)

These steps configure MSAL so it accepts a login from the CI-built debug APK.

1. Open [Microsoft Entra admin center](https://entra.microsoft.com/) and sign
   in with your Microsoft account.

2. Go to **App registrations → New registration**.
   - Name: `Synckro debug` (or any name you prefer).
   - Supported account types: **Accounts in any organizational directory and
     personal Microsoft accounts** (the "common" endpoint).
   - Redirect URI: leave blank for now — you will add it next.
   - Click **Register**.

3. Copy the **Application (client) ID** shown on the overview page.
   This is your `MS_CLIENT_ID` secret.

4. Add the Android platform redirect URI:
   - In the app registration, go to **Authentication → Add a platform →
     Android**.
   - Package name: `com.konarsubhojit.synckro.debug`
   - Signature hash: paste the **Base64-encoded SHA-1** hash.
     To convert the colon-separated SHA-1 fingerprint from Step 1 to the
     format Azure expects:
     ```bash
     # Replace AA:BB:CC:… with your actual SHA-1 fingerprint
     echo "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD" \
       | xxd -r -p | base64
     ```
   - Click **Configure**.
   - Azure shows a **redirect URI** of the form:
     ```
     msauth://com.konarsubhojit.synckro.debug/<base64-hash>
     ```
   - Copy this **entire URI verbatim** — this is your `MSAL_REDIRECT_URI`
     secret.

> The redirect URI generated by Azure is URL-encoded.  Copy it exactly as
> shown; do not decode or modify it.

---

## Step 4 — (Optional) Configure Microsoft Graph permissions

If you need OneDrive file access in addition to sign-in:

1. In the app registration, go to **API permissions → Add a permission →
   Microsoft Graph → Delegated permissions**.
2. Add `Files.ReadWrite` (or `Files.ReadWrite.All` if you need access outside
   the app folder).
3. Click **Add permissions**.
4. You do **not** need admin consent for delegated permissions on personal
   accounts.

---

## Step 5 — Add the secrets to GitHub Actions

Go to your repository on GitHub: **Settings → Secrets and variables →
Actions → New repository secret**.

Add each secret listed in the table below.

| Secret name               | Value                                                                 |
|---------------------------|-----------------------------------------------------------------------|
| `GOOGLE_WEB_CLIENT_ID`    | The Web OAuth Client ID from Step 2 (e.g. `123….apps.googleusercontent.com`). |
| `MS_CLIENT_ID`            | The Application (client) ID from Step 3.                             |
| `MSAL_REDIRECT_URI`       | The full redirect URI from Step 3 (e.g. `msauth://com.konarsubhojit.synckro.debug/<hash>`). |
| `DEBUG_KEYSTORE_BASE64`   | The base64-encoded keystore from Step 1.                             |
| `DEBUG_KEYSTORE_PASSWORD` | The keystore store password (e.g. `android`).                        |
| `DEBUG_KEY_ALIAS`         | The key alias (e.g. `androiddebugkey`).                              |
| `DEBUG_KEY_PASSWORD`      | The key password (e.g. `android`; may be the same as the store password). |

> All seven secrets are independent — you can add them in any order.  Secrets
> that are missing or empty cause the corresponding feature to silently degrade:
> - Missing keystore secrets → CI falls back to AGP's auto-generated debug
>   keystore (auth will fail for the reasons described at the top of this doc).
> - Missing `MSAL_REDIRECT_URI` → the MSAL intent filter gets an empty host
>   and is effectively disabled; sign-in callbacks will not be routed back to
>   the app.
> - Missing `GOOGLE_WEB_CLIENT_ID` / `MS_CLIENT_ID` → `BuildConfig` fields
>   are empty strings; the auth flow fails with a configuration error at
>   runtime.

---

## Step 6 — Verify

After adding all secrets, trigger a new workflow run:

1. Go to **Actions → Android CI → Run workflow**.
2. Wait for the run to succeed.
3. Download the **`synckro-debug-apk-<run_number>`** artifact from the run
   summary.
4. Install the APK on a device or emulator:
   ```bash
   adb install app-debug.apk
   ```
5. Open the app and attempt to sign in with Google and/or Microsoft.
   Both should complete successfully without certificate errors.

---

## Local development

You do not need to configure any secrets just to build locally.  If you want to
test authentication on a locally built APK:

1. Place the `debug.keystore` file created in Step 1 in the root of the repo
   (next to `gradlew`).
2. Add the following entries to `local.properties` (which is `.gitignore`-d):
   ```properties
   GOOGLE_WEB_CLIENT_ID=<your web client id>
   MS_CLIENT_ID=<your azure app client id>
   MSAL_REDIRECT_URI=msauth://com.konarsubhojit.synckro.debug/<base64-hash>
   DEBUG_KEYSTORE_PASSWORD=android
   DEBUG_KEY_ALIAS=androiddebugkey
   DEBUG_KEY_PASSWORD=android
   ```
   `DEBUG_KEYSTORE_PATH` defaults to `<repo-root>/debug.keystore` when unset,
   so you only need it if the keystore is stored elsewhere.

3. Build and install:
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
