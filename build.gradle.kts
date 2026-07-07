// Root build: version and group only; the real work happens in the two
// modules. `core` is the editor-free half (protocol data classes, NDJSON
// framing, the sidecar client) and `plugin` is the IntelliJ half (tool window,
// tools, settings). The split mirrors the VS Code repo's engine/client
// discipline: nothing in core may import the IntelliJ platform.
allprojects {
    group = "ai.mydevteam"
    version = "0.1.0"
}
