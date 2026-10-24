package dev.shipyu.mindverse.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * Capture-table access. Dedupe queries consider only non-tombstoned rows (deleted = 0),
 * so a purged/removed capture does not forever shadow re-ingestion of the same content.
 */
@Dao
interface CaptureDao {
    @Insert
    suspend fun insertCapture(capture: CaptureEntity)

    @Query("SELECT * FROM captures WHERE id = :id")
    suspend fun getCapture(id: String): CaptureEntity?

    /** Dedupe: content byte-hash lookup (exact duplicates), pending captures only. */
    @Query("SELECT * FROM captures WHERE content_hash = :contentHash AND deleted = 0 LIMIT 1")
    suspend fun findActiveByContentHash(contentHash: String): CaptureEntity?

    /** Dedupe: perceptual hash lookup (near duplicates), pending captures only. */
    @Query("SELECT * FROM captures WHERE phash = :phash AND deleted = 0 LIMIT 1")
    suspend fun findActiveByPHash(phash: Long): CaptureEntity?

    /** Tombstone (ADR-0003): flag deleted, stamp mutation time; row is retained. */
    @Query("UPDATE captures SET deleted = 1, updated_at = :now WHERE id = :id")
    suspend fun tombstone(id: String, now: Long)

    /** Full row update; callers stamp [CaptureEntity.updatedAt] first. */
    @Update
    suspend fun update(capture: CaptureEntity)

    @Query("SELECT * FROM captures WHERE deleted = 0 ORDER BY captured_at DESC")
    suspend fun listActive(): List<CaptureEntity>

    @Query("SELECT COUNT(*) FROM captures WHERE deleted = 1")
    suspend fun countTombstoned(): Int

    @Query("SELECT COUNT(*) FROM captures")
    suspend fun countAll(): Int

    /** Dead-lettered captures (visible in-app per ticket 04); newest failure first. */
    @Query("SELECT * FROM captures WHERE processing_state = 'DEAD_LETTER' AND deleted = 0 ORDER BY updated_at DESC")
    suspend fun listDeadLettered(): List<CaptureEntity>

    /** Captures waiting for the extract job. */
    @Query("SELECT * FROM captures WHERE processing_state = 'PENDING' AND deleted = 0 ORDER BY captured_at ASC")
    suspend fun listPending(): List<CaptureEntity>

    /**
     * Pipeline state transition (ticket 04). [processingError] is cleared on progress and
     * set on dead-letter; [processingAttempts] mirrors the worker's run attempt count.
     */
    @Query(
        "UPDATE captures SET processing_state = :state, processing_attempts = :attempts, " +
            "processing_error = :error, updated_at = :now WHERE id = :id",
    )
    suspend fun setProcessingState(
        id: String,
        state: CaptureProcessingState,
        attempts: Int,
        error: String?,
        now: Long,
    )

    /** Extraction output linkage; the embed stage resolves its Memory through this. */
    @Query("UPDATE captures SET memory_id = :memoryId, updated_at = :now WHERE id = :id")
    suspend fun setMemoryId(id: String, memoryId: String, now: Long)

    /** Embed is idempotent; a re-run after process death skips already-created Memories. */
    @Query("SELECT * FROM captures WHERE memory_id = :memoryId LIMIT 1")
    suspend fun findByMemoryId(memoryId: String): CaptureEntity?

    /** Near-duplicate candidates awaiting the in-app merge prompt (spec R5-3). */
    @Query("SELECT * FROM captures WHERE near_duplicate_of IS NOT NULL AND deleted = 0 ORDER BY captured_at DESC")
    suspend fun listNearDuplicates(): List<CaptureEntity>

    /** Merge prompt "keep both": drop the flag without touching content. */
    @Query("UPDATE captures SET near_duplicate_of = NULL, updated_at = :now WHERE id = :id")
    suspend fun clearNearDuplicate(id: String, now: Long)

    /** Capture review sheet (ticket 06): user-edited source context. */
    @Query("UPDATE captures SET source_app = :sourceApp, deep_link = :deepLink, updated_at = :now WHERE id = :id")
    suspend fun updateSourceContext(id: String, sourceApp: String?, deepLink: String?, now: Long)

    /** Recently ingested captures (review surface), newest first. */
    @Query("SELECT * FROM captures WHERE deleted = 0 ORDER BY created_at DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<CaptureEntity>
}
