import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    jacoco
}

// Platform-free sync domain: no Android dependencies are allowed here so the
// diffing/scoping logic stays unit-testable on the JVM.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    finalizedBy(tasks.named("jacocoTestReport"))
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
