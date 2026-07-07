package ai.mydevteam.core

import ai.mydevteam.core.protocol.PROTOCOL_VERSION
import ai.mydevteam.core.protocol.RunRequest
import ai.mydevteam.core.protocol.wireJson
import ai.mydevteam.core.sidecar.ClientHandlers
import ai.mydevteam.core.sidecar.RunOutcome
import ai.mydevteam.core.sidecar.SidecarClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Drives a SidecarClient against a scripted fake engine child speaking NDJSON
 * over piped streams, so the whole wire - handshake, start, events, the invoke
 * inversion, result - is exercised with no process.
 */
class SidecarClientTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // client -> child
    private val childStdin = PipedInputStream()
    private val toChild = PipedOutputStream(childStdin)

    // child -> client
    private val clientIn = PipedInputStream()
    private val fromChild = PipedOutputStream(clientIn)

    private val childReader: BufferedReader = childStdin.bufferedReader()
    private val childWriter: BufferedWriter = fromChild.bufferedWriter()

    private fun childPosts(json: String) {
        childWriter.write(json)
        childWriter.write("\n")
        childWriter.flush()
    }

    private fun childReadsLine(): JsonObject =
        wireJson.parseToJsonElement(childReader.readLine()!!).jsonObject

    private val handlers = object : ClientHandlers {
        override val toolNames = listOf("read", "search")
        override val capabilities = listOf("tool")
        override suspend fun invoke(capability: String, payload: JsonObject): JsonElement {
            assertEquals("tool", capability)
            val tool = payload.getValue("tool").jsonPrimitive.content
            return JsonPrimitive("result of $tool")
        }
    }

    private val client = SidecarClient(toChild, clientIn, scope, handlers)

    @AfterTest
    fun tearDown() {
        client.close()
        scope.cancel()
    }

    @Test
    fun `full round trip - handshake, events, invoke inversion, result`(): Unit = runBlocking {
        childPosts("""{"t":"ready","protocolVersion":$PROTOCOL_VERSION,"kind":"local"}""")
        withTimeout(5_000) { client.ready.await() }

        val events = mutableListOf<JsonObject>()
        val run = client.startRun(
            RunRequest(prompt = "hi", offeredTools = handlers.toolNames),
        ) { events.add(it) }

        // The child sees the start frame with the advertised capabilities.
        val start = childReadsLine()
        assertEquals("start", start.getValue("t").jsonPrimitive.content)
        val runId = start.getValue("runId").jsonPrimitive.content
        val caps = start.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("tool"), caps)

        // An event streams through to the renderer callback.
        childPosts("""{"t":"event","runId":"$runId","event":{"type":"answer-delta","text":"he"}}""")

        // The engine asks for a tool; the client answers with an invoke-result.
        childPosts(
            """{"t":"invoke","runId":"$runId","callId":"c1","capability":"tool",""" +
                """"payload":{"tool":"read","args":{"path":"a.txt"}}}"""
        )
        val invokeResult = withTimeout(5_000) { childReadsLine() }
        assertEquals("invoke-result", invokeResult.getValue("t").jsonPrimitive.content)
        assertEquals("c1", invokeResult.getValue("callId").jsonPrimitive.content)
        assertEquals("result of read", invokeResult.getValue("result").jsonPrimitive.content)

        // The run settles.
        childPosts(
            """{"t":"result","runId":"$runId","result":{"ok":true,""" +
                """"reply":{"intent":"oneshot","reason":"r","answer":"hey"}}}"""
        )
        val outcome = withTimeout(5_000) { run.result.await() }
        assertIs<RunOutcome.Success>(outcome)
        assertEquals("hey", outcome.reply.getValue("answer").jsonPrimitive.content)
        assertTrue(events.isNotEmpty())
    }

    @Test
    fun `a version mismatch fails the handshake with a clear message`(): Unit = runBlocking {
        childPosts("""{"t":"ready","protocolVersion":${PROTOCOL_VERSION + 1},"kind":"local"}""")
        val failure = runCatching { withTimeout(5_000) { client.ready.await() } }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("protocol"))
    }

    @Test
    fun `a failed run decodes into the failed outcome`(): Unit = runBlocking {
        childPosts("""{"t":"ready","protocolVersion":$PROTOCOL_VERSION,"kind":"local"}""")
        withTimeout(5_000) { client.ready.await() }
        val run = client.startRun(RunRequest(prompt = "x", offeredTools = emptyList())) {}
        val runId = childReadsLine().getValue("runId").jsonPrimitive.content
        childPosts(
            """{"t":"result","runId":"$runId","result":""" +
                """{"ok":false,"kind":"failed","message":"boom","step":"plan","hint":"check ollama"}}"""
        )
        val outcome = withTimeout(5_000) { run.result.await() }
        assertIs<RunOutcome.Failed>(outcome)
        assertEquals("plan", outcome.step)
        assertEquals("check ollama", outcome.hint)
    }
}
