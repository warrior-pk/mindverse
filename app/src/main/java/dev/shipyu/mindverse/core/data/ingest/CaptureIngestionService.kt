package dev.shipyu.mindverse.core.data.ingest

import dev.shipyu.mindverse.core.data.db.CaptureEntity
import dev.shipyu.mindverse.core.data.db.CaptureKind
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import java.time.Clock
import java.util.UUID

/**
 * A Capture as it arrives at the ingestion boundary — before the storage layer assigns
 * sync-ready metadata (id/timestamps/tombstone). Callers compute [contentHash] (SHA-256
 * hex of content bytes) and, for screenshot/image blobs, [phash] (spec R5-3).
 */
data class ProposedCapture(
    val kind: CaptureKind,
    val blobPath: String?,
    val sourceApp: String?,
    val deepLink: String?,
    val contentHash: String,
    val phash: Long?,
    val capturedAt: Long,
)

/**
 * Ingestion-boundary result (spec R5-3 dedupe contract):
 * - [IngestOutcome.ExactDuplicate] — byte-hash equal to an active capture; silently
 *   skipped (no row written).
 * - [IngestOutcome.NearDuplicate] — same pHash as an active capture; row is stored but
 *   flagged (near_duplicate_of) for the later in-app merge prompt.
 * - [IngestOutcome.Stored] — unique content.
 */
sealed interface IngestOutcome {
    data class Stored(val capture: CaptureEntity) : IngestOutcome
    data class ExactDuplicate(val existing: CaptureEntity) : IngestOutcome
    data class NearDuplicate(val stored: CaptureEntity, val similarTo: CaptureEntity) : IngestOutcome
}

/**
 * Hash-dedupe at the ingestion boundary (ticket 02, spec R5-3). Dedupe consults only
 * non-tombstoned captures: content the user deleted can be re-ingested. Near-dup matching
 * in v1 is exact pHash equality; hamming-distance thresholds are deferred (documented in
 * the ticket).
 */
class CaptureIngestionService(
    private val db: MindverseDatabase,
    private val clock: Clock,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun ingest(proposed: ProposedCapture): IngestOutcome {
        val now = clock.millis()
        val captureDao = db.captureDao()
        val existing = db.inTransaction {
            captureDao.findActiveByContentHash(proposed.contentHash)
                ?: proposed.phash?.let { captureDao.findActiveByPHash(it) }
        }
        if (existing != null && existing.contentHash == proposed.contentHash) {
            // Exact duplicate: silently skip, no second row (spec R5-3).
            return IngestOutcome.ExactDuplicate(existing)
        }
        val capture = CaptureEntity(
            id = idFactory(),
            kind = proposed.kind,
            blobPath = proposed.blobPath,
            sourceApp = proposed.sourceApp,
            deepLink = proposed.deepLink,
            contentHash = proposed.contentHash,
            phash = proposed.phash,
            capturedAt = proposed.capturedAt,
            createdAt = now,
            updatedAt = now,
            deleted = false,
            nearDuplicateOf = existing?.id,
        )
        captureDao.insertCapture(capture)
        return if (existing != null) {
            IngestOutcome.NearDuplicate(capture, existing)
        } else {
            IngestOutcome.Stored(capture)
        }
    }
}
