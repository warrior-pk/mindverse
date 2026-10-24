package dev.shipyu.mindverse.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One Capture: the raw, untouched content ingested at the moment of a Trigger, together
 * with its source context (normative per GLOSSARY.md).
 *
 * Sync-ready schema (ADR-0003): UUID row [id], mutation timestamps [createdAt]/[updatedAt],
 * tombstone [deleted]. Blob files referenced by [blobPath] live outside the schema; the
 * processing pipeline owns object storage lifecycles.
 *
 * Columns noted by the ticket: blob path, kind, source app package, deep link/URL, hash
 * (byte hash for file blobs; perceptual hash for screenshot blobs — spec R5-3), captured-at.
 *
 * [contentHash] is the SHA-256 of the content bytes (lowercase hex), computed by callers at
 * the ingestion boundary; dedupe (see captureIngestionService) consults it. [phash] is only
 * meaningful for screenshot/image blobs; equal pHash values flag a near-duplicate.
 */
@Entity(
    tableName = "captures",
    indices = [
        Index(value = ["content_hash"]),
        Index(value = ["phash"]),
    ],
)
data class CaptureEntity(
    /** Sync-ready row UUID (ADR-0003), not an autoincrement integer. */
    @PrimaryKey val id: String,
    val kind: CaptureKind,
    /** Absolute or app-scoped path to the stored blob; null for plain-text captures. */
    @ColumnInfo(name = "blob_path") val blobPath: String?,
    /** Originating app package, best-effort (spec R5-2). */
    @ColumnInfo(name = "source_app") val sourceApp: String?,
    /** Deep link / browser URL scraped from the source context (spec R5-2). */
    @ColumnInfo(name = "deep_link") val deepLink: String?,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    /** Perceptual hash of the visual content (screenshot blobs), null when not applicable. */
    val phash: Long?,
    /**
     * MIME type of the stored blob (ticket 04 routing: image MIME types → vision
     * extraction, text/null → text extraction, `application/pdf` → document input).
     * Null when the trigger could not determine it (best-effort source context).
     */
    @ColumnInfo(name = "mime_type") val mimeType: String? = null,
    /** When the content itself was captured (epoch millis), distinct from ingestion time. */
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    /** Mutation timestamp (epoch millis, ADR-0003); stamped at insert and on update/tombstone. */
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Tombstone flag (ADR-0003): true means deleted on this device; rows are kept, not purged. */
    val deleted: Boolean = false,
    /**
     * Set when the ingestion boundary detected a near-duplicate (equal [phash], spec R5-3):
     * the upstream merge prompt resolves this later. Null for unique captures.
     */
    @ColumnInfo(name = "near_duplicate_of") val nearDuplicateOf: String? = null,
    /**
     * Pipeline lifecycle (ticket 04). Null on pre-v3 rows; new captures start [CaptureProcessingState.PENDING]
     * before the extract job is enqueued.
     */
    @ColumnInfo(name = "processing_state") val processingState: CaptureProcessingState? = null,
    /** Attempts made by the current stage (visible in the dead-letter surface). */
    @ColumnInfo(name = "processing_attempts", defaultValue = "0") val processingAttempts: Int = 0,
    /** Last failure reason (typed, human-readable) for a dead-lettered Capture. */
    @ColumnInfo(name = "processing_error") val processingError: String? = null,
    /** Memory produced by extraction; the chip deep-link and embed job resolve through it. */
    @ColumnInfo(name = "memory_id") val memoryId: String? = null,
)
