package dev.shipyu.mindverse.ui.detail

import dev.shipyu.mindverse.core.data.MemoryRepository
import dev.shipyu.mindverse.core.data.db.CaptureEntity
import dev.shipyu.mindverse.core.data.db.MemoryEntity
import dev.shipyu.mindverse.core.data.db.MemoryItemEntity
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.data.db.PinEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the Memory detail screen renders (ticket 06). */
data class MemoryDetailState(
    val loading: Boolean = true,
    val memory: MemoryEntity? = null,
    val items: List<MemoryItemEntity> = emptyList(),
    val sourceCapture: CaptureEntity? = null,
    val pins: List<PinEntity> = emptyList(),
) {
    val exists: Boolean get() = !loading && memory != null

    /** Image blob for the thumbnail view, when the source capture carries one. */
    val thumbnailPath: String?
        get() = sourceCapture?.blobPath?.takeIf { sourceCapture.mimeType?.startsWith("image/") == true }
}

/** Memory detail loader (ticket 06): card + typed Entity rows + source context + Pins. */
class MemoryDetailController(
    private val db: MindverseDatabase,
    private val repository: MemoryRepository,
) {
    private val _state = MutableStateFlow(MemoryDetailState())
    val state: StateFlow<MemoryDetailState> = _state.asStateFlow()

    suspend fun load(memoryId: String) {
        val memory = repository.getMemory(memoryId)
        if (memory == null) {
            _state.value = MemoryDetailState(loading = false)
            return
        }
        val capture = memory.sourceCaptureIds.firstNotNullOfOrNull { id ->
            db.captureDao().getCapture(id)
        }
        _state.value = MemoryDetailState(
            loading = false,
            memory = memory,
            items = repository.itemsFor(memoryId),
            sourceCapture = capture,
            pins = db.pinDao().forMemory(memoryId),
        )
    }
}
