package dev.shipyu.mindverse.core.pipeline

import dev.shipyu.mindverse.core.data.ingest.CaptureIngestionService
import dev.shipyu.mindverse.core.data.ingest.IngestOutcome
import dev.shipyu.mindverse.core.data.ingest.ProposedCapture

/**
 * Enqueues the pipeline stages for a stored Capture. Abstracted so the controller is
 * testable without a WorkManager runtime; the production implementation lives in
 * `core/pipeline/work`.
 */
interface PipelineScheduler {
    /** Unique extract→embed chain for one Capture (idempotent under duplicate submits). */
    fun enqueue(captureId: String)
}

/**
 * The single entry point every M1 trigger (ticket 05) uses: store the Capture through the
 * dedupe boundary (ticket 02), then queue extract → embed. Exact duplicates are silently
 * skipped and never queued (spec R5-3); near-duplicates are stored + queued and flagged
 * for the merge prompt.
 */
class PipelineController(
    private val ingestion: CaptureIngestionService,
    private val scheduler: PipelineScheduler,
    private val db: dev.shipyu.mindverse.core.data.db.MindverseDatabase? = null,
    private val clock: java.time.Clock? = null,
) {
    suspend fun submit(proposed: ProposedCapture): IngestOutcome {
        val outcome = ingestion.ingest(proposed)
        when (outcome) {
            is IngestOutcome.Stored -> scheduler.enqueue(outcome.capture.id)
            is IngestOutcome.NearDuplicate -> scheduler.enqueue(outcome.stored.id)
            is IngestOutcome.ExactDuplicate -> {
                // Re-share of a not-yet-done Capture re-queues it (e.g. keyless
                // dead-letter then re-share after adding a key). DONE memories
                // stay silent per spec R5-3.
                val existing = outcome.existing
                if (existing.memoryId == null &&
                    existing.processingState != dev.shipyu.mindverse.core.data.db.CaptureProcessingState.DONE &&
                    db != null && clock != null
                ) {
                    db.captureDao().setProcessingState(
                        existing.id,
                        dev.shipyu.mindverse.core.data.db.CaptureProcessingState.PENDING,
                        0,
                        null,
                        clock.millis(),
                    )
                    scheduler.enqueue(existing.id)
                }
            }
        }
        return outcome
    }
}
