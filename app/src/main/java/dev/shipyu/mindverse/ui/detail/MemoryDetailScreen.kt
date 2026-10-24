package dev.shipyu.mindverse.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shipyu.mindverse.core.data.db.MemoryItemEntity
import dev.shipyu.mindverse.core.data.db.MemoryItemKind
import dev.shipyu.mindverse.ui.common.BlobThumbnail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Memory detail (ticket 06): full card — title, summary, category, tags, entity chips,
 * ticket block when present, source deep-link + "jump back to origin", blob/thumbnail.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryDetailScreen(
    state: MemoryDetailState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val memory = state.memory
    Surface(modifier = modifier.fillMaxSize()) {
        if (memory == null) {
            Column(modifier = Modifier.padding(16.dp)) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text(
                    text = if (state.loading) "Loading…" else "This Memory is gone",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            return@Surface
        }

        val zone = remember { ZoneId.systemDefault() }
        var expandedItem by remember { mutableStateOf<MemoryItemEntity?>(null) }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { TextButton(onClick = onBack) { Text("← Back") } }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = memory.title, style = MaterialTheme.typography.headlineSmall)
                    memory.category?.let {
                        Text(
                            text = it.replaceFirstChar(Char::uppercase),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = memory.summary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (memory.tags.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            memory.tags.forEach { tag ->
                                AssistChip(onClick = {}, label = { Text("#$tag") })
                            }
                        }
                    }
                }
            }

            state.thumbnailPath?.let { path ->
                item {
                    BlobThumbnail(path = path, modifier = Modifier.fillMaxWidth().height(220.dp))
                }
            }

            val ticketItems = state.items.filter { it.kind == MemoryItemKind.TICKET }
            if (ticketItems.isNotEmpty()) {
                item { TicketBlock(ticketItems, zone) }
            }

            val otherItems = state.items.filter { it.kind != MemoryItemKind.TICKET }
            if (otherItems.isNotEmpty()) {
                item {
                    Text(text = "Entities", style = MaterialTheme.typography.titleMedium)
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        otherItems.forEach { item ->
                            AssistChip(
                                onClick = { expandedItem = item },
                                label = { Text(entityLabel(item)) },
                            )
                        }
                    }
                }
            }

            item {
                SourceBlock(
                    sourceApp = state.sourceCapture?.sourceApp,
                    deepLink = state.sourceCapture?.deepLink,
                )
            }

            expandedItem?.let { item ->
                item(key = "dialog") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = item.kind.name.lowercase().replaceFirstChar(Char::uppercase),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            entityRows(item, zone).forEach { (label, value) ->
                                Text(text = "$label: $value", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { expandedItem = null }) { Text("Close") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TicketBlock(items: List<MemoryItemEntity>, zone: ZoneId) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Ticket", style = MaterialTheme.typography.titleMedium)
            items.forEach { ticket ->
                ticket.ticketCode?.let { Text("PNR: $it", style = MaterialTheme.typography.bodyMedium) }
                ticket.ticketSeat?.let { Text("Seat: $it", style = MaterialTheme.typography.bodyMedium) }
                ticket.ticketGate?.let { Text("Gate: $it", style = MaterialTheme.typography.bodyMedium) }
                ticket.timestampStartMs?.let {
                    Text("Departs: ${formatTime(it, zone)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SourceBlock(sourceApp: String?, deepLink: String?) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Source", style = MaterialTheme.typography.titleMedium)
            Text(
                text = sourceApp ?: "Unknown app",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (deepLink != null) {
                Text(
                    text = deepLink,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, deepLink.toUri()))
                    }
                }) {
                    Text("Jump back to origin ↗")
                }
            }
        }
    }
}

private fun entityLabel(item: MemoryItemEntity): String = when (item.kind) {
    MemoryItemKind.DATE -> "📅 ${item.text}"
    MemoryItemKind.PRICE -> "💰 ${item.text}"
    MemoryItemKind.PLACE -> "📍 ${item.locationName}"
    MemoryItemKind.EVENT -> "🗓 ${item.text}"
    MemoryItemKind.DEADLINE -> "⏰ ${item.text}"
    MemoryItemKind.TICKET -> "🎫 ${item.ticketCode ?: item.text ?: "Ticket"}"
    MemoryItemKind.PERSON -> "👤 ${item.text}"
}

private fun entityRows(item: MemoryItemEntity, zone: ZoneId): List<Pair<String, String>> = buildList {
    item.text?.let { add("Text" to it) }
    item.timestampStartMs?.let { add("Start" to formatTime(it, zone)) }
    item.timestampEndMs?.let { add("End" to formatTime(it, zone)) }
    item.priceAmountMicros?.let {
        add("Amount" to "%.2f".format(it / 1_000_000.0) + (item.priceCurrency?.let { c -> " $c" } ?: ""))
    }
    item.locationName?.let { add("Place" to it) }
    if (item.latitude != null && item.longitude != null) {
        add("Coordinates" to "${item.latitude}, ${item.longitude}")
    }
    item.personHandle?.let { add("Handle" to it) }
}

private fun formatTime(epochMs: Long, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm")
        .withZone(zone)
        .format(Instant.ofEpochMilli(epochMs))
