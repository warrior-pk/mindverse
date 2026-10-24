package dev.shipyu.mindverse.core.pipeline

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The structured-output contract for Memory extraction (ticket 04 / spec "Processing
 * pipeline"). One JSON-Schema-ish document consumed by both provider transports
 * (Gemini maps it to OpenAPI style, OpenAI-compatible to `response_format.json_schema`).
 *
 * Every field beyond title/summary/tags is optional: on a model miss the parser stores
 * nulls rather than spending a second API call (ticket: "Null-able on model miss (extra
 * API calls forbidden)").
 */
object ExtractionContract {

    /** Timestamp fields are ISO-8601 strings; epoch millis are accepted leniently too. */
    val json: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("title") { put("type", "string") }
            putJsonObject("summary") { put("type", "string") }
            putJsonObject("category") { put("type", "string") }
            putJsonObject("extractedText") {
                put("type", "string")
                put("description", "Faithful transcription of the content (OCR/share body); omit if none.")
            }
            putJsonObject("tags") {
                put("type", "array")
                putJsonArray("items") { add("string") }
            }
            putJsonObject("entities") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("dates") {
                        put("type", "array")
                        putJsonArray("items") {
                            add(
                                buildJsonObject {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("text") { put("type", "string") }
                                        putJsonObject("at") { put("type", "string") }
                                    }
                                    putJsonArray("required") { add("text") }
                                },
                            )
                        }
                    }
                    putJsonObject("prices") {
                        put("type", "array")
                        putJsonArray("items") {
                            add(
                                buildJsonObject {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("text") { put("type", "string") }
                                        putJsonObject("amount") { put("type", "number") }
                                        putJsonObject("currency") { put("type", "string") }
                                    }
                                    putJsonArray("required") { add("text") }
                                },
                            )
                        }
                    }
                    putJsonObject("places") {
                        put("type", "array")
                        putJsonArray("items") {
                            add(
                                buildJsonObject {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("name") { put("type", "string") }
                                        putJsonObject("latitude") { put("type", "number") }
                                        putJsonObject("longitude") { put("type", "number") }
                                    }
                                    putJsonArray("required") { add("name") }
                                },
                            )
                        }
                    }
                    putJsonObject("events") {
                        put("type", "array")
                        putJsonArray("items") {
                            add(
                                buildJsonObject {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("text") { put("type", "string") }
                                        putJsonObject("start") { put("type", "string") }
                                        putJsonObject("end") { put("type", "string") }
                                    }
                                    putJsonArray("required") { add("text") }
                                },
                            )
                        }
                    }
                    putJsonObject("deadlines") {
                        put("type", "array")
                        putJsonArray("items") {
                            add(
                                buildJsonObject {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("text") { put("type", "string") }
                                        putJsonObject("at") { put("type", "string") }
                                    }
                                    putJsonArray("required") { add("text") }
                                },
                            )
                        }
                    }
                    putJsonObject("ticket") {
                        put("type", "object")
                        put("description", "Only when the content is a travel/event ticket.")
                        putJsonObject("properties") {
                            putJsonObject("pnr") { put("type", "string") }
                            putJsonObject("seat") { put("type", "string") }
                            putJsonObject("gate") { put("type", "string") }
                            putJsonObject("departure") { put("type", "string") }
                            putJsonObject("barcode") {
                                put("type", "object")
                                put("description", "Barcode/QR presence + geometry (Vault M3 consumes it).")
                                putJsonObject("properties") {
                                    putJsonObject("format") { put("type", "string") }
                                    putJsonObject("value") { put("type", "string") }
                                    putJsonObject("x") { put("type", "number") }
                                    putJsonObject("y") { put("type", "number") }
                                    putJsonObject("width") { put("type", "number") }
                                    putJsonObject("height") { put("type", "number") }
                                }
                            }
                        }
                    }
                    putJsonObject("people") {
                        put("type", "array")
                        putJsonArray("items") {
                            add(
                                buildJsonObject {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("name") { put("type", "string") }
                                        putJsonObject("handle") { put("type", "string") }
                                    }
                                    putJsonArray("required") { add("name") }
                                },
                            )
                        }
                    }
                }
            }
        }
        putJsonArray("required") { add("title"); add("summary"); add("tags") }
    }
}

/**
 * Prompt construction per Capture kind (ticket 04): image → vision prompt, text/URL →
 * passthrough prompt. URL scraping is deliberately NOT performed in M1: fetching a page
 * would add an egress path outside the AI chokepoint, so the URL plus any share body is
 * passed through for the model to read (documented in the ticket).
 */
object ExtractionPrompts {
    const val SYSTEM: String =
        "You convert one captured item into one structured Memory for a local second-brain app. " +
            "Respond ONLY with a JSON object matching the requested schema. " +
            "Transcribe visible/readable content faithfully into extractedText. " +
            "Use null or omit any field you cannot determine from the content. " +
            "Never invent facts, never ask questions, never add prose outside the JSON."

    fun textPrompt(body: String, deepLink: String?, sourceApp: String?): String = buildString {
        appendLine("Extract a structured Memory from the capture below.")
        if (sourceApp != null) appendLine("Source app: $sourceApp")
        if (deepLink != null) appendLine("Source URL: $deepLink")
        appendLine("Capture content:")
        appendLine("<<<")
        append(body.take(MAX_BODY_CHARS))
        appendLine()
        append(">>>")
    }

    fun imagePrompt(deepLink: String?, sourceApp: String?): String = buildString {
        append("Extract a structured Memory from this image. ")
        append("If it is a screenshot or document, transcribe all legible text into extractedText ")
        append("and keep titles/summaries faithful to what is visible. ")
        if (sourceApp != null) append("Source app: $sourceApp. ")
        if (deepLink != null) append("Source URL: $deepLink.")
    }.trim()

    /** Bounds prompt cost; the full text still lands in the Memory via extractedText when short. */
    private const val MAX_BODY_CHARS = 24_000
}
