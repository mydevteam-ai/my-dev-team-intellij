/**
 * Side-question detection, mirroring the VS Code client (`sideQuestion` in
 * my-dev-team-vs-code/src/ui/chatParticipant.ts). A side question - "/ask <q>"
 * or a prompt led by the "btw" marker - runs as the engine's pinned /ask route
 * with no conversation history, and the client keeps it out of every later
 * turn's history, so mid-task curiosity never derails the ongoing work. The
 * detection lives in core (editor-free, unit-tested) because the chat surface
 * and any other entry point must agree on it exactly.
 */
package ai.mydevteam.core.chat

object SideQuestions {
    /**
     * The engine command a side question runs as. The engine registry pins it
     * to the oneshot route; an engine bundle that predates the command treats
     * the prompt as plain text (triage routes it), so version skew degrades -
     * the client-side history exclusion holds either way.
     */
    const val ASK_COMMAND = "ask"

    /**
     * Only a leading "btw" token counts - any case, optionally followed by
     * punctuation - so a prompt merely mentioning "btw" mid-sentence (or a
     * word merely starting with it) is never misrouted.
     */
    private val BTW_MARKER = Regex("""^btw\b[,:.!?]?\s*""", RegexOption.IGNORE_CASE)

    /** The explicit spelling; the marker is the typed shorthand for it. */
    private const val ASK_PREFIX = "/ask"

    /**
     * The side question carried by a chat input, or null when the input is not
     * one: no marker, a bare marker with nothing after it, or a word that only
     * starts like one. The returned text has the marker stripped and is
     * trimmed, ready to travel as the run's prompt.
     */
    fun questionOf(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith(ASK_PREFIX)) {
            // "/askme" is not the command; only "/ask" followed by whitespace
            // (or nothing, which carries no question and falls through).
            val rest = trimmed.removePrefix(ASK_PREFIX)
            if (rest.isNotEmpty() && !rest.first().isWhitespace()) {
                return null
            }
            return rest.trim().ifEmpty { null }
        }
        val marker = BTW_MARKER.find(trimmed) ?: return null
        return trimmed.substring(marker.value.length).trim().ifEmpty { null }
    }
}
