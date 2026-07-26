plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

// StampDemo prints the parse+render table as executable documentation, gated on
// -Dtimetwister.demo=1 so it stays out of ordinary test runs.
//
// Both halves below are required, and the absence of either is part of why the
// README's "reproduce this locally" command used to report success while
// printing nothing:
//   1. Gradle does not forward -D from its own JVM to the test JVM.
//   2. Gradle swallows test stdout unless asked for it (or run with --info).
val demoProperty = "timetwister.demo"
val demoFlag: String? = providers.systemProperty(demoProperty).orNull

tasks.withType<Test>().configureEach {
    demoFlag?.let { systemProperty(demoProperty, it) }

    // Without this the flag is not part of the task's input fingerprint, so
    // toggling it on after a green run leaves the task UP-TO-DATE and it prints
    // nothing — the same silent no-op in a new disguise.
    inputs.property(demoProperty, demoFlag ?: "")

    if (demoFlag != null) {
        testLogging { showStandardStreams = true }
    }
}
