package com.roombrowser.domain.agent

/**
 * Minimal line-oriented SSE (Server-Sent Events) parser.
 *
 * Feed it every raw line (without the trailing newline) as it arrives from
 * the HTTP stream. Whenever a blank line closes an event, [feed] returns the
 * joined `data:` payload; otherwise it returns null.
 *
 * Supported per the SSE spec subset used by chat providers:
 *  - `data: <text>` lines (multi-line data is joined with '\n')
 *  - `:` comment lines (heartbeats) are ignored
 *  - `event:` / `id:` / `retry:` lines are ignored (not needed here)
 *  - CR characters are trimmed (CRLF transports)
 */
class SseParser {

    private val dataLines = mutableListOf<String>()

    /**
     * @return the event data payload when the fed line closes an event,
     *         otherwise null (event still in progress / ignored line).
     */
    fun feed(rawLine: String): String? {
        val line = if (rawLine.endsWith("\r")) rawLine.dropLast(1) else rawLine
        if (line.isEmpty()) {
            if (dataLines.isEmpty()) return null
            val payload = dataLines.joinToString("\n")
            dataLines.clear()
            return payload
        }
        if (line.startsWith(":")) return null // comment / heartbeat
        if (line.startsWith("data:")) {
            var value = line.removePrefix("data:")
            if (value.startsWith(" ")) value = value.removePrefix(" ")
            dataLines.add(value)
        }
        return null
    }
}
