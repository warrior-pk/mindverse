package dev.shipyu.mindverse.core.data

import dev.shipyu.mindverse.core.data.db.MemoryEntity
import dev.shipyu.mindverse.core.data.db.MemoryItemEntity
import dev.shipyu.mindverse.core.data.db.MemoryItemKind
import dev.shipyu.mindverse.core.data.db.MemoryVectorHit
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import java.time.Clock
import java.util.UUID

/** A synthesized Memory at write time: processed Memory payload + typed Entity rows. */
data class MemoryDraft(
    val sourceCaptureIds: List<String>,
    val title: String,
    val summary: String,
    val category: String?,
    val tags: List<String>,
    val extractedText: String?,
    val items: List<MemoryItemDraft> = emptyList(),
    /** On-device embedding (spec R4-2); null for Memories not yet embedded. */
    val embedding: FloatArray? = null,
)

/** Typed Entity row draft; storage assigns id/memoryId/ordering/sync-ready stamps. */
data class MemoryItemDraft(
    val kind: MemoryItemKind,
    val text: String? = null,
    val timestampStartMs: Long? = null,
    val timestampEndMs: Long? = null,
    val priceAmountMicros: Long? = null,
    val priceCurrency: String? = null,
    val locationName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val personHandle: String? = null,
    val ticketCode: String? = null,
    val ticketSeat: String? = null,
    val ticketGate: String? = null,
)

/**
 * Memory write/query surface over the storage core (search seams for ticket 07's Ask
 * retrieval and ticket 06's surfaces): create/update/tombstone Memories, FTS5 full-text
 * search and sqlite-vec KNN search.
 *
 * Tombstones (ADR-0003): update/delete never purge rows; `tombstone` flags deleted=1 and
 * stamps updatedAt. Tombstoned rows drop out of search results (FTS via query-time join
 * on deleted=0, vectors by eager removal from `memory_vec`).
 */
class MemoryRepository(
    private val db: MindverseDatabase,
    private val clock: Clock,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun createMemory(draft: MemoryDraft): MemoryEntity {
        val now = clock.millis()
        val memory = MemoryEntity(
            id = idFactory(),
            title = draft.title,
            summary = draft.summary,
            category = draft.category,
            tags = draft.tags,
            extractedText = draft.extractedText,
            sourceCaptureIds = draft.sourceCaptureIds,
            createdAt = now,
            updatedAt = now,
            deleted = false,
        )
        db.inTransaction {
            db.memoryDao().insert(memory)
            if (draft.items.isNotEmpty()) {
                db.memoryItemDao().insertItems(
                    draft.items.mapIndexed { index, item -> item.toEntity(idFactory(), memory.id, now, index) },
                )
            }
            draft.embedding?.let { db.insertMemoryVector(memory.id, it) }
        }
        return memory
    }

    suspend fun getMemory(id: String): MemoryEntity? = db.memoryDao().getMemory(id)

    suspend fun listActive(): List<MemoryEntity> = db.memoryDao().listActive()

    /** Full-Memory update: stamps updatedAt, keeps createdAt; optional re-embedding. */
    suspend fun updateMemory(memory: MemoryEntity, newEmbedding: FloatArray? = null) {
        val now = clock.millis()
        val stamped = memory.copy(updatedAt = now)
        db.inTransaction {
            db.memoryDao().update(stamped)
            if (newEmbedding != null) {
                db.deleteMemoryVector(memory.id)
                db.insertMemoryVector(memory.id, newEmbedding)
            }
        }
    }

    /** Tombstone: deleted=1 + mutation stamp; vector row removed so KNN skips it. */
    suspend fun tombstoneMemory(id: String) {
        val now = clock.millis()
        db.inTransaction {
            db.memoryDao().tombstone(id, now)
            db.deleteMemoryVector(id)
        }
    }

    /**
     * FTS5 full-text search (bm25-ranked). The user-facing query is sanitized into a
     * double-quoted FTS5 phrase so arbitrary text can never alter query syntax.
     */
    suspend fun searchByFullText(query: String, limit: Int = 20): List<MemoryEntity> {
        require(limit > 0)
        val matchExpression = ftsPhrase(query)
        val sql = """
            SELECT memory.* FROM memory_fts
            JOIN memory ON memory.rowid = memory_fts.rowid
            WHERE memory_fts MATCH ? AND memory.deleted = 0
            ORDER BY bm25(memory_fts) ASC
            LIMIT ?
        """.trimIndent()
        return db.memoryDao().searchFtsRaw(
            db.simpleQuery(sql, arrayOf<Any>(matchExpression, limit)),
        )
    }

    /**
     * KNN cosine search over Memory vectors, tombstoned rows filtered out. Distance is
     * ascending (most similar first).
     */
    suspend fun searchByVector(queryVector: FloatArray, k: Int = 20): List<MemoryVectorHit> {
        require(k > 0)
        val hits = db.searchMemoryVectors(queryVector, k)
        if (hits.isEmpty()) return emptyList()
        val activeIds = db.memoryDao().getActiveByIds(hits.map { it.memoryId }).map { it.id }.toSet()
        return hits.filter { it.memoryId in activeIds }
    }

    suspend fun itemsFor(memoryId: String): List<MemoryItemEntity> =
        db.memoryItemDao().itemsFor(memoryId)

    companion object {
        /** FTS5 quoted-phrase form: doubles embedded quotes so text can't break out. */
        internal fun ftsPhrase(query: String): String = "\"" + query.replace("\"", "\"\"") + "\""
    }
}

private fun MemoryItemDraft.toEntity(id: String, memoryId: String, now: Long, ordering: Int) =
    MemoryItemEntity(
        id = id,
        memoryId = memoryId,
        kind = kind,
        text = text,
        timestampStartMs = timestampStartMs,
        timestampEndMs = timestampEndMs,
        priceAmountMicros = priceAmountMicros,
        priceCurrency = priceCurrency,
        locationName = locationName,
        latitude = latitude,
        longitude = longitude,
        personHandle = personHandle,
        ticketCode = ticketCode,
        ticketSeat = ticketSeat,
        ticketGate = ticketGate,
        ordering = ordering,
        createdAt = now,
        updatedAt = now,
        deleted = false,
    )
