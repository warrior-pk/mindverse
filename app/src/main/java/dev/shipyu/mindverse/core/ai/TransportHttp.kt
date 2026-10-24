package dev.shipyu.mindverse.core.ai

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Shared wire plumbing for both transports (ticket 03): request build + send, HTTP
 * status → [TransportFailure.Http] mapping, IOException → [TransportFailure.Unreachable]
 * mapping, and the blocking SSE loop. Provider differences (auth header, URL, delta
 * decoding) stay with the transports; the shape here is identical for both.
 */
internal object TransportHttp {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Send [payload] JSON to [url] with [headers]; never maps non-HTTP failures itself —
     * the caller routes through [wire] which does. Returns the open [Response]; caller closes.
     */
    fun post(client: OkHttpClient, url: String, headers: List<Pair<String, String>>, payload: ByteArray, providerId: String): Response {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .post(payload.toRequestBody(jsonMediaType))
            .build()
        return try {
            client.newCall(request).execute()
        } catch (io: IOException) {
            throw AiTransportException(TransportFailure.Unreachable(io), payload.size, providerId)
        }
    }

    /** Non-2xx → typed HTTP failure carrying a bounded body summary. */
    fun checkSuccess(response: Response, bytesOut: Int, providerId: String) {
        if (!response.isSuccessful) {
            val body = response.body?.string().orEmpty()
            throw AiTransportException(
                failure = TransportFailure.Http(response.code, body.take(ERROR_BODY_LIMIT)),
                bytesOut = bytesOut,
                where = providerId,
            )
        }
    }

    /**
     * Wrap a wire-reading [block]: rethrow typed failures untouched, map stray IO errors
     * to [TransportFailure.Unreachable] ([bytesOut] is the attempted payload size).
     */
    inline fun <T> wire(providerId: String, bytesOut: Int, block: () -> T): T =
        try {
            block()
        } catch (wire: AiTransportException) {
            throw wire
        } catch (io: IOException) {
            throw AiTransportException(TransportFailure.Unreachable(io), bytesOut, providerId)
        }

    /**
     * Blocking SSE loop over an open response source: decodes each `data:` line through
     * [decodeDelta] (empty → skip), emits non-empty deltas to [onData], returns the
     * aggregate text. Returns on EOF and on OpenAI's `[DONE]` sentinel.
     */
    suspend fun readSse(
        response: Response,
        bytesOut: Int,
        providerId: String,
        decodeDelta: (String) -> String,
        onData: suspend (String) -> Unit,
    ): String = wire(providerId, bytesOut) {
        val source = checkNotNull(response.body?.source())
        val aggregate = StringBuilder()
        SseSupport.forEachDataLine(source) { dataLine ->
            val delta = decodeDelta(dataLine)
            if (delta.isNotEmpty()) {
                aggregate.append(delta)
                onData(delta)
            }
        }
        aggregate.toString()
    }

    private const val ERROR_BODY_LIMIT = 400
}
