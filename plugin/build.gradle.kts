// The IntelliJ half: tool window, tools, settings, sidecar process management.
// Depends on :core for everything wire-shaped; the engine itself is the
// bundled Node sidecar (dist/sidecar-stdio.js from the VS Code repo), copied
// into the plugin's resources by the `copySidecarBundle` task below.
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.intellij.platform")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core")) {
        // The platform ships its own Kotlin stdlib and coroutines; ours must
        // not ride along or the two versions clash at runtime.
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }

    intellijPlatform {
        intellijIdeaCommunity("2024.2.4")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    // Bytecode instrumentation only matters for UI Designer forms and JB
    // nullability assertions, neither of which this plugin uses; it also needs
    // a JetBrains Runtime the build host may not have.
    instrumentCode = false
    pluginConfiguration {
        id = "ai.mydevteam.intellij"
        name = "My Dev Team"
        version = project.version.toString()
        description =
            "An agentic dev-team chat with tool-calling (read, search, run, write) " +
                "driven by the My Dev Team engine running as a local sidecar."
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
}

// Copy the engine bundle into resources so the plugin zip carries it. The
// source path comes from the `sidecarDist` gradle property (defaults to the
// sibling VS Code repo's dist output). Skipped with a warning when missing so
// a core-only build does not require the JS toolchain.
val copySidecarBundle by tasks.registering(Copy::class) {
    val dist = rootProject.file(providers.gradleProperty("sidecarDist").get())
    from(dist)
    into(layout.buildDirectory.dir("generated-resources/sidecar"))
    onlyIf {
        dist.exists().also {
            if (!it) logger.warn("sidecar bundle not found at $dist; the plugin will need myDevTeam sidecar path set at runtime")
        }
    }
}

sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated-resources"))

tasks.processResources {
    dependsOn(copySidecarBundle)
}

tasks.test {
    useJUnitPlatform()
}
