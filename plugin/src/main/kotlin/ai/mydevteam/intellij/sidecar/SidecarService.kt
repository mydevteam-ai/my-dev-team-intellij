/**
 * Owns the engine sidecar process for one project: spawns `node
 * dist/sidecar-stdio.js` in the project root, wires a SidecarClient over its
 * stdin/stdout, injects the runtime config, and respawns on the next request
 * after a crash. Cloud API keys travel as environment variables the child
 * inherits - nothing secret crosses the wire.
 */
package ai.mydevteam.intellij.sidecar

import ai.mydevteam.core.protocol.RuntimeConfig
import ai.mydevteam.core.sidecar.SidecarClient
import ai.mydevteam.intellij.settings.DevTeamSettings
import ai.mydevteam.intellij.tools.WorkspaceToolHost
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

@Service(Service.Level.PROJECT)
class SidecarService(private val project: Project) : Disposable {
    private val log = logger<SidecarService>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var process: Process? = null

    @Volatile
    private var client: SidecarClient? = null

    /**
     * The connected, handshaken client, spawning the child when needed. Throws
     * with a user-readable message when Node or the engine bundle is missing
     * or the handshake fails; the caller renders it in the chat.
     */
    @Synchronized
    fun connect(): SidecarClient {
        val existing = client
        if (existing != null && process?.isAlive == true) {
            return existing
        }
        client?.close()

        val settings = DevTeamSettings.instance().state
        val script = resolveSidecarScript(settings.sidecarScript)
        val command = listOf(settings.nodePath.ifBlank { "node" }, script.toString())
        log.info("Spawning engine sidecar: $command")

        val proc = try {
            ProcessBuilder(command)
                .directory(project.basePath?.let { Paths.get(it).toFile() })
                .start()
        } catch (e: Exception) {
            throw IllegalStateException(
                "Could not start Node (\"${command[0]}\"): ${e.message}. " +
                    "Install Node.js 20+ or set its path in Settings > Tools > My Dev Team."
            )
        }

        // stderr carries the child's logs; drain it or the child can block.
        Thread({
            proc.errorStream.bufferedReader().forEachLine { log.info("[sidecar] $it") }
        }, "devteam-sidecar-stderr").apply { isDaemon = true }.start()

        proc.onExit().thenAccept {
            log.info("Engine sidecar exited with ${it.exitValue()}")
            synchronized(this) {
                if (process === proc) {
                    client?.close()
                    client = null
                    process = null
                }
            }
        }

        val fresh = SidecarClient(
            output = proc.outputStream,
            input = proc.inputStream,
            scope = scope,
            handlers = WorkspaceToolHost(project),
            onDebug = { log.debug("[engine] $it") },
        )
        process = proc
        client = fresh
        return fresh
    }

    /** Wait for the ready handshake, then inject the current runtime config. */
    suspend fun connected(): SidecarClient {
        val c = connect()
        withTimeout(HANDSHAKE_TIMEOUT_MS) { c.ready.await() }
        c.sendConfig(currentRuntimeConfig())
        return c
    }

    private fun currentRuntimeConfig(): RuntimeConfig {
        val settings = DevTeamSettings.instance().state
        return RuntimeConfig(
            ollamaEndpoint = settings.ollamaEndpoint.ifBlank { null },
            workModel = settings.model.ifBlank { "auto" },
        )
    }

    /**
     * The sidecar script: the user's configured path when set, otherwise the
     * bundle shipped in the plugin's resources, extracted to a cache file.
     */
    private fun resolveSidecarScript(configured: String): Path {
        if (configured.isNotBlank()) {
            val path = Paths.get(configured)
            if (!Files.isRegularFile(path)) {
                throw IllegalStateException(
                    "The configured sidecar script does not exist: $configured"
                )
            }
            return path
        }
        val resource = javaClass.getResourceAsStream(BUNDLED_SIDECAR_RESOURCE)
            ?: throw IllegalStateException(
                "This build carries no engine bundle. Build the engine " +
                    "(npm run package in my-dev-team-vs-code) and set the sidecar " +
                    "script path in Settings > Tools > My Dev Team."
            )
        val target = Paths.get(
            com.intellij.openapi.application.PathManager.getTempPath(),
            "my-dev-team",
            "sidecar-stdio.js",
        )
        resource.use { stream ->
            Files.createDirectories(target.parent)
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    override fun dispose() {
        client?.close()
        client = null
        process?.destroy()
        process = null
        scope.cancel()
    }

    companion object {
        private const val BUNDLED_SIDECAR_RESOURCE = "/sidecar/sidecar-stdio.js"
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L

        fun getInstance(project: Project): SidecarService =
            project.getService(SidecarService::class.java)
    }
}
