package dev.shipyu.mindverse.core.pipeline

import dev.shipyu.mindverse.core.data.MemoryDraft
import dev.shipyu.mindverse.core.data.MemoryItemDraft
import dev.shipyu.mindverse.core.data.db.MemoryItemKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Lenient mapping of the model's structured output into a [MemoryDraft] + typed Entity
 * rows (ticket 04). A model miss degrades field-by-field to null; it never triggers a
 * second API call. Only a reply with neither a usable title nor summary is rejected
 * (returns null → the worker treats it as a transient bad response).
 *
 * Ticket barcode/QR geometry is parsed but intentionally not persisted: storage owns only
 * PNR/seat/gate/departure until the Vault (M3) lands geometry columns (see MemoryItemEntity).
 */
object ExtractionParser {

    fun parse(root: JsonObject): ExtractionResult? {
        val extractedText = root.string("extractedText")?.trim()?.takeUnless { it.isEmpty() }
        val summary = root.string("summary")?.trim()?.takeUnless { it.isEmpty() }
        val title = root.string("title")?.trim()?.takeUnless { it.isEmpty() }
            ?: summary?.take(80)
            ?: return null
        if (summary == null && title.isEmpty()) return null

        val entities = (root["entities"] as? JsonObject)
        val items = ArrayList<MemoryItemDraft>()

        entities?.objects("dates")?.forEach { date ->
            val text = date.string("text")?.trim().orEmpty()
            if (text.isNotEmpty()) {
                items += MemoryItemDraft(
                    kind = MemoryItemKind.DATE,
                    text = text,
                    timestampStartMs = date.instant("at"),
                )
            }
        }
        entities?.objects("prices")?.forEach { price ->
            val text = price.string("text")?.trim().orEmpty()
            if (text.isNotEmpty()) {
                items += MemoryItemDraft(
                    kind = MemoryItemKind.PRICE,
                    text = text,
                    priceAmountMicros = price.amountMicros("amount"),
                    priceCurrency = price.string("currency")?.trim()?.uppercase()?.takeUnless { it.isEmpty() },
                )
            }
        }
        entities?.objects("places")?.forEach { place ->
            val name = place.string("name")?.trim().orEmpty()
            if (name.isNotEmpty()) {
                items += MemoryItemDraft(
                    kind = MemoryItemKind.PLACE,
                    locationName = name,
                    latitude = place.double("latitude"),
                    longitude = place.double("longitude"),
                )
            }
        }
        entities?.objects("events")?.forEach { event ->
            val text = event.string("text")?.trim().orEmpty()
            if (text.isNotEmpty()) {
                items += MemoryItemDraft(
                    kind = MemoryItemKind.EVENT,
                    text = text,
                    timestampStartMs = event.instant("start"),
                    timestampEndMs = event.instant("end"),
                )
            }
        }
        entities?.objects("deadlines")?.forEach { deadline ->
            val text = deadline.string("text")?.trim().orEmpty()
            if (text.isNotEmpty()) {
                items += MemoryItemDraft(
                    kind = MemoryItemKind.DEADLINE,
                    text = text,
                    timestampStartMs = deadline.instant("at"),
                )
            }
        }
        entities?.let { it["ticket"] as? JsonObject }?.let { ticket ->
            val pnr = ticket.string("pnr")?.trim()?.takeUnless { it.isEmpty() }
            val seat = ticket.string("seat")?.trim()?.takeUnless { it.isEmpty() }
            val gate = ticket.string("gate")?.trim()?.takeUnless { it.isEmpty() }
            val departure = ticket.instant("departure")
            if (pnr != null || seat != null || gate != null || departure != null) {
                items += MemoryItemDraft(
                    kind = MemoryItemKind.TICKET,
                    text = pnr,
                    ticketCode = pnr,
                    ticketSeat = seat,
                    ticketGate = gate,
                    timestampStartMs = departure,
                )
            }
        }
        entities?.objects("people")?.forEach { person ->
            val name = person.string("name")?.trim().orEmpty()
            if (name.isNotEmpty()) {
                items += MemoryItemDraft(
                    kind = MemoryItemKind.PERSON,
                    text = name,
                    personHandle = person.string("handle")?.trim()?.takeUnless { it.isEmpty() },
                )
            }
        }

        return ExtractionResult(
            title = title,
            summary = summary ?: title,
            category = root.string("category")?.trim()?.lowercase()?.takeUnless { it.isEmpty() },
            tags = root.strings("tags"),
            extractedText = extractedText,
            items = items,
        )
    }

    /** Departure time of the first ticket Entity, used by the Pin probe. */
    fun ticketDeparture(result: ExtractionResult): Long? =
        result.items.firstOrNull { it.kind == MemoryItemKind.TICKET }?.timestampStartMs

    // --- JSON helpers ----------------------------------------------------------------

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.strings(key: String): List<String> {
        val array = this[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeUnless { it.isEmpty() }
        }.distinct().take(12)
    }

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    /** Amount → base-unit micros (2.50 → 2_500_000), matching MemoryItemEntity's contract. */
    private fun JsonObject.amountMicros(key: String): Long? {
        val element = this[key] as? JsonPrimitive ?: return null
        element.longOrNull?.let { return it * 1_000_000 }
        element.doubleOrNull?.let { return Math.round(it * 1_000_000) }
        return null
    }

    /**
     * Lenient timestamp: ISO-8601 instant/offset/local date-time/date, or epoch numbers
     * (seconds vs millis distinguished by magnitude). Anything else → null (model miss).
     */
    internal fun parseInstant(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isString) {
            val number = primitive.longOrNull ?: primitive.doubleOrNull?.toLong() ?: return null
            return if (number > 100_000_000_000L) number else number * 1000L
        }
        val text = primitive.content.trim()
        if (text.isEmpty()) return null
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun JsonObject.instant(key: String): Long? = parseInstant(this[key])
}

/** Parser output: the Memory payload plus the typed Entity rows it produced. */
data class ExtractionResult(
    val title: String,
    val summary: String,
    val category: String?,
    val tags: List<String>,
    val extractedText: String?,
    val items: List<MemoryItemDraft>,
) {
    fun toDraft(captureId: String): MemoryDraft = MemoryDraft(
        sourceCaptureIds = listOf(captureId),
        title = title,
        summary = summary,
        category = category,
        tags = tags,
        extractedText = extractedText,
        items = items,
        // Embedding is the next pipeline stage (on-device engine, ticket 04).
        embedding = null,
    )
}
