package dev.shipyu.mindverse.ui.captures

import dev.shipyu.mindverse.core.data.MemoryRepository
import dev.shipyu.mindverse.core.data.db.CaptureEntity
import dev.shipyu.mindverse.core.data.db.CaptureProcessingState
import dev.shipyu.mindverse.core.data.db.MemoryEntity
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.pipeline.PipelineScheduler
import java.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Capture review sheet state (ticket 06): the Capture is visible immediately (even
 * pre-extraction), with its source context editable and confirm/discard actions.
 */
data class CaptureReviewState(
    val loading: Boolean = true,
    val capture: CaptureEntity? = null,
    val memory: MemoryEntity? = null,
) {
    val exists: Boolean get() = !loading && capture != null
}

/**
 * Capture review actions (ticket 06): edit source context, confirm (keep + re-queue the
 * pipeline when it is not done yet), discard (tombstone the Capture and any derived
 * Memory — rows are kept per ADR-0003, never purged).
 */
class CaptureReviewController(
    private val db: MindverseDatabase,
    private val repository: MemoryRepository,
    private val scheduler: PipelineScheduler,
    private val clock: Clock,
) {
    private val _state = MutableStateFlow(CaptureReviewState())
    val state: StateFlow<CaptureReviewState> = _state.asStateFlow()

    suspend fun load(captureId: String) {
        val capture = db.captureDao().getCapture(captureId)
        _state.value = CaptureReviewState(
            loading = false,
            capture = capture,
            memory = capture?.memoryId?.let { repository.getMemory(it) },
        )
    }

    /** User edits to the source context (package + deep link/URL), persisted immediately. */
    suspend fun updateSource(sourceApp: String?, deepLink: String?) {
        val capture = _state.value.capture ?: return
        db.captureDao().updateSourceContext(
            id = capture.id,
            sourceApp = sourceApp?.trim()?.takeUnless { it.isEmpty() },
            deepLink = deepLink?.trim()?.takeUnless { it.isEmpty() },
            now = clock.millis(),
        )
        load(capture.id)
    }

    /**
     * Confirm keeps the Capture and (re-)queues extraction when it is not finished yet —
     * this is also the retry affordance for dead-lettered captures.
     */
    suspend fun confirm() {
        val capture = _state.value.capture ?: return
        if (capture.processingState != CaptureProcessingState.DONE) {
            db.captureDao().setProcessingState(
                id = capture.id,
                state = CaptureProcessingState.PENDING,
                attempts = 0,
                error = null,
                now = clock.millis(),
            )
            scheduler.enqueue(capture.id)
        }
        load(capture.id)
    }

    /** Discard: tombstone the Capture and any derived Memory (kept as tombstones). */
    suspend fun discard() {
        val capture = _state.value.capture ?: return
        capture.memoryId?.let { repository.tombstoneMemory(it) }
        db.captureDao().tombstone(capture.id, clock.millis())
        load(capture.id)
    }
}
