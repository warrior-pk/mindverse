package dev.shipyu.mindverse.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Typed child-table Entity rows attached to a Memory (ticket: "entities (typed columns or
 * typed child table: dates, prices, places w/ nullable lat/lng, events/deadlines,
 * ticket fields, people)"). One row per extracted Entity, typed by [kind]; nullable typed
 * columns carry the value (import profile: a row uses the subset its kind needs).
 *
 * Sync-ready columns mirror the parent ([createdAt]/[updatedAt]/[deleted]). Rows cascade
 * logically with their Memory (repository-level lifecycle; no cross-entity FK constraint
 * so ingestion and downstream processing stay independent of ordering).
 */
@Entity(
    tableName = "memory_item",
    indices = [
        Index(value = ["memory_id"]),
    ],
)
data class MemoryItemEntity(
    /** Sync-ready row UUID (ADR-0003). */
    @PrimaryKey val id: String,
    @ColumnInfo(name = "memory_id") val memoryId: String,
    val kind: MemoryItemKind,
    /** Raw extracted token/value (person name, place label, raw date text, PNR...). */
    val text: String?,
    /** Event/deadline start (epoch millis); null when the entity has no time. */
    @ColumnInfo(name = "timestamp_start_ms") val timestampStartMs: Long?,
    /** Event end (epoch millis); deadlines use only start. */
    @ColumnInfo(name = "timestamp_end_ms") val timestampEndMs: Long?,
    /** Price value in base-unit micros (e.g. 2.50 USD = 2_500_000µ$). Spec R4-1 core Memory fields. */
    @ColumnInfo(name = "price_amount_micros") val priceAmountMicros: Long?,
    /** ISO 4217 currency, pairs with [priceAmountMicros]. */
    @ColumnInfo(name = "price_currency") val priceCurrency: String?,
    /** Place label; places carry nullable lat/lng (ticket). */
    @ColumnInfo(name = "location_name") val locationName: String?,
    val latitude: Double?,
    val longitude: Double?,
    /** Person handle (phone/email/handle) surfacing People fields; primary name is [text]. */
    @ColumnInfo(name = "person_handle") val personHandle: String?,
    /** Ticket fields (R4-4/R6): PNR/booking/confirmation code, seat, gate; barcode/QR
     *  coordinates are capture-time geometry owned by the Vault module (M3), not storage. */
    @ColumnInfo(name = "ticket_code") val ticketCode: String?,
    @ColumnInfo(name = "ticket_seat") val ticketSeat: String?,
    @ColumnInfo(name = "ticket_gate") val ticketGate: String?,
    /** Stable ordering within a Memory (extraction order). */
    @ColumnInfo(name = "ordering") val ordering: Int = 0,
    /** Mutation timestamps (epoch millis, ADR-0003). */
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Tombstone flag (ADR-0003). */
    val deleted: Boolean = false,
)

/**
 * Memory Entity kinds (GLOSSARY Entity: typed, individually addressable). Ticket list:
 * dates, prices, places, events/deadlines, ticket fields, people.
 */
enum class MemoryItemKind {
    DATE,
    PRICE,
    PLACE,
    EVENT,
    DEADLINE,
    TICKET,
    PERSON,
}
