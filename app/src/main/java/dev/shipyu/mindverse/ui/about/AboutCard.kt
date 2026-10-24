package dev.shipyu.mindverse.ui.about

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shipyu.mindverse.core.updates.UpdateController
import dev.shipyu.mindverse.core.updates.UpdateUiState
import kotlinx.coroutines.launch

/**
 * About + in-app update check card (ticket 08): shows the running version, runs the
 * GitHub Releases check (debounced cold-start + manual button), and deep-links to the APK
 * asset when a newer versionCode is published. No store APIs.
 */
@Composable
fun AboutCard(
    updateController: UpdateController,
    versionName: String,
    versionCode: Int,
    modifier: Modifier = Modifier,
) {
    val state by updateController.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "Mindverse $versionName ($versionCode)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Sideload build. Updates come from GitHub Releases — nothing is " +
                    "downloaded until you tap Download and confirm the install.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val current = state) {
                UpdateUiState.Idle -> {}
                UpdateUiState.Checking -> Text(
                    text = "Checking for updates…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UpdateUiState.UpToDate -> Text(
                    text = "You're on the latest version.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is UpdateUiState.Available -> {
                    Text(
                        text = "Version ${current.outcome.versionName} (${current.outcome.versionCode}) is available.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val url = current.outcome.apkUrl ?: current.outcome.releaseUrl
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                                }
                            },
                        ) {
                            Text(if (current.outcome.apkUrl != null) "Download update" else "Open release page")
                        }
                    }
                }
                is UpdateUiState.Failed -> Text(
                    text = "Update check failed (${current.reason}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scope.launch { updateController.checkNow() } },
                    enabled = state !is UpdateUiState.Checking,
                ) {
                    Text("Check for updates")
                }
            }
        }
    }
}
