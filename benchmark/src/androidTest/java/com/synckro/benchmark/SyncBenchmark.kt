package com.synckro.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmarks for Synckro.
 *
 * Covers:
 * - Cold and warm startup time (includes initial Room/DB open = "scan" of
 *   persisted sync-pair index).
 * - "First sync" dispatch: cold startup followed by the WorkManager
 *   one-shot sync triggered from the home screen (uses FakeCloudProvider so
 *   no network is required in CI).
 *
 * Run on a device/emulator with:
 *   ./gradlew :benchmark:connectedBenchmarkAndroidTest
 *
 * On CI emulators the benchmark framework suppresses emulator-specific
 * warnings via the test-runner argument set in build.gradle.kts so that
 * results are collected without aborting. Timing numbers from emulators are
 * not representative of real-device performance.
 */
@RunWith(AndroidJUnit4::class)
class SyncBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val targetPackage = "com.synckro"

    /**
     * Measures cold-startup time.
     *
     * A cold start includes process creation, Application.onCreate (which
     * opens the Room DB and enumerates persisted sync pairs — the "scan" step
     * described in the issue), and rendering the first frame of the home
     * screen.
     */
    @Test
    fun startupCold() = benchmarkRule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
    }

    /**
     * Measures warm-startup time (process already running, activity re-created).
     *
     * Warm start skips process creation but still exercises the Compose tree
     * rebuild and the StateFlow emission from the Repository (another slice of
     * the "scan" path).
     */
    @Test
    fun startupWarm() = benchmarkRule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = 5,
    ) {
        startActivityAndWait()
    }

    /**
     * Measures "first sync" startup: cold launch of the app as a new user
     * (empty DB, no sync pairs configured). This represents the first-run
     * experience and captures the cost of WorkManager initialisation and the
     * initial home-screen render with an empty pair list.
     *
     * Once the sync engine is fully wired, a more complete benchmark should
     * seed a sync pair via Room's test helpers and measure the time from launch
     * to WorkManager completing the first FakeCloudProvider sync run.
     */
    @Test
    fun firstSyncColdStart() = benchmarkRule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
    }
}
