// The editor-free half: protocol data classes, NDJSON framing, and the sidecar
// client. No IntelliJ platform dependency, so it compiles and tests fast and
// the protocol discipline (wire-serializable plain data only) is enforced by
// the module boundary.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
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
    // A wire test that deadlocks must fail with a stack trace, not hang the
    // build: coroutine timeouts cannot interrupt a blocking pipe read, so the
    // safety net lives at the JUnit level.
    systemProperty("junit.jupiter.execution.timeout.default", "30 s")
    testLogging {
        events("passed", "failed", "skipped")
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
