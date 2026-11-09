plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    // Common atomics for the mux and the connection loop, which are touched from OkHttp's socket
    // threads and from coroutines at once. The plugin rewrites `atomic(...)` into plain field
    // updaters on the JVM (no wrapper objects) and into Kotlin/Native atomic intrinsics on iOS.
    alias(libs.plugins.kotlinx.atomicfu)
}

// Kotlin/Native runs `:core:iosSimulatorArm64Test` in an iOS simulator. The default device name is one
// a recent Xcode no longer ships, so CI passes whichever iPhone the runner has
// (`-Pdsh.iosSimulator=<name or UDID>`; `xcrun simctl list devices available` shows what exists).
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    providers.gradleProperty("dsh.iosSimulator").orNull?.let { device.set(it) }
}

kotlin {
    // The JVM target is what the Android app, the conformance suite and local tooling consume
    // today: the exact same classes, in the same packages, that `kotlin("jvm")` used to build.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // The iOS targets exist to keep the shared code honestly shared. They compile `commonMain`
    // plus `iosMain`, which holds only the small `actual`s that common code's `expect`s need —
    // every JVM-only dependency lives in `jvmMain` — so a file that quietly grows a `java.*`
    // import fails the iOS compile instead of waiting for the real iOS app to find it.
    // Kotlin/Native builds Apple targets on macOS only: on Linux and Windows Gradle disables them
    // and every iOS task is SKIPPED, which is why CI's `ios-compile` job runs on a macOS runner.
    // Assembling an actual .ipa also needs Xcode, which is a later milestone of the iOS port.
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // `api`, not `implementation`: `RpcTransport` exposes kotlinx-io's `Source` and
            // `IOException` in its signatures, so every module that implements or calls it (the
            // app, the conformance suite) has to see them on its own compile classpath.
            api(libs.kotlinx.io.core)
            // The multiplatform HTTP and WebSocket client behind `KtorRpcTransport` and
            // `KtorMuxSocket`. `implementation`: callers hand those classes an `HttpClient`, so a
            // module that builds one declares Ktor itself. The Android app does not use these
            // classes at all and keeps Ktor out of its APK; see `app/build.gradle.kts`.
            implementation(libs.ktor.client.core)
        }
        iosMain.dependencies {
            // NSURLSession, through Ktor: the iOS engine. Its own platform libraries come from the
            // Kotlin/Native toolchain, so this only resolves (and compiles) on a macOS host.
            implementation(libs.ktor.client.darwin)
        }
        jvmMain.dependencies {
            // The HTTP/WebSocket transport and the commonmark Markdown renderer are JVM-only and
            // deliberately kept out of common code: the shared logic sees them only through the
            // `RpcTransport` interface. The iOS transport implements that interface with Ktor.
            implementation(libs.okhttp)
            implementation(libs.commonmark)
            implementation(libs.commonmark.ext.gfm.tables)
            implementation(libs.commonmark.ext.gfm.strikethrough)
            implementation(libs.commonmark.ext.autolink)
            implementation(libs.commonmark.ext.task.list.items)
            implementation(libs.commonmark.ext.heading.anchor)
        }
        // `HarnessContractTest` runs on the JVM and on an iOS simulator, against the same fixture
        // harnesses (`CiFixtures` in `:mock-harness`).
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            // The Ktor transport is common code, so it can only run on the JVM through a JVM
            // engine. CIO is test-scoped on purpose rather than a `jvmMain` dependency: the app
            // never builds a Ktor client, and a production class whose only caller is a test would
            // be dead weight in the shipped APK. The iOS build uses Darwin instead.
            implementation(libs.ktor.client.cio)
            // The transport contract tests drive both transports against the same real harness
            // stand-in, and the ping test needs a server that sends RFC 6455 pings.
            implementation(project(":mock-harness"))
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.netty)
            implementation(libs.ktor.server.websockets)
        }
    }
}
