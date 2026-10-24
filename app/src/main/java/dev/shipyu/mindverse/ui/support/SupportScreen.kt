package dev.shipyu.mindverse.ui.support

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shipyu.mindverse.core.updates.UpdateController
import dev.shipyu.mindverse.core.updates.UpdateUiState
import dev.shipyu.mindverse.support.QrCode
import dev.shipyu.mindverse.support.SupportConfig
import dev.shipyu.mindverse.ui.about.AboutCard

/**
 * Support screen (ticket 09 / spec "Support screen"): static, zero-backend, zero-egress.
 * India card renders the UPI QR locally and launches the system chooser; global card opens
 * GitHub Sponsors. Both ship as data strings (remote-config-able in ticket 10). No payment
 * SDK, no analytics, no network — every rail is external.
 */
@Composable
fun SupportScreen(
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
                text = "Mindverse is built in the open. If it helps you, you can support " +
                    "its development — every rail below is external; the app never sees " +
                    "your payment.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            UpiCard()
            SponsorsCard()
            AboutCard(
                updateController = updateController,
                versionName = versionName,
                versionCode = versionCode,
            )
        }
    }
}

@Composable
private fun UpiCard() {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val qrBitmap = remember {
        QrCode.toBitmap(QrCode.encodeMatrix(SupportConfig.upiPaymentUri))
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "India · UPI (0% fees)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "UPI payment QR code",
                modifier = Modifier.size(220.dp),
            )
            Text(
                text = "Scan with any UPI app, or use the buttons below. " +
                    "You choose the amount.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = SupportConfig.UPI_VPA,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_VIEW, SupportConfig.upiPaymentUri.toUri()),
                                "Pay via UPI app",
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Pay via UPI app")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        copyToClipboard(context, "UPI VPA", SupportConfig.UPI_VPA)
                        copied = true
                    },
                ) {
                    Text(if (copied) "VPA copied ✓" else "Copy VPA")
                }
            }
        }
    }
}

@Composable
private fun SponsorsCard() {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Worldwide · GitHub Sponsors (0% fees)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "One-time and monthly sponsorships via GitHub. GitHub covers " +
                    "payment processing — everything reaches the project.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, SupportConfig.SPONSORS_URL.toUri()))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("♥ Sponsor on GitHub")
            }
            OutlinedButton(
                onClick = {
                    copyToClipboard(context, "Sponsor link", SupportConfig.SPONSORS_URL)
                    copied = true
                },
            ) {
                Text(if (copied) "Link copied ✓" else "Copy link")
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}
