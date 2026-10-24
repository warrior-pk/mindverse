package dev.shipyu.mindverse.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Typed Entity rows attached to Memories (child table; see MemoryItemEntity).
 * Rows are written through the repository inside the same transaction as the parent
 * Memory so a card is always complete.
 */
@Dao
interface MemoryItemDao {
    @Insert
    suspend fun insertItems(items: List<MemoryItemEntity>)

    @Query("SELECT * FROM memory_item WHERE memory_id = :memoryId AND deleted = 0 ORDER BY ordering ASC")
    suspend fun itemsFor(memoryId: String): List<MemoryItemEntity>

    @Query("SELECT COUNT(*) FROM memory_item WHERE memory_id = :memoryId AND deleted = 0")
    suspend fun countActiveFor(memoryId: String): Int

    @Query("SELECT COUNT(*) FROM memory_item")
    suspend fun countAll(): Int
}
