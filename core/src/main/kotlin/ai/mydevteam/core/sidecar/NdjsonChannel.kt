/**
 * The NDJSON duplex channel to the sidecar child: one JSON object per line,
 * UTF-8, over any stream pair (production wires a spawned Node process's
 * stdin/stdout; tests wire piped streams). Mirrors the framing of the engine
 * repo's stdio entry (sidecar/ndjson.ts): a malformed line is skipped rather
 * than fatal, and the incoming stream ending closes the channel.
 */
package ai.mydevteam.core.sidecar

import ai.mydevteam.core.protocol.wireJson
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class NdjsonChannel(
    output: OutputStream,
    input: InputStream,
    private val onMessage: (JsonObject) -> Unit,
    private val onClose: (reason: String) -> Unit,
) : AutoCloseable {
    private val writer: BufferedWriter = output.bufferedWriter(Charsets.UTF_8)
    private val reader: BufferedReader = input.bufferedReader(Charsets.UTF_8)
    private val closed = AtomicBoolean(false)

    private val readerThread = Thread({
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val msg = try {
                    wireJson.parseToJsonElement(line) as? JsonObject
                } catch (e: Exception) {
                    null
                }
                if (msg != null) onMessage(msg)
            }
            fireClose("stream ended")
        } catch (e: Exception) {
            fireClose(e.message ?: "read failed")
        }
    }, "devteam-sidecar-reader").apply {
        isDaemon = true
        start()
    }

    /** Send one message. Throws when the peer is gone (the caller handles respawn). */
    fun post(msg: JsonObject) {
        synchronized(writer) {
            writer.write(msg.toString())
            writer.write("\n")
            writer.flush()
        }
    }

    private fun fireClose(reason: String) {
        if (closed.compareAndSet(false, true)) onClose(reason)
    }

    override fun close() {
        fireClose("channel disposed")
        try {
            writer.close()
        } catch (ignored: Exception) {
        }
        try {
            reader.close()
        } catch (ignored: Exception) {
        }
        readerThread.interrupt()
    }
}
