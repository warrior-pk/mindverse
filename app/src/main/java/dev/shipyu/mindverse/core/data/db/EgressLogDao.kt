package dev.shipyu.mindverse.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Egress-log access (ticket 03). Newest-first everywhere: the Privacy screen renders
 * "chronological, grouped by day" as latest-first pages scrolled backwards in time.
 */
@Dao
interface EgressLogDao {
    @Insert
    suspend fun insert(row: EgressLogEntity)

    /** Live feed for the Privacy screen; newest first, bounded for scroll-back. */
    @Query("SELECT * FROM egress_log ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<EgressLogEntity>>

    @Query("SELECT * FROM egress_log ORDER BY at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<EgressLogEntity>

    @Query("SELECT COUNT(*) FROM egress_log")
    suspend fun countAll(): Int
}
