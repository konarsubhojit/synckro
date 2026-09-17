import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    jacoco
}

// Platform-free sync domain: no Android dependencies are allowed here so the
// diffing/scoping logic stays unit-testable on the JVM.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
}

tasks.register<JacocoCoverageVerification>("verifyUnitTestCoverage") {
    group = "verification"
    description = "Verifies :core:domain unit-test line coverage is at least 45%."
    dependsOn(tasks.named("test"))
    executionData.from(layout.buildDirectory.file("jacoco/test.exec"))
    classDirectories.from(layout.buildDirectory.dir("classes/kotlin/main"))
    sourceDirectories.from(files("src/main/java"))

    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.45".toBigDecimal()
            }
        }
    }
}
