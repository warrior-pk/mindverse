package dev.shipyu.mindverse.ui.ask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Ask screen (ticket 07): streaming grounded answers with tappable source chips per
 * block, an input row, and the keyless fallback surfaced honestly. Empty state explains
 * the capability; errors surface inline.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskScreen(
    controller: AskController,
    onOpenMemory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.turns.size, state.pending?.blocks?.size) {
        val count = state.turns.size + (if (state.pending != null) 1 else 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.turns.isEmpty() && state.pending == null) {
                    item {
                        Text(text = "Ask", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            text = "Ask questions about what you captured. Answers are strictly " +
                                "grounded in your Memories with source chips for every claim. " +
                                "Works without a key using local results.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.turns, key = { it.hashCode() }) { turn ->
                    TurnCard(turn = turn, onOpenMemory = onOpenMemory)
                }
                state.pending?.let { pending ->
                    item(key = "pending") {
                        TurnCard(turn = pending, onOpenMemory = onOpenMemory)
                    }
                }
            }

            AskInputRow(
                input = input,
                onInputChange = { input = it },
                busy = state.busy,
                onSend = {
                    controller.ask(input)
                    input = ""
                },
                onCancel = { controller.cancel() },
            )
        }
    }
}

@Composable
private fun AskInputRow(
    input: String,
    onInputChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ask your Memories…") },
            singleLine = true,
        )
        if (busy) {
            OutlinedButton(onClick = onCancel) { Text("Stop") }
        } else {
            OutlinedButton(onClick = onSend, enabled = input.isNotBlank()) { Text("Send") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TurnCard(
    turn: AskTurn,
    onOpenMemory: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = turn.query,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                turn.blocks.forEach { block ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = block.text, style = MaterialTheme.typography.bodyMedium)
                        if (block.citations.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                block.citations.forEach { citation ->
                                    AssistChip(
                                        onClick = { onOpenMemory(citation.memoryId) },
                                        label = { Text("[${citation.index}] ${citation.title.take(24)}") },
                                    )
                                }
                            }
                        }
                    }
                }
                if (turn.blocks.isEmpty() && turn.error == null) {
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                turn.error?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                turn.fallbackReason?.let {
                    Text(
                        text = "Answered from local results ($it) — no model call was made.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
