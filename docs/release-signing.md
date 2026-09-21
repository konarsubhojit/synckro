# Release signing

Production releases are built by `.github/workflows/android-release.yml` when a
`v*` tag is pushed, or when the workflow is manually dispatched for an existing
`v*` tag. The workflow runs on the self-hosted Android builder, creates both
`app-release.aab` and `app-release.apk`, verifies the APK signature with
`apksigner`, and publishes a GitHub Release using the matching `CHANGELOG.md`
entry as release notes.

## Generate the release keystore

Create the keystore on a trusted local machine, not on the CI runner:

```bash
keytool -genkeypair \
  -v \
  -keystore synckro-release.p12 \
  -storetype PKCS12 \
  -alias synckro-release \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=Synckro Release, OU=Android, O=Your Organization, L=City, ST=State, C=US"
```

Use a non-debug alias such as `synckro-release`, a strong keystore password, and
a strong key password. The key password may be the same as the keystore password;
if so, `RELEASE_KEY_PASSWORD` can be omitted because Gradle falls back to
`RELEASE_KEYSTORE_PASSWORD`.

Choose a `-dname` that identifies the release key owner. Replace the example
organization and location values with the maintainer or organization details
that should appear in the signing certificate.

> **Back up the keystore.** Android signing keys are unrecoverable. If this file
> or its passwords are lost, future updates signed with the same key cannot be
> produced. Store an encrypted backup off-machine and keep the passwords in a
> separate password manager or secrets vault.

Record the public certificate fingerprints:

```bash
keytool -list -v -keystore synckro-release.p12 -alias synckro-release
```

## GitHub Actions secrets

Base64-encode the keystore without line wrapping:

```bash
base64 -w0 synckro-release.p12
```

Configure these repository secrets under
**Settings → Secrets and variables → Actions**:

| Secret | Required | Description |
|--------|----------|-------------|
| `RELEASE_KEYSTORE_BASE64` | Yes | Base64-encoded contents of `synckro-release.p12`. |
| `RELEASE_KEYSTORE_PASSWORD` | Yes | Password for the PKCS12 keystore. |
| `RELEASE_KEY_ALIAS` | Yes | Alias of the release key, for example `synckro-release`. |
| `RELEASE_KEY_PASSWORD` | No | Password for the key entry. Omit only when it is the same as `RELEASE_KEYSTORE_PASSWORD`. |

The Gradle build keeps a path-based contract: it reads
`RELEASE_KEYSTORE_PATH`, not the base64 secret. The release workflow decodes
`RELEASE_KEYSTORE_BASE64` into `$RUNNER_TEMP/release.keystore`, verifies that the
decoded keystore can be opened with `RELEASE_KEYSTORE_PASSWORD` /
`RELEASE_KEY_ALIAS` and that the private key can be recovered with the effective
key password, and only then exports `RELEASE_KEYSTORE_PATH` for
`bundleRelease assembleRelease`.

During this preflight, the workflow writes a notice with the decoded keystore's
byte count and SHA-256. These values help confirm that Actions received the
expected password-protected file without exposing its contents or passwords.

## Troubleshooting

If the `Decode and verify release keystore` preflight step reports an
`EOFException`, or if the release job fails in `:app:packageRelease` with

```
com.android.ide.common.signing.KeytoolException: Failed to read key *** from store "...": null
```

the decoded keystore is not readable at all — the `null` cause is an
`EOFException` raised while loading it. That means `RELEASE_KEYSTORE_BASE64`
holds something other than a complete keystore, most often because it was
truncated when pasted or because the value was base64-encoded twice. Re-create
the secret from the keystore file:

```bash
base64 -w0 synckro-release.p12 > synckro-release.p12.b64
```

then paste the whole contents of `synckro-release.p12.b64` (never the output of
`base64 -w0 synckro-release.p12.b64`) into the secret. The workflow tolerates
wrapped lines, but the value must decode to the keystore itself.

Compare the preflight notice's byte count and SHA-256 with the original local
keystore before updating the secret. An `Invalid keystore format` or
`DerInputStream` preflight error has the same truncated/corrupt-secret cause.

Verify the secret locally before updating it:

```bash
base64 -d synckro-release.p12.b64 > /tmp/release.keystore
keytool -list -keystore /tmp/release.keystore -alias synckro-release
```

When the key password differs from the keystore password, set
`RELEASE_KEY_PASSWORD`; otherwise the workflow's key-recovery check fails with
`Cannot recover key`.

## OAuth registrations for the release key

Debug and release builds have different package names and signing certificates,
so provider consoles need separate Android entries for the release key.

For Google Cloud, create a separate **Android** OAuth client:

- Package name: `com.synckro`
- SHA-1 certificate fingerprint: the SHA-1 from the release keystore

Keep using the existing web client ID in `GOOGLE_WEB_CLIENT_ID`; Google matches
the Android client automatically by package name and SHA-1 at runtime.

For Microsoft Entra, add a separate **Android** platform entry:

- Package name: `com.synckro`
- Signature hash: the base64-encoded SHA-1 of the release keystore

Convert the colon-separated SHA-1 fingerprint to Entra's base64 signature hash:

```bash
echo "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD" \
  | tr -d ':' | xxd -r -p | base64
```

The resulting redirect URI has the form
`msauth://com.synckro/<base64-hash>`. The workflow derives this release URI from
the existing `MSAL_REDIRECT_URI` secret so both build types can pass Gradle's
host validation. See **[login-setup.md](login-setup.md)** for the full Google
Drive and OneDrive setup.

## Self-hosted runner threat model

The signed release job runs on the persistent self-hosted runner documented in
**[ci-self-hosted-runner.md](ci-self-hosted-runner.md)**. That runner has access
to the production signing key, so the workflow is deliberately not triggerable by
pull requests or branch pushes. Only pushed `v*` tags and explicit manual
dispatches for `v*` tags can run it.

Because `actions/checkout` does not reliably remove every file that may be left
on a persistent machine, the workflow never decodes the keystore into the
workspace. It decodes only into `$RUNNER_TEMP` with `umask 077`, exports the
temporary path through `RELEASE_KEYSTORE_PATH`, never prints the keystore or any
password, and never uploads the keystore as an artifact.

A final `if: always()` cleanup step runs:

```bash
shred -u "$RUNNER_TEMP/release.keystore" 2>/dev/null || rm -f "$RUNNER_TEMP/release.keystore"
rm -f "$RUNNER_TEMP/keytool-verify.log"
```

This cleanup runs even when the build, verification, or release publishing step
fails, reducing the chance that the signing key is stranded on the runner.
