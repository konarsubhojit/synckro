import java.util.Properties

val localProps =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

fun secretOrEmpty(key: String): String = (System.getenv(key) ?: localProps.getProperty(key) ?: "").trim()

data class MsalBuildConfig(
    val clientId: String,
    val redirectUri: String,
    val host: String,
    val path: String,
)

val msalConfigByBuildType = mutableMapOf<String, MsalBuildConfig>()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.synckro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.synckro"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"\"")
        buildConfigField("String", "MS_CLIENT_ID", "\"\"")
        buildConfigField("String", "MSAL_REDIRECT_URI", "\"\"")
        manifestPlaceholders["msalHost"] = ""
        manifestPlaceholders["msalPath"] = "/"
    }

    signingConfigs {
        create("debugPinned") {
            val ksPath =
                secretOrEmpty("DEBUG_KEYSTORE_PATH")
                    .ifEmpty { rootProject.file("debug.keystore").absolutePath }
            val ksFile = file(ksPath)
            if (ksFile.exists() &&
                secretOrEmpty("DEBUG_KEYSTORE_PASSWORD").isNotEmpty()
            ) {
                storeFile = ksFile
                storePassword = secretOrEmpty("DEBUG_KEYSTORE_PASSWORD")
                keyAlias =
                    secretOrEmpty("DEBUG_KEY_ALIAS")
                        .ifEmpty { "androiddebugkey" }
                keyPassword =
                    secretOrEmpty("DEBUG_KEY_PASSWORD")
                        .ifEmpty { secretOrEmpty("DEBUG_KEYSTORE_PASSWORD") }
            }
        }
    }

    val googleWebClientId = secretOrEmpty("GOOGLE_WEB_CLIENT_ID")
    val msClientId = secretOrEmpty("MS_CLIENT_ID")
    val msalRedirect = secretOrEmpty("MSAL_REDIRECT_URI")

    fun configuredMsalForBuildType(
        buildTypeName: String,
        expectedHost: String,
        legacyHost: String? = null,
    ): MsalBuildConfig {
        // Allow callers to pass a build-type-specific redirect URI so that one
        // build type's validation does not reject another build type's host
        // when Gradle configures every build type up front. Falls back to the
        // shared `MSAL_REDIRECT_URI` so single-secret setups keep working.
        val perBuildTypeRedirect = secretOrEmpty("MSAL_REDIRECT_URI_${buildTypeName.uppercase()}")
        val effectiveMsalRedirect = perBuildTypeRedirect.ifEmpty { msalRedirect }
        val msClientIdEmpty = msClientId.isEmpty()
        val msalRedirectEmpty = effectiveMsalRedirect.isEmpty()
        when {
            msClientIdEmpty && msalRedirectEmpty -> {
                println(
                    "WARNING: MS_CLIENT_ID and MSAL_REDIRECT_URI are both unset for '$buildTypeName'. " +
                        "OneDrive sign-in will be disabled at runtime. " +
                        "See docs/login-setup.md to enable it.",
                )
                return MsalBuildConfig(clientId = "", redirectUri = "", host = "", path = "/")
            }
            msClientIdEmpty -> {
                error(
                    "MS_CLIENT_ID is not set but MSAL_REDIRECT_URI is for '$buildTypeName'. " +
                        "Both must be provided together. See docs/login-setup.md.",
                )
            }
            msalRedirectEmpty -> {
                error(
                    "MSAL_REDIRECT_URI is not set but MS_CLIENT_ID is for '$buildTypeName'. " +
                        "Both must be provided together. See docs/login-setup.md.",
                )
            }
        }

        check(effectiveMsalRedirect.startsWith("msauth://")) {
            "MSAL_REDIRECT_URI must start with 'msauth://'. " +
                "Got: '$effectiveMsalRedirect'. See docs/login-setup.md."
        }
        val redirectSansScheme = effectiveMsalRedirect.removePrefix("msauth://")
        val host = redirectSansScheme.substringBefore("/", "")
        val pathWithoutSlash = redirectSansScheme.substringAfter("/", "")
        check(host.isNotEmpty()) {
            "MSAL_REDIRECT_URI has no host component. " +
                "Expected 'msauth://<applicationId>/<hash>'. See docs/login-setup.md."
        }
        check(pathWithoutSlash.isNotEmpty()) {
            "MSAL_REDIRECT_URI has no path component after the host. " +
                "Expected 'msauth://<applicationId>/<hash>'. See docs/login-setup.md."
        }

        val allowedHosts =
            buildList {
                add(expectedHost)
                legacyHost?.let { add(it) }
            }
        check(allowedHosts.contains(host)) {
            val expectedText =
                if (legacyHost == null) {
                    "'$expectedHost' ($buildTypeName applicationId)"
                } else {
                    "'$expectedHost' ($buildTypeName applicationId) or the legacy '$legacyHost'"
                }
            "MSAL_REDIRECT_URI host '$host' must equal $expectedText. " +
                "See docs/login-setup.md."
        }
        if (legacyHost != null && host == legacyHost) {
            println(
                "WARNING: MSAL_REDIRECT_URI uses legacy host '$legacyHost'. " +
                    "Update CI/local secrets to '$expectedHost' to match the renamed debug applicationId.",
            )
        }

        return MsalBuildConfig(
            clientId = msClientId,
            redirectUri = effectiveMsalRedirect,
            host = host,
            path = "/$pathWithoutSlash",
        )
    }

    fun com.android.build.api.dsl.BuildType.configureAuthForBuildType(
        expectedHost: String,
        legacyHost: String? = null,
    ) {
        val msalConfig =
            configuredMsalForBuildType(
                buildTypeName = name,
                expectedHost = expectedHost,
                legacyHost = legacyHost,
            )

        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            "\"$googleWebClientId\"",
        )
        buildConfigField("String", "MS_CLIENT_ID", "\"${msalConfig.clientId}\"")
        buildConfigField("String", "MSAL_REDIRECT_URI", "\"${msalConfig.redirectUri}\"")
        manifestPlaceholders["msalHost"] = msalConfig.host
        manifestPlaceholders["msalPath"] = msalConfig.path

        msalConfigByBuildType[name] = msalConfig
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val pinned = signingConfigs.getByName("debugPinned")
            if (pinned.storeFile != null) {
                signingConfig = pinned
            } else {
                println(
                    "WARNING: debugPinned signing config is not fully configured. " +
                        "Release APK will use default unsigned output locally. " +
                        "Set DEBUG_KEYSTORE_* values to build a signed testing release APK.",
                )
            }
            configureAuthForBuildType(expectedHost = "com.synckro")
        }
        // Benchmark build type: inherits release settings (no debug overhead) but disables
        // R8 minification and resource shrinking so the build completes cleanly on CI.
        // The :benchmark module's own "benchmark" build type matches this by name, so no
        // matchingFallbacks override is needed in benchmark/build.gradle.kts.
        create("benchmark") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
            val pinned = signingConfigs.getByName("debugPinned")
            if (pinned.storeFile != null) {
                signingConfig = pinned
            }
            // else: AGP falls back to the default auto-generated debug keystore.
            configureAuthForBuildType(
                expectedHost = "com.synckro.debug",
                legacyHost = "com.konarsubhojit.synckro.debug",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes +=
            setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    lint {
        // Treat lint errors as build failures. New issues not captured in the
        // baseline below will break the build so regressions are caught in CI.
        abortOnError = true
        warningsAsErrors = false
        // Baseline captures pre-existing issues; only NEW problems fail the build.
        // Regenerate with: ./gradlew lintDebug -Dlint.baselines.continue=true
        baseline = file("lint-baseline.xml")
    }
}

/**
 * Generates `msal_config.json` into the build directory so the MSAL client ID
 * and redirect URI are never committed to source control.
 *
 * Declared as a typed task so that [addGeneratedSourceDirectory] can wire it
 * into every variant's res source set via the stable AGP variant API.
 */
abstract class GenerateMsalConfigTask : DefaultTask() {
    @get:Input abstract val clientId: Property<String>

    @get:Input abstract val redirect: Property<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun execute() {
        fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
        val json =
            """
            {
              "client_id": "${clientId.get().esc()}",
              "authorization_user_agent": "DEFAULT",
              "redirect_uri": "${redirect.get().esc()}",
              "account_mode": "MULTIPLE",
              "broker_redirect_uri_registered": false,
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
            """.trimIndent()
        val raw = outputDir.get().asFile.resolve("raw")
        raw.mkdirs()
        raw.resolve("msal_config.json").writeText(json)
    }
}

androidComponents {
    onVariants { variant ->
        val buildTypeName = variant.buildType ?: return@onVariants
        val msalConfig = msalConfigByBuildType[buildTypeName] ?: MsalBuildConfig("", "", "", "/")
        val variantCap =
            variant.name.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        val generateMsalConfigForVariant =
            tasks.register("generate${variantCap}MsalConfig", GenerateMsalConfigTask::class) {
                clientId.set(msalConfig.clientId)
                redirect.set(msalConfig.redirectUri)
                outputDir.set(layout.buildDirectory.dir("generated/res/msal/${variant.name}"))
            }
        // Use the typed variant API to add the generated dir as a res source and
        // automatically establish the task dependency — no brittle task-name
        // string matching required.
        variant.sources.res?.addGeneratedSourceDirectory(
            generateMsalConfigForVariant,
            GenerateMsalConfigTask::outputDir,
        )
    }
}

// Export Room schemas to a tracked folder so migrations can be reviewed in PRs.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core / Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.material)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Async / serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Storage
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.security.crypto)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // WorkManager + Hilt
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Networking (for future OneDrive / Google Drive REST clients)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    // Cloud authentication
    implementation(libs.msal) {
        // Exclude Surface Duo dependency that is not in Maven Central
        exclude(group = "com.microsoft.device.display", module = "display-mask")
    }
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)
    implementation(libs.spotbugs.annotations)

    // Logging
    implementation(libs.timber)

    // Macrobenchmark: baseline-profile precompilation support.
    implementation(libs.profileinstaller)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)

    // Instrumented tests
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
