plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.synckro.benchmark"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        targetSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Suppress benchmark framework warnings that are expected on CI emulators:
        //   EMULATOR          – running on a virtual device (expected in CI)
        //   LOW-BATTERY       – emulator battery state is unknown / low
        //   NOT-PROFILEABLE   – profiling unavailable on emulator
        //   UNLOCKED          – device lock-screen state; emulators are always unlocked
        //   ACTIVITY-MISSING  – raised when the activity under test is not found via
        //                       the default intent (e.g. when the launcher has not yet
        //                       registered the activity after a fresh install on the emulator)
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] =
            "EMULATOR,LOW-BATTERY,NOT-PROFILEABLE,UNLOCKED,ACTIVITY-MISSING"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        // "benchmark" build type: debuggable so the test APK can attach to the
        // profiled process. Matches the "benchmark" build type in :app by name,
        // so no matchingFallbacks are required.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Points at the app module whose debug/benchmark variant will be profiled.
    targetProjectPath = ":app"

    // Self-instrumenting: the test APK instruments itself rather than a
    // separate target APK. Required for the `com.android.test` plugin.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

// Only enable the benchmark build type; skip debug to avoid redundant APKs.
androidComponents {
    beforeVariants(selector().all()) { builder ->
        builder.enable = builder.buildType == "benchmark"
    }
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.benchmark.macro.junit4)
}
