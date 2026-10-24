package dev.shipyu.mindverse.ui.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shipyu.mindverse.core.data.db.CaptureEntity
import dev.shipyu.mindverse.ui.common.BlobThumbnail
import kotlinx.coroutines.launch

/**
 * Brain (home): masonry of Memories — thumbnails first, no text clutter.
 * Text Memories show title only; image Memories show the image only.
 * Filters stay collapsible; detail holds the full summary/tags.
 */
@Composable
fun GalleryScreen(
    controller: GalleryController,
    onOpenMemory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Refresh on open, then keep polling briefly while captures are still
    // processing — a fresh clipboard/share capture shows as pending first and
    // becomes a card once the pipeline finishes (no manual reopen needed).
    LaunchedEffect(Unit) {
        controller.refresh()
        repeat(5) {
            kotlinx.coroutines.delay(3_000)
            if (controller.state.value.pending.isEmpty()) return@LaunchedEffect
            controller.refresh()
        }
    }

    Surface(modifier = modifier.fillMaxSize()) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalItemSpacing = 10.dp,
        ) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Text(text = "Brain", style = MaterialTheme.typography.headlineSmall)
            }

            if (state.nearDups.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    NearDupSection(
                        pairs = state.nearDups,
                        onKeepBoth = { id -> scope.launch { controller.keepBoth(id) } },
                        onDiscardDuplicate = { id -> scope.launch { controller.discardDuplicate(id) } },
                    )
                }
            }

            item(span = StaggeredGridItemSpan.FullLine) {
                FilterSection(
                    state = state,
                    onCategory = controller::setCategory,
                    onTimeRange = controller::setTimeRange,
                    onSourceApp = controller::setSourceApp,
                )
            }

            if (state.loading) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (state.cards.isEmpty() && state.pending.isEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    EmptyState(hasFilters = state.category != null || state.sourceApp != null || state.timeRange != TimeRange.ALL)
                }
            } else {
                items(state.pending, key = { "pending-${it.id}" }) { capture ->
                    PendingTile(capture = capture)
                }
                items(state.cards, key = { it.memoryId }) { card ->
                    MasonryTile(card = card, onClick = { onOpenMemory(card.memoryId) })
                }
            }
        }
    }
}

@Composable
private fun FilterSection(
    state: GalleryState,
    onCategory: (String?) -> Unit,
    onTimeRange: (TimeRange) -> Unit,
    onSourceApp: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeRange.entries.forEach { range ->
                FilterChip(
                    selected = state.timeRange == range,
                    onClick = { onTimeRange(range) },
                    label = { Text(range.label) },
                )
            }
        }
        if (state.categories.isNotEmpty()) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.category == null,
                    onClick = { onCategory(null) },
                    label = { Text("All") },
                )
                state.categories.forEach { category ->
                    FilterChip(
                        selected = state.category == category,
                        onClick = { onCategory(category) },
                        label = { Text(category) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NearDupSection(
    pairs: List<NearDupPair>,
    onKeepBoth: (String) -> Unit,
    onDiscardDuplicate: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Similar",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            pairs.take(3).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { onKeepBoth(pair.capture.id) }) { Text("Keep") }
                    TextButton(onClick = { onDiscardDuplicate(pair.capture.id) }) { Text("Remove") }
                }
            }
        }
    }
}

/** Masonry tile: tall image tile, or tall title tile (1:2–1:4 w:h). */
@Composable
private fun MasonryTile(card: GalleryCard, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        // Tall-tile rhythm: width:height between 1:2 and 1:4 by id hash.
        val ratio = when ((card.memoryId.hashCode() and Int.MAX_VALUE) % 4) {
            0 -> 1f / 2f
            1 -> 1f / 2.75f
            2 -> 1f / 3.5f
            else -> 1f / 4f
        }
        if (card.thumbnailPath != null) {
            BlobThumbnail(
                path = card.thumbnailPath,
                modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
                placeholder = "",
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(ratio).padding(12.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 6,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun PendingTile(capture: CaptureEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        val blob = capture.blobPath?.takeIf { capture.mimeType?.startsWith("image/") == true }
        if (blob != null) {
            BlobThumbnail(
                path = blob,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f / 2.5f),
                placeholder = "",
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f / 2.5f).padding(12.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text(
                    text = "Saving…",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(hasFilters: Boolean) {
    Column {
        Text(
            text = if (hasFilters) "Nothing here" else "Empty",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (hasFilters) {
                "Clear the filters."
            } else {
                "Share into Mindverse — it lands here."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
