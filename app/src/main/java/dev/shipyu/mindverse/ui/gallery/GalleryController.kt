package dev.shipyu.mindverse.ui.gallery

import dev.shipyu.mindverse.core.data.MemoryRepository
import dev.shipyu.mindverse.core.data.db.CaptureDao
import dev.shipyu.mindverse.core.data.db.CaptureEntity
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Gallery state (ticket 06): active Memories as cards, with category / time-range /
 * source-app filters and the near-duplicate merge prompt queue. Filtering happens over the
 * loaded card list (M1 volumes are small; a query-level filter can come later).
 */
class GalleryController(
    private val db: MindverseDatabase,
    private val repository: MemoryRepository,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val scope: CoroutineScope,
    private val clipboard: dev.shipyu.mindverse.triggers.clipboard.ClipboardImporter? = null,
    private val clipboardSettings: dev.shipyu.mindverse.triggers.clipboard.ClipboardSettings? = null,
) {
    private val _state = MutableStateFlow(GalleryState())
    val state: StateFlow<GalleryState> = _state.asStateFlow()

    /** Unfiltered source list; the state's `cards` are its filtered view. */
    private var allCards: List<GalleryCard> = emptyList()

    init {
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        // Clipboard auto-add (opt-in, off by default): foreground-only read of the
        // primary clip; unchanged clips are dedupe-skipped, failures never block Brain.
        try {
            if (clipboardSettings?.isEnabled() == true) {
                clipboard?.importOnce()
            }
        } catch (_: Exception) {
            // Best-effort: a failed clipboard read must never block Brain.
        }
        val captures = db.captureDao().listActive()
        val capturesById = captures.associateBy { it.id }
        val memories = repository.listActive()
        allCards = memories.map { it.toCard(capturesById[it.sourceCaptureIds.firstOrNull()]) }
            .sortedByDescending { it.createdAtMs }

        val nearDups = db.captureDao().listNearDuplicates().mapNotNull { capture ->
            capture.nearDuplicateOf?.let { similarId ->
                capturesById[similarId]?.let { NearDupPair(capture, it) }
            }
        }

        val current = _state.value
        val pending = captures.filter { it.memoryId == null }
            .sortedByDescending { it.createdAt }
        _state.value = current.copy(
            loading = false,
            categories = allCards.mapNotNull { it.category }.distinct().sorted(),
            sourceApps = allCards.mapNotNull { it.sourceApp }.distinct().sorted(),
            nearDups = nearDups,
            pending = pending,
        )
        applyFilters()
    }

    fun setCategory(category: String?) {
        _state.value = _state.value.copy(category = category)
        applyFilters()
    }

    fun setTimeRange(range: TimeRange) {
        _state.value = _state.value.copy(timeRange = range)
        applyFilters()
    }

    fun setSourceApp(sourceApp: String?) {
        _state.value = _state.value.copy(sourceApp = sourceApp)
        applyFilters()
    }

    /** Merge prompt "keep both": both captures stay, the flag is cleared. */
    suspend fun keepBoth(captureId: String) {
        db.captureDao().clearNearDuplicate(captureId, clock.millis())
        refresh()
    }

    /** Merge prompt "remove duplicate": tombstone the capture and its derived Memory. */
    suspend fun discardDuplicate(captureId: String) {
        val capture = db.captureDao().getCapture(captureId) ?: return
        capture.memoryId?.let { repository.tombstoneMemory(it) }
        db.captureDao().tombstone(captureId, clock.millis())
        refresh()
    }

    private fun applyFilters() {
        val current = _state.value
        val since = sinceFor(current.timeRange)
        val filtered = allCards.filter { card ->
            (current.category == null || card.category == current.category) &&
                (current.sourceApp == null || card.sourceApp == current.sourceApp) &&
                (since == null || card.createdAtMs >= since)
        }
        _state.value = current.copy(cards = filtered)
    }

    private fun sinceFor(range: TimeRange): Long? = when (range) {
        TimeRange.ALL -> null
        TimeRange.TODAY -> LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        TimeRange.LAST_7_DAYS -> clock.millis() - 7 * DAY_MS
        TimeRange.LAST_30_DAYS -> clock.millis() - 30 * DAY_MS
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
