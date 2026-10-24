package dev.shipyu.mindverse.core.ai

import okio.BufferedSource

/**
 * Minimal blocking SSE reader shared by both transports (ticket 03). Runs on the IO
 * dispatcher inside `withContext`/`channelFlow`. Handles:
 * - `data: <payload>` lines (optional space after the colon),
 * - comment (`:`) and non-data lines (event names, ids, retries),
 * - OpenAI's `data: [DONE]` sentinel (terminates the stream),
 * - blank separators.
 */
internal object SseSupport {
    inline fun forEachDataLine(source: BufferedSource, onData: (String) -> Unit) {
        while (true) {
            val line = source.readUtf8Line() ?: return
            if (line.isEmpty() || line.startsWith(":") || !line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            if (payload == "[DONE]") return
            onData(payload)
        }
    }
}
