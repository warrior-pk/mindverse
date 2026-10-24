package dev.shipyu.mindverse.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * Typed Entity rows attached to Memories (child table; see MemoryItemEntity).
 * Rows are written through the repository inside the same transaction as the parent
 * Memory so a Memory is always complete.
 */
@Dao
interface MemoryItemDao {
    @Insert
    suspend fun insertItems(items: List<MemoryItemEntity>)

    @Query("SELECT * FROM memory_item WHERE memory_id = :memoryId AND deleted = 0 ORDER BY ordering ASC")
    suspend fun itemsFor(memoryId: String): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_item WHERE id = :id")
    suspend fun getItem(id: String): MemoryItemEntity?

    /** Full row update; callers stamp [MemoryItemEntity.updatedAt] first. */
    @Update
    suspend fun updateItem(item: MemoryItemEntity)

    /** Tombstone (ADR-0003): flag deleted, stamp mutation time; row is retained. */
    @Query("UPDATE memory_item SET deleted = 1, updated_at = :now WHERE id = :id")
    suspend fun tombstone(id: String, now: Long)

    @Query("SELECT COUNT(*) FROM memory_item WHERE memory_id = :memoryId AND deleted = 0")
    suspend fun countActiveFor(memoryId: String): Int

    @Query("SELECT COUNT(*) FROM memory_item")
    suspend fun countAll(): Int
}
