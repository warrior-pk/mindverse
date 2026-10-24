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
}
