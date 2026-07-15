/**
 * One project's chat session: builds the RunRequest (prompt, tools,
 * environment facts, project instructions, capped history), starts the run on
 * the sidecar, and relays raw run events to the renderer. History is kept here
 * so /clear stays a client concern, exactly as in the VS Code client.
 */
package ai.mydevteam.intellij.ui

import ai.mydevteam.core.protocol.EnvironmentFacts
import ai.mydevteam.core.protocol.HistoryTurn
import ai.mydevteam.core.protocol.ProjectInstructions
import ai.mydevteam.core.protocol.RunRequest
import ai.mydevteam.core.sidecar.ActiveRun
import ai.mydevteam.core.sidecar.RunOutcome
import ai.mydevteam.intellij.settings.DevTeamSettings
import ai.mydevteam.intellij.sidecar.SidecarService
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Paths

private const val MAX_HISTORY_TURNS = 20
private const val MAX_INSTRUCTIONS_CHARS = 20_000
private val INSTRUCTION_FILES = listOf("AGENTS.md", "CLAUDE.md")

class ChatController(private val project: Project) {
    private val log = logger<ChatController>()
    private val history = mutableListOf<HistoryTurn>()

    @Volatile
    private var active: ActiveRun? = null

    /** Start one run. Events and the outcome arrive on background threads. */
    fun send(
        prompt: String,
        onEvent: (JsonObject) -> Unit,
        onOutcome: (RunOutcome) -> Unit,
        onFailedToStart: (String) -> Unit,
    ) {
        val service = SidecarService.getInstance(project)
        service.scope.launch {
            val client = try {
                service.connected()
            } catch (e: Exception) {
                onFailedToStart(e.message ?: "The engine sidecar did not start.")
                return@launch
            }
            val request = RunRequest(
                prompt = prompt,
                offeredTools = listOf("read", "search"),
                model = DevTeamSettings.instance().state.model.ifBlank { "auto" },
                environment = environmentFacts(),
                instructions = readInstructions(),
                history = history.takeLast(MAX_HISTORY_TURNS).toList().ifEmpty { null },
            )
            val run = client.startRun(request, onEvent)
            active = run
            val outcome = try {
                run.result.await()
            } catch (e: Exception) {
                RunOutcome.Other(e.message ?: "The run failed.")
            } finally {
                active = null
            }
            if (outcome is RunOutcome.Success) {
                history.add(HistoryTurn(role = "user", text = prompt))
                assistantText(outcome.reply)?.let {
                    history.add(HistoryTurn(role = "assistant", text = it))
                }
            }
            onOutcome(outcome)
        }
    }

    fun cancel() {
        active?.cancel()
    }

    fun clearHistory() {
        history.clear()
    }

    /** The reply text a follow-up turn should see: the answer, or the plan summary. */
    private fun assistantText(reply: JsonObject): String? {
        reply["answer"]?.jsonPrimitive?.content?.let { return it }
        val plan = reply["plan"] as? JsonObject ?: return null
        return plan["summary"]?.jsonPrimitive?.content
    }

    private fun environmentFacts(): EnvironmentFacts {
        val osName = System.getProperty("os.name").lowercase()
        val os = when {
            osName.contains("win") -> "Windows"
            osName.contains("mac") -> "macOS"
            else -> "Linux"
        }
        return EnvironmentFacts(os = os, shell = if (os == "Windows") "PowerShell" else "bash")
    }

    /** Probe the project root for a standing instructions file (AGENTS.md, then CLAUDE.md). */
    private fun readInstructions(): ProjectInstructions? {
        val base = project.basePath ?: return null
        for (name in INSTRUCTION_FILES) {
            val path = Paths.get(base, name)
            if (Files.isRegularFile(path)) {
                return try {
                    ProjectInstructions(
                        source = name,
                        text = Files.readString(path, Charsets.UTF_8).take(MAX_INSTRUCTIONS_CHARS),
                    )
                } catch (e: Exception) {
                    log.warn("Could not read $name", e)
                    null
                }
            }
        }
        return null
    }
}
