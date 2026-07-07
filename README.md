# my-dev-team-intellij

My Dev Team for IntelliJ IDEA: an agentic dev-team chat with tool-calling,
driven by the same engine as the
[VS Code extension](https://github.com/bobrovsky420/my-dev-team-vs-code).

## Architecture

The TypeScript engine (agents, prompts, model router, workflow) is not
reimplemented: it runs as a **Node sidecar process** (`node
dist/sidecar-stdio.js`, built by the VS Code repo) speaking newline-delimited
JSON over stdin/stdout. This plugin is only the client half:

- `core/` - editor-free Kotlin: the protocol data classes, NDJSON channel and
  the sidecar client (handshake, run events, the `invoke` capability
  inversion). No IntelliJ dependency; unit-tested standalone.
- `plugin/` - the IntelliJ half: the JCEF chat tool window, the workspace
  tools the engine calls back into (read and search today; run, write and edit
  with the approval UI come next), settings and the sidecar process manager.

The engine bundle is copied into the plugin's resources at build time from the
sibling VS Code repo checkout (`sidecarDist` in `gradle.properties`).

## Requirements

- JDK 21
- Node.js 20+ on PATH (or set its path in Settings > Tools > My Dev Team)
- A built engine bundle: `npm run package` in `my-dev-team-vs-code`

## Build and run

```
./gradlew :core:test        # protocol round-trip tests, no IDE download
./gradlew :plugin:buildPlugin
./gradlew :plugin:runIde    # sandbox IDE with the plugin
```

## Status

Phase 1 walking skeleton: chat tool window, sidecar handshake, streamed
answers, read + search tools. See the VS Code repo's docs/DESIGN.md for the
protocol and the roadmap.
