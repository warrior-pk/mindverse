package dev.shipyu.mindverse.core.ask

import dev.shipyu.mindverse.core.data.MemoryRepository
import dev.shipyu.mindverse.core.data.db.MemoryEntity
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.embed.EmbeddingEngine
import java.time.Clock
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.max

/** One ranked Memory returned by hybrid retrieval, with its source context resolved. */
data class ScoredMemory(
    val memory: MemoryEntity,
    val score: Double,
    val sourceApp: String?,
)

/**
 * Hybrid retrieval (ticket 07 / spec "Ask"): FTS5 BM25 + sqlite-vec cosine similarity over
 * the on-device embedding, blended with recency decay and narrowed by the metadata filters
 * parsed from the query. Query embedding uses the exact same [EmbeddingEngine] as the
 * pipeline (ticket: "no cloud embed; consistency matters, results differ otherwise").
 */
class HybridRetriever(
    private val db: MindverseDatabase,
    private val repository: MemoryRepository,
    private val embedding: EmbeddingEngine,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    suspend fun retrieve(query: String, topK: Int = DEFAULT_TOP_K): List<ScoredMemory> {
        require(topK > 0)
        val active = repository.listActive()
        if (active.isEmpty()) return emptyList()

        val knownCategories = active.mapNotNull { it.category?.lowercase() }.toSet()
        val intent = AskIntentParser.parse(query, clock, zone, knownCategories)

        val captures = db.captureDao().listActive().associateBy { it.id }
        val pool = active.filter { memory ->
            val capture = memory.sourceCaptureIds.firstNotNullOfOrNull { captures[it] }
            (intent.category == null || memory.category?.equals(intent.category, ignoreCase = true) == true) &&
                (intent.sinceMs == null || memory.createdAt >= intent.sinceMs) &&
                (intent.sourceAppSubstring == null ||
                    capture?.sourceApp?.contains(intent.sourceAppSubstring, ignoreCase = true) == true)
        }
        if (pool.isEmpty()) return emptyList()
        val poolById = pool.associateBy { it.id }

        val ftsRanks = LinkedHashMap<String, Int>()
        repository.searchByFullText(intent.contentQuery, CANDIDATE_WINDOW).forEach { hit ->
            if (hit.id in poolById) ftsRanks.putIfAbsent(hit.id, ftsRanks.size)
        }
        repository.searchByAnyToken(intent.contentQuery, CANDIDATE_WINDOW).forEach { hit ->
            if (hit.id in poolById) ftsRanks.putIfAbsent(hit.id, ftsRanks.size)
        }

        val vecScores = HashMap<String, Double>()
        val queryVec = embedding.embed(intent.contentQuery)
        if (queryVec.any { it != 0f }) {
            repository.searchByVector(queryVec, CANDIDATE_WINDOW).forEach { hit ->
                // KNN always returns *some* nearest rows; the similarity floor keeps
                // unrelated memories out of the candidate set on small databases.
                val similarity = (1.0 - hit.distance / 2.0).coerceIn(0.0, 1.0)
                if (hit.memoryId in poolById && similarity >= VEC_MIN_SIMILARITY) {
                    vecScores[hit.memoryId] = similarity
                }
            }
        }

        val hasFilters = intent.category != null || intent.sourceAppSubstring != null || intent.sinceMs != null
        val candidateIds = (ftsRanks.keys + vecScores.keys).ifEmpty {
            // Filter-only queries ("everything from today"): rank the filtered pool.
            // Plain queries with zero matches stay empty — that is the honest answer.
            if (hasFilters) poolById.keys else emptySet()
        }
        val now = clock.millis()
        return candidateIds.mapNotNull { id ->
            val memory = poolById[id] ?: return@mapNotNull null
            val ftsScore = ftsRanks[id]?.let { rank -> 1.0 / (1.0 + rank * 0.25) } ?: 0.0
            val vecScore = vecScores[id] ?: 0.0
            val ageDays = max(0.0, (now - memory.createdAt) / DAY_MS)
            val recencyScore = exp(-ageDays / RECENCY_HALF_LIFE_DAYS)
            val total = FTS_WEIGHT * ftsScore + VEC_WEIGHT * vecScore + RECENCY_WEIGHT * recencyScore
            val capture = memory.sourceCaptureIds.firstNotNullOfOrNull { captures[it] }
            ScoredMemory(memory, total, capture?.sourceApp)
        }
            .sortedByDescending { it.score }
            .take(topK)
    }

    companion object {
        const val DEFAULT_TOP_K = 5
        private const val CANDIDATE_WINDOW = 25
        private const val FTS_WEIGHT = 0.55
        private const val VEC_WEIGHT = 0.35
        private const val RECENCY_WEIGHT = 0.10
        private const val RECENCY_HALF_LIFE_DAYS = 30.0
        private const val DAY_MS = 24.0 * 60 * 60 * 1000

        /** Cosine-similarity floor for a vector hit to count as a candidate. */
        private const val VEC_MIN_SIMILARITY = 0.35
    }
}
