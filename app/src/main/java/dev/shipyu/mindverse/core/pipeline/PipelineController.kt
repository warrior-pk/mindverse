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
) {
    suspend fun submit(proposed: ProposedCapture): IngestOutcome {
        val outcome = ingestion.ingest(proposed)
        when (outcome) {
            is IngestOutcome.Stored -> scheduler.enqueue(outcome.capture.id)
            is IngestOutcome.NearDuplicate -> scheduler.enqueue(outcome.stored.id)
            is IngestOutcome.ExactDuplicate -> Unit
        }
        return outcome
    }
}
