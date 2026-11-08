pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dsh-mobile"

// `-Pdsh.iosOnly=true` leaves the Android app out of the build, so a Mac with Xcode and a JDK but no
// Android SDK can build the iOS app: configuring `:app` fails without an SDK. The iOS app's Xcode
// build phase passes it. Nothing else changes, and without the flag the project is what it was.
if (!providers.gradleProperty("dsh.iosOnly").isPresent) {
    include(":app")
}
include(":core")
include(":mock-harness")
include(":conformance")
include(":sharedUi")
