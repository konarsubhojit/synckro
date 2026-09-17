plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

// Platform-free sync domain: no Android dependencies are allowed here so the
// diffing/scoping logic stays unit-testable on the JVM.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
