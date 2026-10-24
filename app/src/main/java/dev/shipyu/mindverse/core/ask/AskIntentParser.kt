package dev.shipyu.mindverse.core.ask

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Structured query intent (ticket 07: "metadata filters (category, time range, source app)
 * from the query language parsed by a small structured-intent pass (no LLM rewriting/agent
 * in v1)"). Deterministic local rules strip filter tokens out of the query and leave the
 * content question in [contentQuery] for FTS + vector retrieval.
 */
data class AskQueryIntent(
    /** The question after filter expressions are removed (falls back to the raw query). */
    val contentQuery: String,
    val category: String? = null,
    val sourceAppSubstring: String? = null,
    val sinceMs: Long? = null,
)

object AskIntentParser {

    fun parse(
        raw: String,
        clock: Clock,
        zone: ZoneId = ZoneId.systemDefault(),
        knownCategories: Set<String> = emptySet(),
    ): AskQueryIntent {
        var working = raw.trim()
        var category: String? = null
        var sourceApp: String? = null
        var sinceMs: Long? = null

        CATEGORY_TAG.find(working)?.let { match ->
            category = match.groupValues[1].lowercase()
            working = working.removeRange(match.range)
        }
        APP_TAG.find(working)?.let { match ->
            sourceApp = match.groupValues[1].lowercase()
            working = working.removeRange(match.range)
        }
        val todayStart = LocalDate.now(clock.withZone(zone)).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = clock.millis()
        for ((pattern, window) in TIME_PATTERNS) {
            val match = pattern.find(working) ?: continue
            sinceMs = when (window) {
                Window.TODAY -> todayStart
                Window.YESTERDAY -> todayStart - DAY_MS
                Window.WEEK -> now - 7 * DAY_MS
                Window.MONTH -> now - 30 * DAY_MS
            }
            working = working.removeRange(match.range)
            break
        }
        FROM_APP.find(working)?.let { match ->
            val candidate = match.groupValues[1].lowercase()
            if (candidate !in TIME_WORDS && candidate !in knownCategories) {
                sourceApp = sourceApp ?: candidate
                working = working.removeRange(match.range)
            }
        }

        if (category == null && knownCategories.isNotEmpty()) {
            val lower = working.lowercase()
            for (known in knownCategories) {
                val pattern = Regex("""\b(in|for|from)\s+${Regex.escape(known.lowercase())}\b""", RegexOption.IGNORE_CASE)
                val match = pattern.find(lower) ?: continue
                category = known.lowercase()
                working = working.removeRange(match.range)
                break
            }
        }

        val cleaned = working.replace(WHITESPACE, " ").trim()
        return AskQueryIntent(
            contentQuery = cleaned.ifEmpty { raw.trim() },
            category = category,
            sourceAppSubstring = sourceApp,
            sinceMs = sinceMs,
        )
    }

    private enum class Window { TODAY, YESTERDAY, WEEK, MONTH }

    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private val CATEGORY_TAG = Regex("""\b(?:category|cat):(\w+)\b""", RegexOption.IGNORE_CASE)
    private val APP_TAG = Regex("""\b(?:app|source):([\w.]+)\b""", RegexOption.IGNORE_CASE)
    private val FROM_APP = Regex("""\bfrom\s+([a-zA-Z][\w.]{2,})\b""")
    private val TIME_WORDS = setOf("today", "yesterday", "week", "month")
    private val WHITESPACE = Regex("""\s+""")

    private val TIME_PATTERNS = listOf(
        Regex("""\b(?:from\s+|since\s+)?today\b""", RegexOption.IGNORE_CASE) to Window.TODAY,
        Regex("""\b(?:from\s+|since\s+)?yesterday\b""", RegexOption.IGNORE_CASE) to Window.YESTERDAY,
        Regex("""\b(?:from\s+|since\s+)?(?:this|last|past)\s+week\b""", RegexOption.IGNORE_CASE) to Window.WEEK,
        Regex("""\blast\s+7\s+days\b""", RegexOption.IGNORE_CASE) to Window.WEEK,
        Regex("""\b(?:from\s+|since\s+)?(?:this|last|past)\s+month\b""", RegexOption.IGNORE_CASE) to Window.MONTH,
        Regex("""\blast\s+30\s+days\b""", RegexOption.IGNORE_CASE) to Window.MONTH,
    )
}
