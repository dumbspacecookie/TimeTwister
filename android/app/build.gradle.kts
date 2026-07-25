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
}
