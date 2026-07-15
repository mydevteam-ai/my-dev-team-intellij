package ai.mydevteam.core

import ai.mydevteam.core.protocol.PROTOCOL_VERSION
import ai.mydevteam.core.protocol.RunRequest
import ai.mydevteam.core.protocol.RuntimeConfig
import ai.mydevteam.core.protocol.wireJson
import ai.mydevteam.core.sidecar.ChildMessage
import ai.mydevteam.core.sidecar.ParentMessages
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    @Test
    fun `run request always carries the protocol version and omits absent optionals`() {
        val request = RunRequest(prompt = "hello", offeredTools = listOf("read", "search"))
        val obj = wireJson.encodeToJsonElement(request).jsonObject
        assertEquals(PROTOCOL_VERSION, obj.getValue("protocolVersion").jsonPrimitive.content.toInt())
        assertEquals("hello", obj.getValue("prompt").jsonPrimitive.content)
        assertFalse(obj.containsKey("command"), "absent optionals must not ride as null")
        assertFalse(obj.containsKey("model"))
        assertFalse(obj.containsKey("attachments"))
    }

    @Test
    fun `runtime config defaults mirror the engine's shipped settings`() {
        val obj = wireJson.encodeToJsonElement(RuntimeConfig()).jsonObject
        assertEquals("auto", obj.getValue("workModel").jsonPrimitive.content)
        assertEquals("classifier", obj.getValue("triageMode").jsonPrimitive.content)
        assertEquals("auto", obj.getValue("planApproval").jsonPrimitive.content)
        assertEquals("100", obj.getValue("checkpointEverySteps").jsonPrimitive.content)
        assertFalse(obj.containsKey("ollamaEndpoint"), "unset endpoint must be omitted, not null")
    }

    @Test
    fun `parent start message matches the wire envelope`() {
        val msg = ParentMessages.start(
            "run-1",
            RunRequest(prompt = "p", offeredTools = listOf("read")),
            listOf("tool"),
        )
        assertEquals("start", msg.getValue("t").jsonPrimitive.content)
        assertEquals("run-1", msg.getValue("runId").jsonPrimitive.content)
        val request = msg.getValue("request").jsonObject
        assertEquals("p", request.getValue("prompt").jsonPrimitive.content)
    }

    @Test
    fun `child messages decode by discriminator and unknowns are preserved`() {
        val ready = ChildMessage.decode(
            wireJson.parseToJsonElement("""{"t":"ready","protocolVersion":5,"kind":"local"}""").jsonObject
        )
        assertIs<ChildMessage.Ready>(ready)
        assertEquals(5, ready.protocolVersion)

        val event = ChildMessage.decode(
            wireJson.parseToJsonElement(
                """{"t":"event","runId":"r1","event":{"type":"answer-delta","text":"hi"}}"""
            ).jsonObject
        )
        assertIs<ChildMessage.Event>(event)
        assertEquals("answer-delta", event.event.getValue("type").jsonPrimitive.content)

        val unknown = ChildMessage.decode(
            wireJson.parseToJsonElement("""{"t":"telemetry","n":1}""").jsonObject
        )
        assertIs<ChildMessage.Unknown>(unknown)

        assertNull(ChildMessage.decode(JsonObject(emptyMap())), "a frame without t is skipped")
    }

    @Test
    fun `invoke results carry ok and error shapes`() {
        val ok = ParentMessages.invokeResultOk("c1", wireJson.parseToJsonElement("\"file text\""))
        assertTrue(ok.getValue("ok").jsonPrimitive.content.toBoolean())
        val err = ParentMessages.invokeResultError("c1", "boom")
        assertEquals("boom", err.getValue("error").jsonPrimitive.content)
    }
}
