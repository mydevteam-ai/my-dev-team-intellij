/**
 * The wire-shaped data contract with the engine, mirroring the TypeScript
 * source of truth in my-dev-team-vs-code/src/protocol/types.ts. Everything
 * here is plain serializable data; nested shapes the Kotlin side only relays
 * (to the renderer or back to the engine) stay JsonObject on purpose - the
 * webview consumes the raw JSON, so retyping every leaf would only add drift
 * surface. PROTOCOL_VERSION must match the engine's or its ready handshake is
 * rejected.
 */
package ai.mydevteam.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

const val PROTOCOL_VERSION = 5

/**
 * The one Json configuration both directions use: unknown keys ignored (a
 * newer engine may add fields - version skew degrades, exactly as the TS
 * client behaves), defaults encoded (protocolVersion must always ride), nulls
 * omitted (the wire's optional-field idiom; JSON.stringify drops undefined the
 * same way).
 */
val wireJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** One attached file/selection: a short label plus its (already truncated) text. */
@Serializable
data class Attachment(val label: String, val text: String)

/** Standing project instructions read from the workspace root (AGENTS.md/CLAUDE.md). */
@Serializable
data class ProjectInstructions(val source: String, val text: String)

/** One workspace skill, as metadata only; the body is fetched on demand via the skill tool. */
@Serializable
data class WorkspaceSkill(val source: String, val name: String, val description: String)

/** One prior conversation turn, already capped by the client. */
@Serializable
data class HistoryTurn(val role: String, val text: String)

/** The client's runtime facts: prompts are written for the client's OS and shell. */
@Serializable
data class EnvironmentFacts(val os: String, val shell: String)

/** Everything the client sends to start a run (see RunRequestSchema in the TS protocol). */
@Serializable
data class RunRequest(
    val prompt: String,
    val offeredTools: List<String>,
    val protocolVersion: Int = PROTOCOL_VERSION,
    val command: String? = null,
    val model: String? = null,
    val instructions: ProjectInstructions? = null,
    val attachments: List<Attachment>? = null,
    val history: List<HistoryTurn>? = null,
    val skills: List<WorkspaceSkill>? = null,
    val environment: EnvironmentFacts? = null,
    val shadowTriage: Boolean? = null,
)

/** One model offered in the picker (Engine.listModels). */
@Serializable
data class ModelChoice(
    val id: String,
    val label: String,
    val description: String = "",
    val available: Boolean = true,
    val disabled: Boolean? = null,
)

/**
 * The engine's injected runtime configuration, mirroring
 * config/runtimeConfig.ts (field names and defaults must match - the child
 * re-injects this snapshot verbatim). `customModels` entries stay raw objects:
 * the engine owns their schema and validates them itself.
 */
@Serializable
data class RuntimeConfig(
    val ollamaEndpoint: String? = null,
    val providerBaseUrls: Map<String, String> = emptyMap(),
    val disabledProviders: List<String> = emptyList(),
    val disabledModels: List<String> = emptyList(),
    val customModels: List<JsonObject> = emptyList(),
    val workModel: String = "auto",
    val triageModel: String = "",
    val triageMode: String = "classifier",
    val complexityRoutingEnabled: Boolean = true,
    val requestsPerMinute: Double? = null,
    val toolSnippetLines: Int = 5,
    val checkpointEverySteps: Int = 100,
    val checkpointEverySeconds: Int = 600,
    val contextWarnThresholds: List<Int> = listOf(75, 85, 95),
    val modelContextWindows: Map<String, Int> = emptyMap(),
    val planApproval: String = "auto",
    val thinkingShowInChat: Boolean = true,
    val summaryShowInChat: Boolean = true,
    val clarifyEnabled: Boolean = true,
    val debugEnabled: Boolean = false,
)
