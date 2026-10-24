package dev.shipyu.mindverse.ui.gallery

import dev.shipyu.mindverse.core.data.db.CaptureEntity
import dev.shipyu.mindverse.core.data.db.MemoryEntity

/** Time-range filter for the Gallery (ticket 06). */
enum class TimeRange(val label: String) {
    ALL("All time"),
    TODAY("Today"),
    LAST_7_DAYS("7 days"),
    LAST_30_DAYS("30 days"),
}

/** One gallery card: the Memory plus the capture context that backs it. */
data class GalleryCard(
    val memoryId: String,
    val title: String,
    val summary: String,
    val category: String?,
    val tags: List<String>,
    val createdAtMs: Long,
    val sourceApp: String?,
    val deepLink: String?,
    /** Local image blob to show as a thumbnail, when the capture has one. */
    val thumbnailPath: String?,
)

/** A near-duplicate pair awaiting the merge prompt (spec R5-3). */
data class NearDupPair(
    val capture: CaptureEntity,
    val similarTo: CaptureEntity,
)

/** Complete Gallery state (ticket 06): cards + available filters + merge prompts. */
data class GalleryState(
    val loading: Boolean = true,
    val cards: List<GalleryCard> = emptyList(),
    val categories: List<String> = emptyList(),
    val sourceApps: List<String> = emptyList(),
    val category: String? = null,
    val timeRange: TimeRange = TimeRange.ALL,
    val sourceApp: String? = null,
    val nearDups: List<NearDupPair> = emptyList(),
    /** Captures not yet turned into Memories (processing / dead-letter): shown inline
     *  so Share appears instantly even before WorkManager finishes. */
    val pending: List<CaptureEntity> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && cards.isEmpty() && pending.isEmpty()
}

internal fun MemoryEntity.toCard(capture: CaptureEntity?): GalleryCard = GalleryCard(
    memoryId = id,
    title = title,
    summary = summary,
    category = category,
    tags = tags,
    createdAtMs = createdAt,
    sourceApp = capture?.sourceApp,
    deepLink = capture?.deepLink,
    thumbnailPath = capture?.blobPath?.takeIf { capture.mimeType?.startsWith("image/") == true },
)
