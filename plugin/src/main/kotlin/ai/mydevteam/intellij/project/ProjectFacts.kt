/**
 * Facts about the running IDE and the open project that every run request
 * carries: the OS/shell the engine writes prompts for, and the workspace's
 * standing instruction file (AGENTS.md/CLAUDE.md). Shared by the chat
 * controller and the quick-question action so the two build identical
 * requests.
 */
package ai.mydevteam.intellij.project

import ai.mydevteam.core.protocol.EnvironmentFacts
import ai.mydevteam.core.protocol.ProjectInstructions
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Paths

private const val MAX_INSTRUCTIONS_CHARS = 20_000
private val INSTRUCTION_FILES = listOf("AGENTS.md", "CLAUDE.md")
private val log = Logger.getInstance("ai.mydevteam.intellij.project.ProjectFacts")

object ProjectFacts {
    fun environmentFacts(): EnvironmentFacts {
        val osName = System.getProperty("os.name").lowercase()
        val os = when {
            osName.contains("win") -> "Windows"
            osName.contains("mac") -> "macOS"
            else -> "Linux"
        }
        return EnvironmentFacts(os = os, shell = if (os == "Windows") "PowerShell" else "bash")
    }

    /** Probe the project root for a standing instructions file (AGENTS.md, then CLAUDE.md). */
    fun readInstructions(project: Project): ProjectInstructions? {
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
