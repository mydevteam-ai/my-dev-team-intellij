/**
 * One project's chat session: builds the RunRequest (prompt, tools,
 * environment facts, project instructions, capped history), starts the run on
 * the sidecar, and relays raw run events to the renderer. History is kept here
 * so /clear stays a client concern, exactly as in the VS Code client.
 *
 * Side questions ("/ask <q>" or a prompt led by "btw", see SideQuestions) run
 * as the engine's pinned /ask route with **no history**, and their turns are
 * never added to it - the question is answered in place but neither reads the
 * conversation nor joins it, so a later follow-up still refers to the real
 * work. Mirrors the VS Code client's collectHistory rules.
 */
package ai.mydevteam.intellij.ui

import ai.mydevteam.core.chat.SideQuestions
import ai.mydevteam.core.protocol.HistoryTurn
import ai.mydevteam.core.protocol.RunRequest
import ai.mydevteam.core.sidecar.ActiveRun
import ai.mydevteam.core.sidecar.RunOutcome
import ai.mydevteam.intellij.project.ProjectFacts
import ai.mydevteam.intellij.settings.DevTeamSettings
import ai.mydevteam.intellij.sidecar.SidecarService
import com.intellij.openapi.project.Project
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MAX_HISTORY_TURNS = 20

class ChatController(private val project: Project) {
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
            // A side question travels as the pinned /ask route with the marker
            // stripped and no history; a normal prompt goes as typed with the
            // capped conversation.
            val aside = SideQuestions.questionOf(prompt)
            val request = RunRequest(
                prompt = aside ?: prompt,
                offeredTools = listOf("read", "search"),
                command = if (aside != null) SideQuestions.ASK_COMMAND else null,
                model = DevTeamSettings.instance().state.model.ifBlank { "auto" },
                environment = ProjectFacts.environmentFacts(),
                instructions = ProjectFacts.readInstructions(project),
                history = if (aside != null) {
                    null
                } else {
                    history.takeLast(MAX_HISTORY_TURNS).toList().ifEmpty { null }
                },
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
            // A side question never joins the history: skipping it here is the
            // IntelliJ half of what collectHistory's ask-turn filter does in
            // the VS Code client.
            if (outcome is RunOutcome.Success && aside == null) {
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
}
