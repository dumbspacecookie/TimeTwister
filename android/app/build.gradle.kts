import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release-signing creds. Read first from app/keystore.properties (gitignored),
// falling back to env vars so CI can sign without committing anything. If neither
// is present the release build is left unsigned — useful for assembleRelease smoke
// checks without forcing every contributor to generate a keystore.
val keystoreProps = Properties().apply {
    val f = rootProject.file("app/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// NOTE the `takeIf { it.isNotBlank() }`: in GitHub Actions an *unset* secret expands
// to an empty string rather than being absent, so `System.getenv(...)` returns ""
// instead of null. Without the blank check the build would decide signing is
// available, then create a signing config with storeFile = file("") and fail during
// packaging — i.e. the documented "no keystore → build unsigned" fallback worked
// locally but was broken in CI.
fun signingProp(key: String, env: String): String? =
    (keystoreProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val releaseStoreFilePath = signingProp("storeFile", "TIMETWISTER_KEYSTORE_PATH")
val releaseStorePassword = signingProp("storePassword", "TIMETWISTER_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProp("keyAlias", "TIMETWISTER_KEY_ALIAS")
val releaseKeyPassword = signingProp("keyPassword", "TIMETWISTER_KEY_PASSWORD")
val releaseSigningAvailable = releaseStoreFilePath != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

// Version. Play rejects an upload whose versionCode it has already seen, and two
// GitHub Releases built from different tags with the same versionCode/versionName
// are indistinguishable once downloaded. So CI derives both from the git tag and
// passes them in as Gradle properties; local builds fall back to the dev defaults.
//   ./gradlew :app:assembleRelease -Ptimetwister.versionCode=201 -Ptimetwister.versionName=0.2.1
val appVersionCode = (findProperty("timetwister.versionCode") as String?)
    ?.trim()?.toIntOrNull() ?: 1
val appVersionName = (findProperty("timetwister.versionName") as String?)
    ?.trim()?.takeIf { it.isNotEmpty() } ?: "0.1.0"

android {
    namespace = "com.timetwister.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.timetwister.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(17)
}

// ---------------------------------------------------------------------------------------
// Unit-test floor.
//
// CI runs :app:testDebugUnitTest. Until 2026-07-26 there was no test source set here at
// all, so that step went green in about a second while verifying nothing — the same
// vacuous-pass failure the core's eval gate was hardened against, one module over.
//
// This needs TWO checks, which the first attempt at it got wrong: with no test sources
// Gradle marks the Test task NO-SOURCE and *skips* it, so anything hung off the task
// itself — doLast, a test listener — never runs. A floor that lives inside the task
// cannot fire in the exact situation it exists for. Verified by hiding src/test: the
// listener-only version reported BUILD SUCCESSFUL in 1s.
//
//   1. taskGraph — the source set must contain test files, checked before execution and
//      therefore independent of whether the task runs. Catches "the suite is gone".
//   2. listener  — when the task does run, it must execute at least MIN_APP_UNIT_TESTS.
//      Catches "the suite shrank" and "a filter quietly excluded everything".
// ---------------------------------------------------------------------------------------

// Raise this with the suite. Lowering it is allowed but must be deliberate and in the
// same commit as the removal, which is the whole point of it being a checked-in number.
val MIN_APP_UNIT_TESTS = 28

// Escape hatch for `--tests "*Something*"` during development, where a partial run is the
// intent: -Ptimetwister.allowPartialTestRun=1. Deliberately a property rather than
// sniffing Gradle's filter internals, so it cannot silently start passing after an upgrade.
val allowPartialTestRun = (findProperty("timetwister.allowPartialTestRun") as String?)
    ?.trim().let { it == "1" || it == "true" }

val unitTestSourceDir = layout.projectDirectory.dir("src/test")

gradle.taskGraph.whenReady {
    val runsUnitTests = allTasks.any { it.project == project && it is Test }
    if (!runsUnitTests || allowPartialTestRun) return@whenReady

    val testFiles = unitTestSourceDir.asFile
        .walkTopDown()
        .filter { it.isFile && it.name.endsWith("Test.kt") }
        .toList()
    if (testFiles.isEmpty()) {
        throw GradleException(
            "No unit tests found under ${unitTestSourceDir.asFile}. A Test task with no " +
                "sources is silently SKIPPED, so this would otherwise report success " +
                "while checking nothing, which is precisely what :app did before " +
                "2026-07-26. Restore the tests, or pass " +
                "-Ptimetwister.allowPartialTestRun=1 if you really mean it.",
        )
    }
}

tasks.withType<Test>().configureEach {
    var executed = 0
    addTestListener(object : org.gradle.api.tasks.testing.TestListener {
        override fun beforeSuite(suite: org.gradle.api.tasks.testing.TestDescriptor) = Unit
        override fun afterSuite(
            suite: org.gradle.api.tasks.testing.TestDescriptor,
            result: org.gradle.api.tasks.testing.TestResult,
        ) = Unit
        override fun beforeTest(test: org.gradle.api.tasks.testing.TestDescriptor) = Unit
        override fun afterTest(
            test: org.gradle.api.tasks.testing.TestDescriptor,
            result: org.gradle.api.tasks.testing.TestResult,
        ) { executed++ }
    })
    doLast {
        if (!allowPartialTestRun && executed < MIN_APP_UNIT_TESTS) {
            throw GradleException(
                "$name executed $executed tests, expected at least $MIN_APP_UNIT_TESTS. " +
                    "Either tests were lost/filtered, or the source set stopped being " +
                    "compiled. If you deliberately removed tests, lower " +
                    "MIN_APP_UNIT_TESTS in app/build.gradle.kts in the same commit.",
            )
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    // ContactZoneInferencer imports kotlinx.coroutines directly. It used to compile
    // only because DataStore/activity-compose leak it transitively via `api` — a bump
    // in either could have removed it. Declare what we use.
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Unit tests here are deliberately framework-free: the logic worth testing was pulled
    // out into ProcessTextDecision and UserPreferences' companion so that plain JUnit can
    // reach it. Robolectric would buy the Toast/clipboard/intent paths as well and remains
    // an open call (PLAN.md §7) — it is a real dependency, not a free one.
    testImplementation(libs.junit)
}
