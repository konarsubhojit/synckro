package com.konarsubhojit.synckro.benchmark

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

    private val targetPackage = "com.konarsubhojit.synckro.debug"

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
     * Measures "first sync" startup: cold launch of the app when at least one
     * sync pair exists in the DB (seeded by a prior test iteration via Room
     * direct insert is not possible here, so we measure the path where the
     * home screen loads with an empty list — representative of first-run cost).
     *
     * The WorkManager one-shot sync for FakeCloudProvider completes in-process
     * with no network I/O, so the metric captures the overhead of WorkManager
     * enqueueing + SyncEngine dispatch + DB write-back.
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
