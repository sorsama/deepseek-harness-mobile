plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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
    // only — every JVM-only dependency lives in `jvmMain` — so a file that quietly grows a
    // `java.*` import fails the iOS compile instead of waiting for the real iOS app to find it.
    // Kotlin/Native cross-compiles these from any host OS (CI does it on Linux); assembling an
    // actual .ipa still needs Xcode on macOS, which is a later milestone of the iOS port.
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
        }
        jvmMain.dependencies {
            // The HTTP/WebSocket transport and the commonmark Markdown renderer are JVM-only and
            // deliberately kept out of common code: the shared logic sees them only through the
            // `RpcTransport` interface. An iOS transport will implement that interface natively.
            implementation(libs.okhttp)
            implementation(libs.commonmark)
            implementation(libs.commonmark.ext.gfm.tables)
            implementation(libs.commonmark.ext.gfm.strikethrough)
            implementation(libs.commonmark.ext.autolink)
            implementation(libs.commonmark.ext.task.list.items)
            implementation(libs.commonmark.ext.heading.anchor)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
