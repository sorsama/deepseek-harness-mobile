plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/**
 * Conformance against a real harness.
 *
 * Its own module rather than a source set in `:core`, for two reasons. `:core:jvmTest` is hermetic and
 * fast — no sockets, no subprocesses — and it should stay that way, because it is what runs on every
 * push. And these tests need only `:core` plus OkHttp and a process, so putting them in `:app` would
 * drag the whole Android toolchain into a suite that never touches Android.
 *
 * Every test here skips unless it can find a harness checkout; see `HarnessProcess`. That is
 * deliberate: CI has no checkout, and a suite that cannot run is not the same fact as a client that
 * is wrong. Run it locally with `DSH_HARNESS_SRC=<checkout> ./gradlew :conformance:test`.
 */
dependencies {
    testImplementation(project(":core"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.okhttp)
    // The Ktor transport and the JVM engine it runs on here; see `TransportChoice`.
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // Which implementation of the transport seam drives the harness: `okhttp` (the default, what
    // Android ships) or `ktor` (what iOS ships). It is a declared input, so a Ktor run after an
    // OkHttp run is a fresh run and never an up-to-date or build-cached result of the other one.
    val transport = providers.gradleProperty("dsh.transport").orElse("okhttp").get()
    inputs.property("transport", transport)
    systemProperty("dsh.transport", transport)
    inputs.property("harnessSource", System.getenv("DSH_HARNESS_SRC") ?: "auto")
    inputs.property("legacyConformance", System.getenv("DSH_LEGACY_CONFORMANCE") ?: "false")
    // A real harness boot plus a model turn is slower than a unit test, and the output of a failure
    // is the useful part.
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
