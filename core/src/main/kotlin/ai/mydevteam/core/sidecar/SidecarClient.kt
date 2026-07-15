/**
 * The client end of the sidecar protocol: the Kotlin counterpart of
 * my-dev-team-vs-code/src/client/sidecarEngine.ts. It speaks ChildMessage /
 * ParentMessage over an NdjsonChannel, holds runs until the child's `ready`
 * handshake (rejecting a protocol-version mismatch up front), forwards run
 * events for rendering, services every engine->client `invoke` through the
 * supplied ClientHandlers, and settles each run from its terminal `result`
 * message. Editor-free: the IntelliJ layer supplies the streams (a spawned
 * Node process) and the handlers (the real ToolHost).
 */
package ai.mydevteam.core.sidecar

import ai.mydevteam.core.protocol.ModelChoice
import ai.mydevteam.core.protocol.PROTOCOL_VERSION
import ai.mydevteam.core.protocol.RunRequest
import ai.mydevteam.core.protocol.RuntimeConfig
import ai.mydevteam.core.protocol.wireJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What this client can answer when the engine asks. `invoke` receives the
 * capability name and its payload and returns the capability's wire result
 * (for "tool": a JSON string - exactly the text the model sees). Throwing
 * makes the call cross back as the invoke's error.
 */
interface ClientHandlers {
    /** The client tool names offered to the engine (the run's offeredTools). */
    val toolNames: List<String>

    /** The capability set advertised on `start` ("tool" at minimum). */
    val capabilities: List<String>

    suspend fun invoke(capability: String, payload: JsonObject): JsonElement
}

/** How a run ended, preserving the wire's RunResult shape. */
sealed interface RunOutcome {
    data class Success(val reply: JsonObject) : RunOutcome
    data class Failed(val message: String, val step: String?, val hint: String?) : RunOutcome
    data object Cancelled : RunOutcome
    data class Other(val message: String) : RunOutcome
}

/** A started run: its events flow to the callback given at start; `result` settles once. */
class ActiveRun internal constructor(
    val runId: String,
    val result: Deferred<RunOutcome>,
    private val doCancel: () -> Unit,
) {
    fun cancel(): Unit = doCancel()
}

class SidecarClient(
    output: OutputStream,
    input: InputStream,
    private val scope: CoroutineScope,
    private val handlers: ClientHandlers,
    private val onDebug: (JsonObject) -> Unit = {},
) : AutoCloseable {
    /** Settles when the child posts `ready`; fails on close or version mismatch. */
    val ready: CompletableDeferred<Unit> = CompletableDeferred()

    private data class Run(
        val onEvent: (JsonObject) -> Unit,
        val result: CompletableDeferred<RunOutcome>,
    )

    private val runs = ConcurrentHashMap<String, Run>()
    private val queries = ConcurrentHashMap<String, CompletableDeferred<JsonElement?>>()
    private val seq = AtomicLong(0)

    private val channel = NdjsonChannel(output, input, ::dispatch) { reason ->
        val err = IllegalStateException("The sidecar channel closed: $reason")
        ready.completeExceptionally(err)
        queries.values.forEach { it.completeExceptionally(err) }
        queries.clear()
        runs.values.forEach { it.result.complete(RunOutcome.Other(err.message ?: "closed")) }
        runs.clear()
    }

    private fun dispatch(raw: JsonObject) {
        when (val msg = ChildMessage.decode(raw) ?: return) {
            is ChildMessage.Ready -> {
                if (msg.protocolVersion != PROTOCOL_VERSION) {
                    ready.completeExceptionally(
                        IllegalStateException(
                            "The engine bundle speaks protocol ${msg.protocolVersion} " +
                                "but this plugin speaks $PROTOCOL_VERSION; " +
                                "update the bundled engine or the plugin."
                        )
                    )
                } else {
                    ready.complete(Unit)
                }
            }
            is ChildMessage.Event -> runs[msg.runId]?.onEvent?.invoke(msg.event)
            is ChildMessage.Invoke -> serviceInvoke(msg)
            is ChildMessage.Debug -> onDebug(msg.entry)
            is ChildMessage.Result -> {
                val run = runs.remove(msg.runId) ?: return
                run.result.complete(decodeOutcome(msg.result))
            }
            is ChildMessage.QueryResult -> {
                val pending = queries.remove(msg.queryId) ?: return
                if (msg.ok) {
                    pending.complete(msg.value)
                } else {
                    pending.completeExceptionally(IllegalStateException(msg.error ?: "query failed"))
                }
            }
            is ChildMessage.Unknown -> Unit
        }
    }

    private fun serviceInvoke(msg: ChildMessage.Invoke) {
        scope.launch {
            val answer = try {
                ParentMessages.invokeResultOk(msg.callId, handlers.invoke(msg.capability, msg.payload))
            } catch (e: Exception) {
                ParentMessages.invokeResultError(msg.callId, e.message ?: "The call failed.")
            }
            try {
                channel.post(answer)
            } catch (ignored: Exception) {
                // The channel died while we worked; the close handler settles the run.
            }
        }
    }

    private fun decodeOutcome(result: JsonObject): RunOutcome {
        val ok = result["ok"]?.jsonPrimitive?.boolean ?: false
        if (ok) {
            val reply = result["reply"] as? JsonObject ?: JsonObject(emptyMap())
            return RunOutcome.Success(reply)
        }
        return when (result["kind"]?.jsonPrimitive?.content) {
            "cancelled" -> RunOutcome.Cancelled
            "failed" -> RunOutcome.Failed(
                message = result["message"]?.jsonPrimitive?.content ?: "The run failed.",
                step = result["step"]?.jsonPrimitive?.content,
                hint = result["hint"]?.jsonPrimitive?.content,
            )
            else -> RunOutcome.Other(result["message"]?.jsonPrimitive?.content ?: "The run failed.")
        }
    }

    /** Inject/refresh the engine's runtime config. */
    fun sendConfig(config: RuntimeConfig): Unit = channel.post(ParentMessages.config(config))

    /** Start one run; `onEvent` receives each raw run event for rendering. */
    fun startRun(request: RunRequest, onEvent: (JsonObject) -> Unit): ActiveRun {
        val runId = "run-${seq.incrementAndGet()}"
        val run = Run(onEvent, CompletableDeferred())
        runs[runId] = run
        channel.post(ParentMessages.start(runId, request, handlers.capabilities))
        return ActiveRun(runId, run.result) {
            if (runs.containsKey(runId)) channel.post(ParentMessages.cancel(runId))
        }
    }

    private suspend fun query(method: String, timeoutMs: Long): JsonElement? {
        val queryId = "q-${seq.incrementAndGet()}"
        val pending = CompletableDeferred<JsonElement?>()
        queries[queryId] = pending
        channel.post(ParentMessages.query(queryId, method))
        return try {
            withTimeout(timeoutMs) { pending.await() }
        } finally {
            queries.remove(queryId)
        }
    }

    /** The `/model`-picker catalogue. */
    suspend fun listModels(timeoutMs: Long = 10_000): List<ModelChoice> {
        val value = query("listModels", timeoutMs) ?: return emptyList()
        return wireJson.decodeFromJsonElement(value)
    }

    /** Activation-time health warnings (empty when all is well). */
    suspend fun startupWarnings(timeoutMs: Long = 30_000): List<String> {
        val value = query("startupWarnings", timeoutMs) ?: return emptyList()
        return wireJson.decodeFromJsonElement(value)
    }

    override fun close(): Unit = channel.close()
}
