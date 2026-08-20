plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * The release version, taken from the git tag the release workflow is building.
 *
 * `DSH_VERSION_NAME` is the tag without its leading `v`. Hardcoding it here meant every tag after
 * the first shipped an APK still claiming to be the first — same `versionCode`, so Android saw no
 * upgrade at all. The code is derived from the name so it rises with semver on its own; the
 * fallback is what a local `assembleRelease` builds.
 */
val dshVersionName: String = System.getenv("DSH_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.5.0"

val dshVersionCode: Int = dshVersionName
    .substringBefore('-')
    .split('.')
    .mapNotNull { it.toIntOrNull() }
    .let { parts ->
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        major * 10_000 + minor * 100 + patch
    }
    .coerceAtLeast(1)

android {
    namespace = "com.labteto.dshmobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.labteto.dshmobile"
        minSdk = 26
        targetSdk = 35
        versionCode = dshVersionCode
        versionName = dshVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Optional release signing: provide DSH_KEYSTORE / DSH_KEYSTORE_PASSWORD /
    // DSH_KEY_ALIAS / DSH_KEY_PASSWORD (env vars, e.g. from GitHub secrets).
    // Signing activates only when the keystore file actually exists, so a
    // missing keystore silently falls back to an unsigned release APK.
    signingConfigs {
        val keystore = System.getenv("DSH_KEYSTORE")
        if (!keystore.isNullOrBlank() && file(keystore).exists()) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("DSH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DSH_KEY_ALIAS")
                keyPassword = System.getenv("DSH_KEY_PASSWORD")
            }
            buildTypes.getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // The 11-language claim is only true while every base string has a translation, and the
        // gap is invisible in review — this is the check that actually enforces it, so it is
        // pinned rather than left to the default severity.
        error += listOf("MissingTranslation", "ImpliedQuantity")
        // `HardcodedText` is deliberately absent: it only inspects XML layouts, and this app has
        // none. Compose string literals have to be caught in review.
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The mock harness carries this project's port of the host's answer-acceptance law. The
    // conformance test runs the real encoder through it rather than through a copy, because a copy
    // is a second thing to keep in step and the failure it guards against is a silent one.
    testImplementation(project(":mock-harness"))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
