// Root build: shared plugin versions (applied false here so each module
// applies what it needs without loading the Kotlin plugin twice) and the
// group/version. `core` is the editor-free half (protocol data classes,
// NDJSON framing, the sidecar client) and `plugin` is the IntelliJ half (tool
// window, tools, settings). The split mirrors the VS Code repo's
// engine/client discipline: nothing in core may import the IntelliJ platform.
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.intellij.platform") version "2.2.1" apply false
}

allprojects {
    group = "ai.mydevteam"
    version = "0.1.0"
}
