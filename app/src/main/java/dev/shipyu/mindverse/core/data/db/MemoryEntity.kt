package dev.shipyu.mindverse.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A Memory: the processed, structured Memory derived from one or more Captures — summary,
 * title, tags, extracted entities — what search/Ask/Collections operate on (GLOSSARY.md).
 *
 * Sync-ready schema (ADR-0003): UUID row [id], mutation timestamps, tombstone [deleted]
 * (rows are kept with deleted=1 so future E2EE sync/backup can observe deletions).
 *
 * Search indexes (FTS5 over title+summary+tags+extracted text and the sqlite-vec single
 * vector) live in auxiliary tables outside Room's schema: `memory_fts` and `memory_vec`
 * (see MindverseDatabaseSchema). They are kept consistent by SQL triggers/vector ops;
 * deletion semantics: tombstoned rows remain in `memory` but are dropped from Matches
 * by query-time join on deleted=0, their FTS text is purged from the index, and their
 * vector row is removed eagerly.
 *
 * Embedding pointer (ticket): the Memory's embedding lives in `memory_vec` keyed by
 * `memory_id` = this row's [id] — no separate pointer column is needed.
 *
 * Chunk-ready (spec R4-3): v1 is 1 Memory = 1 chunk — a single vec row keyed by [id].
 * The future chunk table fragments long extracted text into multiple vec rows; slotting
 * it in requires no Room migration because `memory_vec` is schema-owned by code
 * (MindverseDatabaseSchema), not by an Entity. See that file's CHUNK-READY note.
 */
@Entity(
    tableName = "memory",
    indices = [
        Index(value = ["source_capture_ids"]), // crude scan filter until full-text includes refs
    ],
)
data class MemoryEntity(
    /** Sync-ready row UUID (ADR-0003). */
    @PrimaryKey val id: String,
    val title: String,
    val summary: String,
    val category: String?,
    /** Normalized tags list (GLOSSARY: Entity avoidance: not metadata). */
    val tags: List<String>,
    /** Whole extracted text (OCR/STT/share body) as lands the FTS index (spec R4-3).
     *  Kept unfragmented in v1; the future chunk table references this row. */
    @ColumnInfo(name = "extracted_text") val extractedText: String?,
    /** Captures the Memory derives from (GLOSSARY: Memory derives from one or more Captures). */
    @ColumnInfo(name = "source_capture_ids") val sourceCaptureIds: List<String>,
    /** Mutation timestamps (epoch millis, ADR-0003). */
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Tombstone flag (ADR-0003). */
    val deleted: Boolean = false,
)
