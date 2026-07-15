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

## Usage notes

- **Side questions.** Start a chat message with `btw` (or `/ask`) to ask a
  quick question that is answered in place but never joins the conversation:
  it runs with no history and is excluded from later turns, so mid-task
  curiosity cannot derail the ongoing work. Only a leading `btw` counts; a
  sentence merely containing it is handled normally. Because the answer never
  joins the conversation, it cannot be followed up on - ask side questions
  self-contained.
- **Quick question while the agent is busy.** `Ctrl+Alt+Q` (`Cmd+Alt+Q` on
  macOS), or Tools > My Dev Team: Ask a Quick Question, asks a side question
  outside the chat: the answer streams into a read-only-style editor tab while
  the chat run keeps going, and the background task's cancel button stops it.
  Quick questions never read or change your files.

## Status

Phase 1 walking skeleton: chat tool window, sidecar handshake, streamed
answers, read + search tools, side questions (`btw`/`/ask` + the quick-question
action). See the VS Code repo's docs/DESIGN.md for the protocol and the
roadmap.
