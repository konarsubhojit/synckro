# Self-hosted CI runner

The Android CI workflow routes pushes to `master` and manual dispatches to the
self-hosted runner matched by `self-hosted` and `android-builder`. The runner
also has the `Linux` and `X64` labels. Pull request builds always run on
`ubuntu-latest`.

## Provisioning

Provision an Ubuntu 24.04 x86_64 host with at least 4 vCPUs, 9.7 GB RAM, and
4 GB swap. Install JDK 21 and make it available at
`/usr/lib/jvm/java-21-openjdk-amd64`:

```bash
sudo apt-get update
sudo apt-get install --yes openjdk-21-jdk
```

Install the Android command-line tools under `/opt/android-sdk`, then install
the required packages and accept their licences:

```bash
sudo mkdir -p /opt/android-sdk
sudo chown "$USER":"$USER" /opt/android-sdk
# Install Google's Android command-line tools in /opt/android-sdk/cmdline-tools/latest.
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0"
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk --licenses
```

Configure the runner user's `~/.gradle/gradle.properties` (not this
repository's committed `gradle.properties`) with:

```properties
org.gradle.jvmargs=-Xmx4g
org.gradle.workers.max=4
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
```

Register the GitHub Actions runner with the labels above. The workflow uses
the persistent SDK at `/opt/android-sdk` and persistent Gradle user home on
this runner; `actions/checkout` explicitly cleans the workspace before every
job without wiping `~/.gradle`.

## Security

This is a public repository, so a fork pull request could otherwise execute
arbitrary code on a sudo-capable self-hosted machine. For that reason, pull
requests deliberately remain on GitHub-hosted `ubuntu-latest` runners. Also
enable **Settings → Actions → General → Fork pull request workflows → Require
approval for all external contributors**.

The tag-driven signed release workflow also runs on this persistent runner
because it needs the production Android signing key. It is intentionally limited
to pushed `v*` tags and manual dispatch for an existing `v*` tag, never pull
requests or branch pushes. See **[release-signing.md](release-signing.md)** for
the keystore threat model, required `RELEASE_KEYSTORE_*` secrets, and cleanup
guardrails.

## Instrumented Android tests

Room migrations are covered by both a JVM test (`MigrationTest.kt`, runs under
`testDebugUnitTest` on every push/PR) and an on-device instrumented test
(`Migration11To12InstrumentedTest`, using `androidx.room.testing
.MigrationTestHelper` against a real `SupportSQLiteDatabase`). The
instrumented variant needs an Android device or emulator and cannot execute
under Robolectric/JVM unit tests.

Booting an emulator (even a GitHub-hosted one via
[`reactivecircus/android-emulator-runner`](https://github.com/ReactiveCircus/android-emulator-runner))
adds several minutes per job and is billed as extra GitHub Actions minutes.
Since `android-ci.yml` already runs on every push and PR, adding an emulator
boot there would make the main build lane slower. Instead,
`.github/workflows/android-instrumented-tests.yml` runs
`connectedDebugAndroidTest` (scoped to the migration test class via
`-Pandroid.testInstrumentationRunnerArguments.class=...`) as a separate CI
workflow on database/migration-related pushes and pull requests, a nightly
schedule, and `workflow_dispatch`, across API 26 (`minSdk`) and API 34
(`compileSdk`/`targetSdk`) emulator images. This keeps the main build job
focused while still requiring the real-database migration path to pass in CI.

## Operations

From the runner installation directory, manage the service with:

```bash
./svc.sh status
./svc.sh stop
./svc.sh start
```

Runner diagnostic logs are in `_diag/` below that directory. To update the
runner, stop the service, install the current runner release over the existing
installation following the GitHub Actions runner release instructions, then
start the service again. Keep automatic runner updates enabled unless an
operational requirement requires a controlled update process.
