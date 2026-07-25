// Root build. The only logic here is build-output isolation; everything else lives in
// :app/build.gradle.kts.

// `:core` is a *shared* source directory — settings.gradle.kts maps it to ../android/core,
// the same directory the Android build compiles. With the default layout both builds would
// write to android/core/build, so `gradlew -p desktop clean` (or a plain desktop build)
// would wipe or overwrite the Android build's outputs and vice versa. Point the desktop
// build's copy at desktop/build/core instead, leaving android/core/build to the Android
// build alone.
project(":core") {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("core"))
}
