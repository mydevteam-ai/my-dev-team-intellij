/**
 * The sidecar wire, mirroring my-dev-team-vs-code/src/sidecar/transport.ts:
 * the parent (this client) posts ParentMessage frames and receives
 * ChildMessage frames, each one JSON object per line (NDJSON). Parent messages
 * are built as JsonObjects (we produce them, so the envelope is fully under
 * our control); child messages are decoded by their `t` discriminator into a
 * sealed type, with unknown discriminators preserved rather than fatal so a
 * newer engine degrades instead of breaking the channel.
 */
package ai.mydevteam.core.sidecar

import ai.mydevteam.core.protocol.RunRequest
import ai.mydevteam.core.protocol.RuntimeConfig
import ai.mydevteam.core.protocol.wireJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Builders for the messages the parent sends to the engine child. */
object ParentMessages {
    /** Inject/refresh the engine's runtime config (handshake, then on settings change). */
    fun config(config: RuntimeConfig): JsonObject = buildJsonObject {
        put("t", "config")
        put("config", wireJson.encodeToJsonElement(config))
    }

    /** Start a run; `capabilities` advertises what this client can answer. */
    fun start(runId: String, request: RunRequest, capabilities: List<String>): JsonObject =
        buildJsonObject {
            put("t", "start")
            put("runId", runId)
            put("request", wireJson.encodeToJsonElement(request))
            put("capabilities", wireJson.encodeToJsonElement(capabilities))
        }

    fun cancel(runId: String): JsonObject = buildJsonObject {
        put("t", "cancel")
        put("runId", runId)
    }

    /** Answer an `invoke` with the capability's result. */
    fun invokeResultOk(callId: String, result: JsonElement): JsonObject = buildJsonObject {
        put("t", "invoke-result")
        put("callId", callId)
        put("ok", true)
        put("result", result)
    }

    /** Answer an `invoke` with the error it threw. */
    fun invokeResultError(callId: String, error: String): JsonObject = buildJsonObject {
        put("t", "invoke-result")
        put("callId", callId)
        put("ok", false)
        put("error", error)
    }

    /** Ask the engine a one-shot question ("listModels" or "startupWarnings"). */
    fun query(queryId: String, method: String): JsonObject = buildJsonObject {
        put("t", "query")
        put("queryId", queryId)
        put("method", method)
    }
}

/** The messages the engine child sends back, decoded by their `t` discriminator. */
sealed interface ChildMessage {
    /** The readiness handshake: the child's engine is up and speaks `protocolVersion`. */
    data class Ready(val protocolVersion: Int, val kind: String) : ChildMessage

    /** A run event, forwarded verbatim for rendering (the webview consumes the raw JSON). */
    data class Event(val runId: String, val event: JsonObject) : ChildMessage

    /** The engine asks this client to run one capability and answer with an invoke-result. */
    data class Invoke(
        val runId: String,
        val callId: String,
        val capability: String,
        val payload: JsonObject,
    ) : ChildMessage

    /** A debug-log entry from the child's engine (only when debug logging is on). */
    data class Debug(val entry: JsonObject) : ChildMessage

    /** The run settled; `result` is the wire's RunResult shape ({ok: true, reply} or a failure). */
    data class Result(val runId: String, val result: JsonObject) : ChildMessage

    /** A query answer. */
    data class QueryResult(
        val queryId: String,
        val ok: Boolean,
        val value: JsonElement?,
        val error: String?,
    ) : ChildMessage

    /** A `t` this client does not know: ignored by callers, never fatal. */
    data class Unknown(val raw: JsonObject) : ChildMessage

    companion object {
        /** Decode one frame; returns null when the object has no readable `t`. */
        fun decode(obj: JsonObject): ChildMessage? {
            val t = (obj["t"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
            return try {
                when (t) {
                    "ready" -> Ready(
                        protocolVersion = obj.getValue("protocolVersion").jsonPrimitive.int,
                        kind = obj.getValue("kind").jsonPrimitive.content,
                    )
                    "event" -> Event(
                        runId = obj.getValue("runId").jsonPrimitive.content,
                        event = obj.getValue("event").jsonObject,
                    )
                    "invoke" -> Invoke(
                        runId = obj.getValue("runId").jsonPrimitive.content,
                        callId = obj.getValue("callId").jsonPrimitive.content,
                        capability = obj.getValue("capability").jsonPrimitive.content,
                        payload = obj["payload"] as? JsonObject ?: JsonObject(emptyMap()),
                    )
                    "debug" -> Debug(entry = obj.getValue("entry").jsonObject)
                    "result" -> Result(
                        runId = obj.getValue("runId").jsonPrimitive.content,
                        result = obj.getValue("result").jsonObject,
                    )
                    "query-result" -> QueryResult(
                        queryId = obj.getValue("queryId").jsonPrimitive.content,
                        ok = obj.getValue("ok").jsonPrimitive.boolean,
                        value = obj["value"],
                        error = (obj["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                    )
                    else -> Unknown(obj)
                }
            } catch (e: Exception) {
                // A malformed known message is skipped like a malformed line.
                null
            }
        }
    }
}
