/**
 * The JCEF chat surface: the transcript, input and controls all live in one
 * embedded page (chat.html). Kotlin -> page: `window.devteam.receive(<json>)`
 * with the raw run events (the page owns all rendering). Page -> Kotlin: one
 * JBCefJSQuery carrying a small JSON envelope ({kind: "ready"|"send"|"cancel"|
 * "clear", ...}). Messages posted before the page signals ready are queued.
 */
package ai.mydevteam.intellij.ui

import ai.mydevteam.core.protocol.wireJson
import ai.mydevteam.core.sidecar.RunOutcome
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import javax.swing.JComponent

class ChatPanel(
    private val controller: ChatController,
    parentDisposable: Disposable,
) {
    private val log = logger<ChatPanel>()
    private val browser = JBCefBrowser()
    private val fromPage = JBCefJSQuery.create(browser as JBCefBrowserBase)

    private val pending = ArrayDeque<JsonObject>()

    @Volatile
    private var pageReady = false

    val component: JComponent get() = browser.component

    init {
        Disposer.register(parentDisposable, browser)
        Disposer.register(parentDisposable, fromPage)
        fromPage.addHandler { raw ->
            try {
                handleFromPage(wireJson.parseToJsonElement(raw) as JsonObject)
            } catch (e: Exception) {
                log.warn("Bad message from the chat page: $raw", e)
            }
            null
        }
        browser.loadHTML(pageHtml())
    }

    private fun pageHtml(): String {
        val template = javaClass.getResourceAsStream("/webview/chat.html")
            ?.readBytes()?.toString(StandardCharsets.UTF_8)
            ?: error("chat.html is missing from the plugin resources")
        // The page calls __postToIde(json) to reach Kotlin; inject the query stub.
        return template.replace("/*__POST_TO_IDE__*/", fromPage.inject("json"))
    }

    private fun handleFromPage(msg: JsonObject) {
        when (msg["kind"]?.jsonPrimitive?.content) {
            "ready" -> {
                pageReady = true
                synchronized(pending) {
                    pending.forEach(::postNow)
                    pending.clear()
                }
            }
            "send" -> {
                val text = msg["text"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (text.isEmpty()) return
                if (text == "/clear") {
                    controller.clearHistory()
                    post(buildJsonObject { put("kind", "cleared") })
                    return
                }
                post(buildJsonObject {
                    put("kind", "user")
                    put("text", text)
                })
                controller.send(
                    prompt = text,
                    onEvent = { event ->
                        post(buildJsonObject {
                            put("kind", "event")
                            put("event", event)
                        })
                    },
                    onOutcome = { outcome -> post(outcomeMessage(outcome)) },
                    onFailedToStart = { message ->
                        post(buildJsonObject {
                            put("kind", "fatal")
                            put("message", message)
                        })
                    },
                )
            }
            "cancel" -> controller.cancel()
        }
    }

    private fun outcomeMessage(outcome: RunOutcome): JsonObject = buildJsonObject {
        put("kind", "outcome")
        when (outcome) {
            is RunOutcome.Success -> {
                put("ok", true)
                put("reply", outcome.reply)
            }
            is RunOutcome.Failed -> {
                put("ok", false)
                put("message", outcome.message)
                outcome.hint?.let { put("hint", it) }
                outcome.step?.let { put("step", it) }
            }
            is RunOutcome.Cancelled -> {
                put("ok", false)
                put("cancelled", true)
                put("message", "The run was cancelled.")
            }
            is RunOutcome.Other -> {
                put("ok", false)
                put("message", outcome.message)
            }
        }
    }

    /** Post one message to the page, queueing until it signalled ready. */
    private fun post(msg: JsonObject) {
        if (!pageReady) {
            synchronized(pending) {
                if (!pageReady) {
                    pending.add(msg)
                    return
                }
            }
        }
        postNow(msg)
    }

    private fun postNow(msg: JsonObject) {
        browser.cefBrowser.executeJavaScript(
            "window.devteam && window.devteam.receive($msg);",
            browser.cefBrowser.url,
            0,
        )
    }
}
