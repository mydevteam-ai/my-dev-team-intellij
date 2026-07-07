/**
 * The client's hands: the tool implementations the engine reaches through the
 * `tool` capability. Phase 1 offers the read-only pair (read, search); run,
 * write and edit arrive with the approval UI in Phase 2. The model-facing
 * result strings mirror the VS Code client's copy (config/messages.ts) so the
 * one engine sees the same tool behaviour behind either editor.
 */
package ai.mydevteam.intellij.tools

import ai.mydevteam.core.sidecar.ClientHandlers
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

private const val MAX_READ_LINES = 200
private const val MAX_FILE_SIZE_BYTES = 1_000_000L
private const val GLOB_MAX_RESULTS = 200
private const val CONTENT_SCAN_LIMIT = 500
private const val CONTENT_MAX_MATCHES = 50
private const val PREVIEW_MAX_CHARS = 200

/** Directories never walked: VCS internals and dependency trees. */
private val SKIPPED_DIRS = setOf(".git", "node_modules", ".idea")

class WorkspaceToolHost(private val project: Project) : ClientHandlers {
    override val toolNames: List<String> = listOf("read", "search")
    override val capabilities: List<String> = listOf("tool")

    override suspend fun invoke(capability: String, payload: JsonObject): JsonElement {
        require(capability == "tool") { "Unsupported capability \"$capability\"." }
        val tool = payload["tool"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("The tool call names no tool.")
        val args = payload["args"] as? JsonObject ?: JsonObject(emptyMap())
        val result = when (tool) {
            "read" -> read(args)
            "search" -> search(args)
            else -> throw IllegalArgumentException("Unknown tool \"$tool\".")
        }
        return JsonPrimitive(result)
    }

    private fun basePath(): Path {
        val base = project.basePath
            ?: throw IllegalStateException("No project directory is open.")
        return Paths.get(base).toAbsolutePath().normalize()
    }

    /**
     * Resolve a workspace-relative path and refuse anything that escapes the
     * project root (absolute paths, .. traversal, or a symbolic link).
     */
    private fun resolveContained(relPath: String): Path {
        val root = basePath()
        val candidate = root.resolve(relPath).normalize()
        if (!candidate.startsWith(root)) {
            throw IllegalArgumentException("Path is outside the workspace: $relPath")
        }
        if (Files.exists(candidate) && Files.isSymbolicLink(candidate)) {
            throw IllegalArgumentException("Path is a symbolic link, which is not allowed: $relPath")
        }
        return candidate
    }

    private fun read(args: JsonObject): String {
        val relPath = args["path"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("read needs a path.")
        val startLine = args["startLine"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        val requestedEnd = args["endLine"]?.jsonPrimitive?.content?.toIntOrNull()

        val file = resolveContained(relPath)
        if (!Files.isRegularFile(file)) {
            return "No such file or directory: $relPath. Use the search tool to find " +
                "the file you meant, or the write tool to create it."
        }
        val size = Files.size(file)
        if (size > MAX_FILE_SIZE_BYTES) {
            return "$relPath is $size bytes, over the $MAX_FILE_SIZE_BYTES-byte read " +
                "limit; reading it whole would risk the editor's memory. Use the " +
                "search tool to find the lines you need in it."
        }

        val allLines = Files.readAllLines(file, Charsets.UTF_8)
        val total = allLines.size
        if (requestedEnd != null && requestedEnd < startLine) {
            return "endLine $requestedEnd is before startLine $startLine; nothing was " +
                "read. Use an endLine at or after startLine."
        }
        if (startLine > total) {
            return "$relPath has only $total lines; startLine $startLine is past the " +
                "end of the file."
        }
        val capEnd = startLine + MAX_READ_LINES - 1
        val end = minOf(requestedEnd ?: capEnd, capEnd, total)
        val text = allLines.subList(startLine - 1, end).joinToString("\n")
        if (startLine == 1 && end == total) {
            return text
        }
        val header = "(lines $startLine-$end of $total" +
            (if (end < total) "; continue with startLine ${end + 1})" else ")")
        return "$header\n$text"
    }

    private fun search(args: JsonObject): String {
        val query = args["query"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("search needs a query.")
        val mode = args["mode"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("search needs a mode.")
        return when (mode) {
            "glob" -> globSearch(query)
            "content" -> contentSearch(query)
            else -> throw IllegalArgumentException("Unknown search mode \"$mode\".")
        }
    }

    /** Walk the project tree, calling `visit` with each file's root-relative path. */
    private fun walkFiles(visit: (path: Path, rel: String) -> Boolean) {
        val root = basePath()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir.fileName?.toString() in SKIPPED_DIRS) FileVisitResult.SKIP_SUBTREE
                else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = root.relativize(file).toString().replace('\\', '/')
                return if (visit(file, rel)) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult =
                FileVisitResult.CONTINUE
        })
    }

    private fun globSearch(query: String): String {
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$query")
        val results = mutableListOf<String>()
        walkFiles { _, rel ->
            if (matcher.matches(Paths.get(rel))) {
                results.add(rel)
            }
            results.size < GLOB_MAX_RESULTS
        }
        return if (results.isEmpty()) "(no files matched)" else results.joinToString("\n")
    }

    private fun contentSearch(query: String): String {
        val matches = mutableListOf<String>()
        var scanned = 0
        var stoppedEarly = false
        walkFiles { file, rel ->
            if (scanned >= CONTENT_SCAN_LIMIT) {
                stoppedEarly = true
                return@walkFiles false
            }
            if (Files.size(file) > MAX_FILE_SIZE_BYTES) return@walkFiles true
            scanned++
            val text = try {
                Files.readString(file, Charsets.UTF_8)
            } catch (e: Exception) {
                return@walkFiles true // binary or unreadable: skip
            }
            text.lineSequence().forEachIndexed { index, line ->
                if (matches.size < CONTENT_MAX_MATCHES && line.contains(query)) {
                    matches.add("$rel:${index + 1}: ${line.trim().take(PREVIEW_MAX_CHARS)}")
                }
            }
            matches.size < CONTENT_MAX_MATCHES
        }
        if (matches.isEmpty()) {
            return if (stoppedEarly) {
                "(search stopped after scanning $scanned files; more files were not " +
                    "searched - narrow the query or use a glob search to look in fewer files)"
            } else {
                "(no matches)"
            }
        }
        val body = matches.joinToString("\n")
        return if (stoppedEarly) {
            body + "\n(search stopped after scanning $scanned files; more files were " +
                "not searched - narrow the query or use a glob search to look in fewer files)"
        } else {
            body
        }
    }
}
