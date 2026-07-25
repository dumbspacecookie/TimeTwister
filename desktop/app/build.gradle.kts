import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    // From the shared version catalog, not a hardcoded literal. `:core` resolves its
    // Kotlin plugin via `alias(libs.plugins.kotlin.jvm)`; if this module pinned its own
    // version, bumping the catalog would make the two disagree and Gradle would fail the
    // build with a plugin-version conflict. The catalog is republished into this build in
    // settings.gradle.kts precisely so both sides can share one source of truth.
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.timetwister.desktop.MainKt")
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}

// ---------------------------------------------------------------------------
// Native packaging
// ---------------------------------------------------------------------------

val javaToolchains = extensions.getByType<JavaToolchainService>()

val isWindowsHost: Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

val appImageName = "TimeTwister"

/**
 * Builds a self-contained, double-clickable application directory via `jpackage`,
 * which ships with the JDK — no Gradle plugin, no third-party dependency.
 *
 * `--type app-image` on purpose: a Windows `.msi`/`.exe` installer requires the WiX
 * Toolset on the build machine, and `.deb`/`.rpm` require dpkg/rpm-build. An app-image
 * requires nothing beyond the JDK, behaves the same on all three platforms, and already
 * bundles a trimmed JRE — so the recipient does not need Java installed at all. Output
 * is platform-specific: run this on the OS you are shipping to.
 */
val jpackageAppImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a self-contained app image (bundled JRE) with jpackage."

    dependsOn(tasks.named("installDist"))

    val launcher = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    // installDist lays out exactly what jpackage wants: a lib/ directory containing
    // nothing but the runtime jars.
    val inputDir = layout.buildDirectory.dir("install/${project.name}/lib")
    val outputDir = layout.buildDirectory.dir("jpackage")

    inputs.dir(inputDir)
    outputs.dir(outputDir)

    doFirst {
        val jpackage = launcher.get().metadata.installationPath
            .file("bin/jpackage" + if (isWindowsHost) ".exe" else "")
            .asFile
        require(jpackage.exists()) {
            "jpackage not found at $jpackage — a full JDK (not a JRE) is required."
        }

        // jpackage refuses to write into an existing image directory.
        val dest = outputDir.get().asFile
        dest.resolve(appImageName).deleteRecursively()
        dest.mkdirs()

        commandLine(
            jpackage.absolutePath,
            "--type", "app-image",
            "--name", appImageName,
            "--app-version", "1.0.0",
            "--vendor", "TimeTwister",
            "--description", "Multi-timezone stamps for whatever is on your clipboard",
            "--input", inputDir.get().asFile.absolutePath,
            "--main-jar", "${project.name}.jar",
            "--main-class", "com.timetwister.desktop.MainKt",
            "--dest", dest.absolutePath,
        )
    }

    doLast {
        logger.lifecycle(
            "App image written to ${outputDir.get().asFile.resolve(appImageName)}",
        )
    }
}
