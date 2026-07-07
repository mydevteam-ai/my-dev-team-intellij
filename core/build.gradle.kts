// The editor-free half: protocol data classes, NDJSON framing, and the sidecar
// client. No IntelliJ platform dependency, so it compiles and tests fast and
// the protocol discipline (wire-serializable plain data only) is enforced by
// the module boundary.
plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Explicit because kotlin.stdlib.default.dependency=false repo-wide (the
    // plugin module must compile against the IDE's bundled stdlib instead).
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
