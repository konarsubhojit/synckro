import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secretOrEmpty(key: String): String =
    (System.getenv(key) ?: localProps.getProperty(key) ?: "").trim()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.konarsubhojit.synckro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.konarsubhojit.synckro"
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
            val ksPath = secretOrEmpty("DEBUG_KEYSTORE_PATH")
                .ifEmpty { rootProject.file("debug.keystore").absolutePath }
            val ksFile = file(ksPath)
            if (ksFile.exists()
                && secretOrEmpty("DEBUG_KEYSTORE_PASSWORD").isNotEmpty()
            ) {
                storeFile = ksFile
                storePassword = secretOrEmpty("DEBUG_KEYSTORE_PASSWORD")
                keyAlias = secretOrEmpty("DEBUG_KEY_ALIAS")
                    .ifEmpty { "androiddebugkey" }
                keyPassword = secretOrEmpty("DEBUG_KEY_PASSWORD")
                    .ifEmpty { secretOrEmpty("DEBUG_KEYSTORE_PASSWORD") }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            val pinned = signingConfigs.getByName("debugPinned")
            if (pinned.storeFile != null) {
                signingConfig = pinned
            }
            // else: AGP falls back to the default auto-generated debug keystore.
            buildConfigField(
                "String",
                "GOOGLE_WEB_CLIENT_ID",
                "\"${secretOrEmpty("GOOGLE_WEB_CLIENT_ID")}\""
            )
            buildConfigField(
                "String",
                "MS_CLIENT_ID",
                "\"${secretOrEmpty("MS_CLIENT_ID")}\""
            )
            val msalRedirect = secretOrEmpty("MSAL_REDIRECT_URI")
            val msalHost = msalRedirect
                .substringAfter("msauth://", "")
                .substringBefore("/", "")
            val msalPath = if (msalRedirect.isNotEmpty() && msalHost.isNotEmpty())
                "/" + msalRedirect.substringAfter("$msalHost/", "")
            else "/"

            // Sanity: if a redirect URI is provided, its host MUST match the debug
            // applicationId. A mismatch silently breaks MSAL at runtime.
            if (msalHost.isNotEmpty()) {
                check(msalHost == "com.konarsubhojit.synckro.debug") {
                    "MSAL_REDIRECT_URI host '$msalHost' must equal " +
                        "'com.konarsubhojit.synckro.debug' (debug applicationId)."
                }
            }

            buildConfigField(
                "String",
                "MSAL_REDIRECT_URI",
                "\"$msalRedirect\""
            )
            manifestPlaceholders["msalHost"] = msalHost
            manifestPlaceholders["msalPath"] = msalPath
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
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
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
        val json = """
        {
          "client_id": "${clientId.get().esc()}",
          "authorization_user_agent": "DEFAULT",
          "redirect_uri": "${redirect.get().esc()}",
          "account_mode": "SINGLE",
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

val generateMsalConfig by tasks.registering(GenerateMsalConfigTask::class) {
    clientId.set(secretOrEmpty("MS_CLIENT_ID"))
    redirect.set(secretOrEmpty("MSAL_REDIRECT_URI"))
    outputDir.set(layout.buildDirectory.dir("generated/res/msal"))
}

androidComponents {
    onVariants { variant ->
        // Use the typed variant API to add the generated dir as a res source and
        // automatically establish the task dependency — no brittle task-name
        // string matching required.
        variant.sources.res?.addGeneratedSourceDirectory(
            generateMsalConfig,
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

    // Logging
    implementation(libs.timber)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)

    // Instrumented tests
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
