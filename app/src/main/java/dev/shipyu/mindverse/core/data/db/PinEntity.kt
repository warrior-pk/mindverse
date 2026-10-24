package dev.shipyu.mindverse.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Pins (GLOSSARY): transient, time-sensitive surfaces over a Memory. v1 creates one
 * [PinKind.TICKET_DEPARTURE] pin per ticket Memory; M3 renders/extinguishes them.
 * TTL is departure-derived; the row survives until tombstoned or expiry passes.
 */
@Dao
interface PinDao {
    @Insert
    suspend fun insert(pin: PinEntity)

    @Query("SELECT * FROM pin WHERE id = :id")
    suspend fun get(id: String): PinEntity?

    /** Pins whose TTL has not elapsed yet (the M3 surface query). */
    @Query("SELECT * FROM pin WHERE deleted = 0 AND expires_at_ms > :now ORDER BY expires_at_ms ASC")
    suspend fun listActive(now: Long): List<PinEntity>

    @Query("SELECT * FROM pin WHERE memory_id = :memoryId AND deleted = 0")
    suspend fun forMemory(memoryId: String): List<PinEntity>

    /** Tombstone (ADR-0003): flag deleted, stamp mutation time; row is retained. */
    @Query("UPDATE pin SET deleted = 1, updated_at = :now WHERE id = :id")
    suspend fun tombstone(id: String, now: Long)

    @Query("SELECT COUNT(*) FROM pin WHERE deleted = 0 AND expires_at_ms > :now")
    suspend fun countActive(now: Long): Int
}

/**
 * One Pin row. [expiresAtMs] is the departure-derived TTL boundary (epoch millis);
 * [kind] leaves room for the M3 unified temporal-pin kinds without a schema change.
 */
@androidx.room.Entity(
    tableName = "pin",
    indices = [
        androidx.room.Index(value = ["memory_id"]),
        androidx.room.Index(value = ["expires_at_ms"]),
    ],
)
data class PinEntity(
    /** Sync-ready row UUID (ADR-0003). */
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "memory_id") val memoryId: String,
    /** Capture that produced the underlying Memory, when known. */
    @ColumnInfo(name = "capture_id") val captureId: String?,
    val kind: PinKind,
    /** End of the time-sensitive window (epoch millis); surfaces stop rendering after it. */
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Tombstone flag (ADR-0003). */
    val deleted: Boolean = false,
)

/** Pin kinds; v1 only probes ticket departures (spec M3 extends this). */
enum class PinKind {
    TICKET_DEPARTURE,
}
