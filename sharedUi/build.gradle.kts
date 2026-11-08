import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

/*
 * The UI both apps share: Compose Multiplatform over `:core`, consumed by the Android app (`:app`)
 * as an ordinary library and by the iOS app as one static framework, `DshShared`, that the SwiftUI
 * host in `iosApp/` links.
 *
 * It started life as `:iosShared`, the iOS app's own module, and was renamed when the Android app
 * began to share its Compose code through it (iOS M4a). The rule it is built on: the Android app is
 * the product and must look and behave exactly as before, so a screen moves here from `:app` one at
 * a time, keeping its package, and `:app` keeps compiling against it. See "The shared UI" in
 * docs/IOS.md for the recipe.
 *
 * Source sets:
 *  - `commonMain` is the design system (theme, type, shapes, components), the screens that have
 *    moved, the state holders behind them and the small interfaces the platforms implement.
 *  - `androidMain` holds the Android halves of those interfaces, and nothing else the app does not
 *    also need; the Android app's other platform code stays in `:app`.
 *  - `iosMain` holds the iOS halves, the iOS app's wiring (networking, stores, haptics) and its
 *    entry point.
 *
 * No Ktor in `commonMain` or `androidMain`: Ktor is the iOS carrier, the Android app keeps OkHttp
 * and excludes Ktor from its APK (`app/build.gradle.kts`), and a class here that touched it would
 * crash the shipped app the first time it loaded. `SharedUiHasNoKtorOnTheAndroidPathTest` in `:app`
 * reads these sources and fails the build if one does.
 *
 * `-Pdsh.iosOnly=true` leaves the Android half out: a Mac with Xcode, a JDK and no Android SDK can
 * still build the iOS app, and configuring an Android library needs an SDK. Without the flag the
 * module is what it is on CI.
 *
 * Kotlin/Native builds Apple targets on macOS only. On Linux and Windows Gradle disables them and
 * their tasks are SKIPPED, so everything that proves the iOS half (the framework link, the tests,
 * the .ipa) lives in the macOS `ios-app` job in `.github/workflows/ci.yml`. `iosX64` (the Intel
 * simulator) is left out because every macOS runner and every Mac a person would build on today is
 * Apple Silicon; `:core` still compiles it.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

val iosOnly = providers.gradleProperty("dsh.iosOnly").isPresent
if (!iosOnly) {
    // Applied here, not in the `plugins` block, so the flag above can leave it out.
    apply(plugin = "com.android.library")
}

kotlin {
    if (!iosOnly) {
        androidTarget {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }
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
            // `api`: the screens' state and ports speak `:core`'s types (`HostDescription`,
            // `SessionExchange`, `TransportFailure`), so a module that implements a port sees them.
            api(project(":core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
        }
        iosMain.dependencies {
            // `HttpClient` appears in the iOS wiring's own signatures, and `:core` keeps Ktor on its
            // implementation classpath. iOS only: see the note above about the Android path.
            implementation(libs.ktor.client.core)
        }
        if (!iosOnly) {
            androidMain.dependencies {
                // BackHandler, for the Android half of PlatformBackHandler. The app resolves the same version.
                implementation(libs.androidx.activity.compose)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

if (!iosOnly) {
    extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
        namespace = "com.labteto.dshmobile.shared"
        compileSdk = 35
        defaultConfig {
            minSdk = 26
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}

compose.resources {
    // Not public: nothing outside this module reads the generated `Res`.
    publicResClass = false
    packageOfResClass = "com.labteto.dshmobile.shared.resources"
    generateResClass = auto
}

// Kotlin/Native runs these tests in an iOS simulator. The default device name is one that a recent
// Xcode no longer ships, so CI passes whichever iPhone the runner has (`-Pdsh.iosSimulator=<name or
// UDID>`; `xcrun simctl list devices available` shows what exists).
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    providers.gradleProperty("dsh.iosSimulator").orNull?.let { device.set(it) }
}
