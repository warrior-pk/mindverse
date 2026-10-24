package dev.shipyu.mindverse.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import dev.shipyu.mindverse.core.data.db.EgressOutcome

/**
 * Privacy screen (ticket 03 — the only UI in this issue; spec R5-1): chronological
 * outbound-call log grouped by day + per-feature egress toggles. Renders live from
 * `egress_log` via [PrivacyController].
 */
@Composable
fun PrivacyScreen(
    controller: PrivacyController,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle(initialValue = PrivacyUiState.idle())
    val zone = ZoneId.systemDefault()
    val scope = rememberCoroutineScope()

    Surface(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { PrivacyHeader() }
            items(state.featureToggles.entries.toList()) { (feature, enabled) ->
                FeatureToggleRow(
                    featureLabel = feature.displayLabel,
                    reasonLabel = feature.reasonLabel,
                    enabled = enabled,
                    onToggle = { checked -> scope.launch { controller.setFeatureEnabled(feature, checked) } },
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            if (state.days.isEmpty()) {
                item {
                    Text(
                        text = "No outbound AI calls yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.days.forEach { day ->
                item(key = "day-${day.epochDay}") {
                    Text(
                        text = dayLabel(day.epochDay, zone),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    HorizontalDivider()
                }
                items(day.rows, key = { it.id }) { row ->
                    EgressLogRow(row)
                }
            }
        }
    }
}

@Composable
private fun PrivacyHeader() {
    Column {
        Text(text = "Privacy", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Every outbound AI call passes through this log. Turning a toggle off " +
                "keeps that feature fully local (it degrades instead of sending).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FeatureToggleRow(
    featureLabel: String,
    reasonLabel: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = featureLabel, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = reasonLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun EgressLogRow(row: PrivacyLogRow) {
    val failure = row.outcome != EgressOutcome.SUCCESS
    val accent = if (failure) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.featureLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = accent,
            )
            Text(
                text = timeLabel(row.atMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = buildString {
                append(row.provider)
                append(" · ")
                append(formatBytes(row.bytesOut))
                append(" out")
                if (row.payloadClasses.isNotEmpty()) {
                    append(" · masked: ")
                    append(row.payloadClasses.joinToString(", ") { it.name.lowercase() })
                }
                if (failure) {
                    append(" · ")
                    append(if (row.outcome == EgressOutcome.DENIED) "denied" else "failed")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        row.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun dayLabel(epochDay: Long, zone: ZoneId): String =
    LocalDate.ofEpochDay(epochDay)
        .atStartOfDay(zone)
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

private fun timeLabel(atMillis: Long): String =
    Instant.ofEpochMilli(atMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

private fun formatBytes(bytes: Int): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1f kB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
