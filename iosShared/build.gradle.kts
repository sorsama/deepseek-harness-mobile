import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

/*
 * The iOS app's shared half: Compose Multiplatform UI over `:core`, built into one static framework,
 * `DshShared`, that the SwiftUI host in `iosApp/` links. It is the only module here that uses
 * Compose Multiplatform; the Android app keeps its own Jetpack Compose UI and does not depend on
 * this module, so nothing in it can change the APK.
 *
 * iOS only, on purpose: there is no Android or JVM target, so this module has no code the Android
 * build could pick up. `iosX64` (the Intel simulator) is left out because every macOS runner and
 * every Mac a person would build on today is Apple Silicon; `:core` still compiles it.
 *
 * Kotlin/Native builds Apple targets on macOS only. On Linux and Windows Gradle disables them and
 * their tasks are SKIPPED, so everything that proves this module (the framework link, the tests,
 * the .ipa) lives in the macOS `ios-app` job in `.github/workflows/ci.yml`.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            // `import DshShared` in the Swift host. Static, so there is nothing to embed or sign
            // inside the app bundle: an unsigned .ipa has one fewer thing that has to be re-signed.
            baseName = "DshShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            // `HttpClient` appears in this module's own signatures (the platform client seam), and
            // `:core` keeps Ktor on its implementation classpath.
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.resources {
    // Not public: nothing outside this module reads the generated `Res`.
    publicResClass = false
    packageOfResClass = "com.labteto.dshmobile.ios.resources"
    generateResClass = auto
}

// Kotlin/Native runs these tests in an iOS simulator. The default device name is one that a recent
// Xcode no longer ships, so CI passes whichever iPhone the runner has (`-Pdsh.iosSimulator=<name or
// UDID>`; `xcrun simctl list devices available` shows what exists).
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    providers.gradleProperty("dsh.iosSimulator").orNull?.let { device.set(it) }
}
