package dev.shipyu.mindverse.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.RawQuery
import androidx.room.Query
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * Memory-table access. The FTS5 index (`memory_fts`) is an external-content table over
 * `memory` (rowid-mapped, trigger-synced — see MindverseDatabaseSchema); MATCH queries
 * are raw (Room has no FTS5 annotations) and join back onto `memory` so tombstoned rows
 * (deleted = 1) never surface; ranking is bm25 (more negative = more relevant).
 */
@Dao
interface MemoryDao {
    @Insert
    suspend fun insert(memory: MemoryEntity)

    @Query("SELECT * FROM memory WHERE id = :id")
    suspend fun getMemory(id: String): MemoryEntity?

    @Query("SELECT * FROM memory WHERE id IN (:ids) AND deleted = 0")
    suspend fun getActiveByIds(ids: List<String>): List<MemoryEntity>

    /** Full row update; callers stamp [MemoryEntity.updatedAt] first. */
    @Update
    suspend fun update(memory: MemoryEntity)

    @Query("SELECT * FROM memory WHERE deleted = 0 ORDER BY updated_at DESC")
    suspend fun listActive(): List<MemoryEntity>

    /** Tombstone (ADR-0003): flag deleted, stamp mutation time; row is retained. */
    @Query("UPDATE memory SET deleted = 1, updated_at = :now WHERE id = :id")
    suspend fun tombstone(id: String, now: Long)

    /**
     * FTS5 MATCH over title/summary/tags/extracted_text with bm25 ranking. The caller must
     * shape the MATCH expression (repository passes a double-quoted phrase).
     */
    @RawQuery(observedEntities = [MemoryEntity::class])
    suspend fun searchFtsRaw(query: SupportSQLiteQuery): List<MemoryEntity>

    /** Raw count over the code-owned external-content table `memory_fts`. */
    @RawQuery(observedEntities = [MemoryEntity::class])
    suspend fun ftsDocCountRaw(query: SupportSQLiteQuery): CountRow

    @Query("SELECT COUNT(*) FROM memory")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM memory WHERE deleted = 1")
    suspend fun countTombstoned(): Int
}
