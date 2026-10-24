package dev.shipyu.mindverse.core.ask

import dev.shipyu.mindverse.core.data.FixedClock
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Query-intent seam (ticket 07): filter expressions become metadata filters, never LLM. */
class AskIntentParserTest {

    private val clock = FixedClock(5_000_000_000L) // ~1970-03-28; windows are relative
    private val zone = ZoneId.of("UTC")

    @Test
    fun `tag filters are stripped from the content query`() {
        val intent = AskIntentParser.parse("receipts category:food app:com.notes.app", clock, zone)
        assertEquals("receipts", intent.contentQuery)
        assertEquals("food", intent.category)
        assertEquals("com.notes.app", intent.sourceAppSubstring)
    }

    @Test
    fun `natural-language time words resolve to windows`() {
        val week = AskIntentParser.parse("what did I capture this week", clock, zone)
        assertEquals("what did I capture", week.contentQuery)
        assertEquals(clock.millis() - 7 * DAY, week.sinceMs)

        val today = AskIntentParser.parse("flights today", clock, zone)
        assertEquals("flights", today.contentQuery)
        assertEquals(todayStartMs(), today.sinceMs)
    }

    @Test
    fun `from app phrasing is a source filter but not for time words or categories`() {
        val fromApp = AskIntentParser.parse("boarding pass from airline.app", clock, zone, setOf("travel"))
        assertEquals("boarding pass", fromApp.contentQuery)
        assertEquals("airline.app", fromApp.sourceAppSubstring)

        val fromCategory = AskIntentParser.parse("notes from travel", clock, zone, setOf("travel"))
        assertEquals("travel", fromCategory.category)

        val fromTime = AskIntentParser.parse("anything from yesterday", clock, zone)
        assertEquals("anything", fromTime.contentQuery)
        assertEquals(todayStartMs() - DAY, fromTime.sinceMs)
    }

    @Test
    fun `plain questions pass through untouched`() {
        val intent = AskIntentParser.parse("what gate is my tokyo flight?", clock, zone)
        assertEquals("what gate is my tokyo flight?", intent.contentQuery)
        assertNull(intent.category)
        assertNull(intent.sourceAppSubstring)
        assertNull(intent.sinceMs)
    }

    private fun todayStartMs(): Long =
        java.time.LocalDate.now(clock.withZone(zone)).atStartOfDay(zone).toInstant().toEpochMilli()

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
