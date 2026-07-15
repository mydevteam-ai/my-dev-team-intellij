/**
 * The quick-question action: the hotkey path for asking a side question while
 * the chat is busy (the chat input is held by a streaming turn, but the
 * sidecar multiplexes runs, so a second run just works). An input dialog takes
 * the question, the run goes to the engine as the pinned /ask route - no
 * conversation history, no tools offered, so it structurally cannot touch the
 * workspace - and the answer streams into an in-memory markdown editor tab. A
 * cancellable background task covers the run; cancelling closes the tab.
 * Mirrors ui/quickQuestion.ts in the VS Code client.
 */
package ai.mydevteam.intellij.actions

import ai.mydevteam.core.chat.SideQuestions
import ai.mydevteam.core.protocol.RunRequest
import ai.mydevteam.core.sidecar.RunOutcome
import ai.mydevteam.intellij.project.ProjectFacts
import ai.mydevteam.intellij.settings.DevTeamSettings
import ai.mydevteam.intellij.sidecar.SidecarService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger

class QuickQuestionAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val question = Messages.showInputDialog(
            project,
            "Answered on the side - it never joins the chat conversation " +
                "and never reads or changes your files.",
            "Ask a Quick Question",
            null,
        )?.trim()
        if (question.isNullOrEmpty()) {
            return
        }
        QuickQuestionRun(project, question).start()
    }
}

/** Per-window question counter: the answer tab reads "Quick answer 1.md". */
private val nextQuestionId = AtomicInteger(1)

/**
 * One question's lifecycle: the answer tab it streams into, the background
 * task that awaits (and can cancel) the run, and the request it sends.
 */
private class QuickQuestionRun(
    private val project: Project,
    private val question: String,
) {
    private val file = LightVirtualFile(
        "Quick answer ${nextQuestionId.getAndIncrement()}.md",
        render(body = "", done = false),
    )

    fun start() {
        FileEditorManager.getInstance(project).openFile(file, false)
        object : Task.Backgroundable(project, "My Dev Team: answering your question", true) {
            override fun run(indicator: ProgressIndicator) = runBlocking {
                execute(indicator)
            }
        }.queue()
    }

    private suspend fun execute(indicator: ProgressIndicator) {
        val client = try {
            SidecarService.getInstance(project).connected()
        } catch (e: Exception) {
            update(render(failure(e.message ?: "The engine sidecar did not start."), done = true))
            return
        }
        // A side question is context-free (no history) and workspace-blind (no
        // tools); the workspace's standing instructions still apply.
        val request = RunRequest(
            prompt = question,
            offeredTools = emptyList(),
            command = SideQuestions.ASK_COMMAND,
            model = DevTeamSettings.instance().state.model.ifBlank { "auto" },
            environment = ProjectFacts.environmentFacts(),
            instructions = ProjectFacts.readInstructions(project),
        )
        val answer = StringBuilder()
        val run = client.startRun(request) { event -> onEvent(event, answer) }

        // Await the result while watching the task's cancel button; cancelling
        // tells the engine to stop and closes the tab without waiting for the
        // (best-effort) cancelled result to come back.
        val outcome: RunOutcome = awaitOrCancel(run.result::isCompleted, indicator) {
            run.cancel()
        } ?: run.result.await()

        when (outcome) {
            is RunOutcome.Success -> {
                val text = outcome.reply["answer"]?.jsonPrimitive?.content ?: answer.toString()
                update(render(text, done = true))
            }
            is RunOutcome.Cancelled -> close()
            is RunOutcome.Failed ->
                update(render(failure(outcome.message) + (outcome.hint?.let { "\n\n$it" } ?: ""), done = true))
            is RunOutcome.Other -> update(render(failure(outcome.message), done = true))
        }
    }

    /**
     * Poll until `done`, returning null; or, when the indicator is cancelled
     * first, run `onCancel` and return the cancelled outcome directly.
     */
    private suspend fun awaitOrCancel(
        done: () -> Boolean,
        indicator: ProgressIndicator,
        onCancel: () -> Unit,
    ): RunOutcome? {
        while (!done()) {
            if (indicator.isCanceled) {
                onCancel()
                return RunOutcome.Cancelled
            }
            delay(150)
        }
        return null
    }

    /** Stream each answer delta into the tab; other events have nothing to show here. */
    private fun onEvent(event: JsonObject, answer: StringBuilder) {
        if (event["type"]?.jsonPrimitive?.content != "answer-delta") {
            return
        }
        val delta = event["text"]?.jsonPrimitive?.content ?: return
        val text = synchronized(answer) {
            answer.append(delta)
            answer.toString()
        }
        update(render(text, done = false))
    }

    /** The answer document: title, the quoted question, then the (streaming) body. */
    private fun render(body: String, done: Boolean): String {
        val quoted = question.lines().joinToString("\n") { "> $it" }
        val working = if (done) "" else (if (body.isEmpty()) "" else "\n\n") + "_Working..._"
        return "# Quick answer\n\n$quoted\n\n$body$working\n"
    }

    private fun failure(message: String): String = "**The question failed:** $message"

    /** Replace the tab's content; Documents insist on LF-only text. */
    private fun update(text: String) {
        val normalized = StringUtil.convertLineSeparators(text)
        ApplicationManager.getApplication().invokeLater {
            val document = FileDocumentManager.getInstance().getDocument(file)
                ?: return@invokeLater
            ApplicationManager.getApplication().runWriteAction {
                document.setText(normalized)
            }
        }
    }

    /** A cancelled question renders nothing: close its tab. */
    private fun close() {
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).closeFile(file)
        }
    }
}
