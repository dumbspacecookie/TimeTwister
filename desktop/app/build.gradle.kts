plugins {
    kotlin("jvm") version "2.1.0"
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
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
