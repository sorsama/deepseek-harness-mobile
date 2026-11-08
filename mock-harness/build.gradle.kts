plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // For `installDist`: the iOS simulator job starts the mock harness as its own process.
    application
}

application {
    mainClass.set("com.labteto.dshmobile.mockharness.MainKt")
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

dependencies {
    // The mock harness is intentionally standalone: it speaks the DSH wire protocol
    // with raw JsonElement values and must not depend on :core's DTOs.
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
