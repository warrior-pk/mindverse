package dev.shipyu.mindverse.ui.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shipyu.mindverse.ui.common.BlobThumbnail
import kotlinx.coroutines.launch

/**
 * Gallery (ticket 06): the home screen of Memories — cards, category / time-range /
 * source-app filters, and the near-duplicate merge prompt. Empty/loading states are part
 * of the design; no invented flows.
 */
@Composable
fun GalleryScreen(
    controller: GalleryController,
    onOpenMemory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Surface(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(text = "Memories", style = MaterialTheme.typography.headlineSmall)
            }

            if (state.nearDups.isNotEmpty()) {
                item {
                    NearDupSection(
                        pairs = state.nearDups,
                        onKeepBoth = { id -> scope.launch { controller.keepBoth(id) } },
                        onDiscardDuplicate = { id -> scope.launch { controller.discardDuplicate(id) } },
                    )
                }
            }

            item {
                FilterSection(
                    state = state,
                    onCategory = controller::setCategory,
                    onTimeRange = controller::setTimeRange,
                    onSourceApp = controller::setSourceApp,
                )
            }

            when {
                state.loading -> item {
                    Text(
                        text = "Loading…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.cards.isEmpty() -> item {
                    EmptyState(hasFilters = state.category != null || state.sourceApp != null || state.timeRange != TimeRange.ALL)
                }
                else -> items(state.cards, key = { it.memoryId }) { card ->
                    MemoryCardRow(card = card, onClick = { onOpenMemory(card.memoryId) })
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
                    label = { Text("All categories") },
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
        if (state.sourceApps.isNotEmpty()) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.sourceApp == null,
                    onClick = { onSourceApp(null) },
                    label = { Text("All sources") },
                )
                state.sourceApps.forEach { app ->
                    FilterChip(
                        selected = state.sourceApp == app,
                        onClick = { onSourceApp(app) },
                        label = { Text(app.substringAfterLast('.')) },
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
                text = "Similar captures",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            pairs.take(5).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Similar to a capture from " +
                            (pair.similarTo.sourceApp ?: "an earlier source"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onKeepBoth(pair.capture.id) }) { Text("Keep both") }
                    TextButton(onClick = { onDiscardDuplicate(pair.capture.id) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun MemoryCardRow(card: GalleryCard, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BlobThumbnail(
                path = card.thumbnailPath,
                modifier = Modifier.size(64.dp),
                placeholder = card.category?.replaceFirstChar { it.uppercase() } ?: "Memory",
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = card.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = card.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
                if (card.tags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = card.tags.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(hasFilters: Boolean) {
    Column {
        Text(
            text = if (hasFilters) "Nothing matches these filters" else "No Memories yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (hasFilters) {
                "Try widening the time range or clearing the filters."
            } else {
                "Share something into Mindverse or take a screenshot — captured items " +
                    "appear here once processed."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
