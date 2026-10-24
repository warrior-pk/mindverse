package dev.shipyu.mindverse.ui.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shipyu.mindverse.core.updates.UpdateController
import dev.shipyu.mindverse.ui.about.AboutCard

/**
 * Support screen shell (ticket 06's nav entry): the About/update card lands here in
 * ticket 08; the UPI + GitHub Sponsors payment cards land in ticket 09.
 */
@Composable
fun SupportPlaceholderScreen(
    updateController: UpdateController,
    versionName: String,
    versionCode: Int,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "Support", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "If Mindverse helps you, you can support its development — UPI for " +
                    "India, GitHub Sponsors worldwide.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Payment options land in ticket 09.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AboutCard(
                updateController = updateController,
                versionName = versionName,
                versionCode = versionCode,
            )
        }
    }
}
