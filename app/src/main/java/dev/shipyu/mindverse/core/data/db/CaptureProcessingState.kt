package dev.shipyu.mindverse.core.data.db

/**
 * Pipeline lifecycle of a Capture (ticket 04). Persisted so retries/process death resume
 * from the last durable point, and so the in-app dead-letter surface (ticket 06) can list
 * failures without consulting WorkManager state.
 *
 * - [PENDING] — stored, waiting for the extract job (or retrying it).
 * - [PROCESSING] — extracted into a Memory; the embed job has not finished yet.
 * - [DONE] — Memory created, indexed (FTS + vec), and any Pin probed.
 * - [DEAD_LETTER] — retries exhausted or a terminal (user-actionable) failure; the
 *   Capture row + blob are kept untouched (no data loss) and [CaptureEntity.processingError]
 *   carries the reason.
 *
 * Legacy rows (pre-v3 captures) have a null state and are never swept into the pipeline.
 */
enum class CaptureProcessingState {
    PENDING,
    PROCESSING,
    DONE,
    DEAD_LETTER,
}
