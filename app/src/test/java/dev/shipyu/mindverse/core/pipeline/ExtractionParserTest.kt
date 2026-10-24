package dev.shipyu.mindverse.core.pipeline

import dev.shipyu.mindverse.core.data.db.MemoryItemKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser seam (ticket 04): the model's structured output → MemoryDraft + typed Entities.
 * Fixtures are worked examples of the contract, not re-derivations of parser logic.
 */
class ExtractionParserTest {

    private fun parse(json: String) = ExtractionParser.parse(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `full contract maps every entity kind`() {
        val result = parse(
            """
            {
              "title": "Flight AI 302 to Tokyo",
              "summary": "Boarding pass departs 09:40 from gate 12",
              "category": "Travel",
              "extractedText": "PNR ABC123 seat 14A gate 12",
              "tags": ["flight", "tokyo", " flight "],
              "entities": {
                "dates": [{"text": "2 Nov 2026", "at": "2026-11-02T00:00:00Z"}],
                "prices": [{"text": "JPY 42,000", "amount": 42000, "currency": "jpy"}],
                "places": [{"name": "Haneda Airport", "latitude": 35.5494, "longitude": 139.7798}],
                "events": [{"text": "Boarding", "start": "2026-11-02T09:10:00Z", "end": "2026-11-02T09:40:00Z"}],
                "deadlines": [{"text": "Check-in closes", "at": "2026-11-02T08:40:00Z"}],
                "ticket": {"pnr": "ABC123", "seat": "14A", "gate": "12", "departure": "2026-11-02T09:40:00Z"},
                "people": [{"name": "Aiko Tanaka", "handle": "@aiko"}]
              }
            }
            """.trimIndent(),
        )!!

        assertEquals("Flight AI 302 to Tokyo", result.title)
        assertEquals("travel", result.category)
        assertEquals(listOf("flight", "tokyo"), result.tags)
        assertEquals("PNR ABC123 seat 14A gate 12", result.extractedText)

        val kinds = result.items.map { it.kind }
        assertEquals(
            listOf(
                MemoryItemKind.DATE,
                MemoryItemKind.PRICE,
                MemoryItemKind.PLACE,
                MemoryItemKind.EVENT,
                MemoryItemKind.DEADLINE,
                MemoryItemKind.TICKET,
                MemoryItemKind.PERSON,
            ),
            kinds,
        )
        val price = result.items.first { it.kind == MemoryItemKind.PRICE }
        assertEquals(42_000_000_000L, price.priceAmountMicros) // 42,000 JPY in micros
        assertEquals("JPY", price.priceCurrency)
        val place = result.items.first { it.kind == MemoryItemKind.PLACE }
        assertEquals(35.5494, place.latitude!!, 1e-9)
        val ticket = result.items.first { it.kind == MemoryItemKind.TICKET }
        assertEquals("ABC123", ticket.ticketCode)
        assertEquals("14A", ticket.ticketSeat)
        assertEquals("12", ticket.ticketGate)
        assertEquals(1_793_612_400_000L, ticket.timestampStartMs) // 2026-11-02T09:40:00Z
        val person = result.items.first { it.kind == MemoryItemKind.PERSON }
        assertEquals("Aiko Tanaka", person.text)
        assertEquals("@aiko", person.personHandle)

        assertEquals(ticket.timestampStartMs, ExtractionParser.ticketDeparture(result))
    }

    @Test
    fun `model miss degrades to nulls instead of failing`() {
        val result = parse(
            """
            {
              "title": "Dentist appointment",
              "summary": "Tuesday at 4pm",
              "tags": [],
              "entities": {"places": [{"name": "Smile Clinic"}], "ticket": {"pnr": null}}
            }
            """.trimIndent(),
        )!!
        assertNull(result.category)
        assertNull(result.extractedText)
        assertTrue(result.tags.isEmpty())
        assertEquals(listOf(MemoryItemKind.PLACE), result.items.map { it.kind })
        assertNull(result.items.first().latitude)
        assertNull(ExtractionParser.ticketDeparture(result))
    }

    @Test
    fun `title falls back to summary and unusable replies are rejected`() {
        val titleless = parse("""{"summary": "A long note about the trip", "tags": []}""")!!
        assertEquals("A long note about the trip", titleless.title)

        assertNull(parse("""{"tags": ["orphan"]}"""))
        assertNull(parse("""{}"""))
    }

    @Test
    fun `timestamps accept epoch numbers and local forms`() {
        assertEquals(1_793_612_400_000L, ExtractionParser.parseInstant(Json.parseToJsonElement("1793612400")))
        assertEquals(1_793_612_400_000L, ExtractionParser.parseInstant(Json.parseToJsonElement("1793612400000")))
        assertEquals(1_793_577_600_000L, ExtractionParser.parseInstant(Json.parseToJsonElement("\"2026-11-02\"")))
        assertEquals(1_793_611_800_000L, ExtractionParser.parseInstant(Json.parseToJsonElement("\"2026-11-02T09:30:00\"")))
        assertNull(ExtractionParser.parseInstant(Json.parseToJsonElement("\"next Tuesday\"")))
        assertNull(ExtractionParser.parseInstant(Json.parseToJsonElement("null")))
    }

    @Test
    fun `schema declares the ticket contract fields`() {
        val text = ExtractionContract.json.toString()
        for (key in listOf(
            "title", "summary", "category", "tags", "extractedText",
            "dates", "prices", "places", "events", "deadlines", "ticket", "people",
            "pnr", "seat", "gate", "departure", "barcode", "latitude", "longitude", "handle",
        )) {
            assertTrue("schema missing $key", text.contains("\"$key\""))
        }
    }
}
