package dev.shipyu.mindverse.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Outcome of one egress attempt (spec R5-1: log on both success and failure). */
enum class EgressOutcome {
    SUCCESS,
    DENIED,
    FAILED,
}

/**
 * One outbound AI call as recorded by the egress chokepoint (ADR-0002, ticket 03):
 * `{captureId?, feature, payload classes, provider, bytes-out, at}` plus the freshest
 * requestId, outcome and a short degradation cause when FAILED.
 *
 * App-local diagnostics only (not sync-ready content): no tombstones, no sync fields —
 * the Privacy screen renders this table live (spec R5-1 / ticket 03).
 */
@Entity(
    tableName = "egress_log",
    indices = [Index(value = ["at"])],
)
data class EgressLogEntity(
    @PrimaryKey val id: String,
    /** Correlation id supplied by the calling feature (stable across provider retries). */
    @ColumnInfo(name = "request_id") val requestId: String,
    /** Capture the payload was derived from, when the caller knows it. */
    @ColumnInfo(name = "capture_id") val captureId: String?,
    /** Reason code / feature axis ([dev.shipyu.mindverse.core.egress.EgressFeature.name]). */
    val feature: String,
    /** Masking payload classes touched by this call (`PayloadClass.name`). */
    @ColumnInfo(name = "payload_classes") val payloadClasses: List<String>,
    /** Provider wire label, e.g. "gemini", "openai_compatible". */
    val provider: String,
    /** Serialized outbound payload size in bytes (0 when nothing was sent). */
    @ColumnInfo(name = "bytes_out") val bytesOut: Int,
    /** When the call happened (epoch millis). */
    val at: Long,
    val outcome: EgressOutcome,
    /** Short human-readable cause for FAILED rows; null otherwise. */
    val error: String? = null,
)
