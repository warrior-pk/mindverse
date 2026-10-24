package dev.shipyu.mindverse.ui.captures

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shipyu.mindverse.ui.common.BlobThumbnail
import kotlinx.coroutines.launch

/**
 * Capture review sheet (ticket 06): shows the Capture immediately (even pre-extraction),
 * lets the user edit source context, and confirm or discard. Also the retry surface for
 * dead-lettered captures (confirm re-queues the pipeline).
 */
@Composable
fun CaptureReviewScreen(
    controller: CaptureReviewController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val capture = state.capture

    Surface(modifier = modifier.fillMaxSize()) {
        if (capture == null) {
            Column(modifier = Modifier.padding(16.dp)) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text(
                    text = if (state.loading) "Loading…" else "This Capture is gone",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            return@Surface
        }

        var sourceApp by remember(capture.id) { mutableStateOf(capture.sourceApp.orEmpty()) }
        var deepLink by remember(capture.id) { mutableStateOf(capture.deepLink.orEmpty()) }
        val scope = rememberCoroutineScope()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text(text = "Review capture", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = when {
                    capture.deleted -> "Discarded"
                    capture.processingState?.name == "DEAD_LETTER" -> "Processing failed — confirm to retry"
                    capture.processingState?.name == "DONE" -> "Processed into a Memory"
                    else -> "Processing…"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            BlobThumbnail(
                path = capture.blobPath.takeIf { capture.mimeType?.startsWith("image/") == true },
                modifier = Modifier.fillMaxWidth().height(220.dp),
                placeholder = capture.kind.name,
            )

            capture.processingError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = sourceApp,
                onValueChange = { sourceApp = it },
                label = { Text("Source app") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = deepLink,
                onValueChange = { deepLink = it },
                label = { Text("Source URL / deep link") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            controller.updateSource(sourceApp, deepLink)
                            controller.confirm()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (capture.processingState?.name == "DEAD_LETTER") "Retry" else "Confirm")
                }
                OutlinedButton(
                    onClick = { scope.launch { controller.discard() } },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Discard")
                }
            }

            capture.contentHash.let {
                Text(
                    text = "content ${it.take(12)}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
