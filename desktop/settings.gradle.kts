@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
    // Reuse the version catalog from android/. The included core module references
    // `libs.plugins.kotlin.jvm`, which only resolves if we re-publish the catalog
    // here under the same name.
    versionCatalogs {
        create("libs") {
            from(files("../android/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "TimeTwisterDesktop"
include(":app", ":core")

// Reuse the cross-platform parser/converter from android/core verbatim. This is the
// "share core, swap shell" pattern from the README — one Kotlin source-of-truth for
// the time logic, two thin shells (Android activity, desktop tray app).
project(":core").projectDir = file("../android/core")
project(":app").projectDir = file("app")
