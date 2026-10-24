package dev.shipyu.mindverse.firstrun

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private enum class FirstRunStep { WELCOME, PERMISSIONS, PRIVACY, KEY }

/**
 * First-run flow (ticket 08): four steps, skippable key entry, and only the two
 * permissions M1 actually needs — notifications (processing chip + watcher prompt) and
 * media read (screenshot watcher). No over-permissioning.
 */
@Composable
fun FirstRunScreen(
    controller: FirstRunController,
    onOpenPrivacy: () -> Unit,
    onOpenSupport: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableStateOf(FirstRunStep.WELCOME) }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* grants are best-effort; features degrade individually */ }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                FirstRunStep.WELCOME -> {
                    Text(text = "Welcome to Mindverse", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = "Your local second brain. Mindverse captures what you share " +
                            "into it — links, images, screenshots — turns each item into a " +
                            "structured Memory, and keeps everything searchable on this device.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "The phone is the source of truth: no cloud sync, and every " +
                            "outbound AI call is visible in the Privacy screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { step = FirstRunStep.PERMISSIONS }, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue")
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                controller.completeOnboarding()
                                onOpenSupport()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Ways to support Mindverse →")
                    }
                }

                FirstRunStep.PERMISSIONS -> {
                    Text(text = "Two permissions, both optional", style = MaterialTheme.typography.headlineSmall)
                    PermissionRow(
                        title = "Notifications",
                        body = "Shows the processing chip and the “Add to Mindverse?” screenshot prompt.",
                    )
                    PermissionRow(
                        title = "Photos & videos",
                        body = "Lets the screenshot watcher save the screenshot you just took. " +
                            "Mindverse never browses your gallery.",
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            permissionLauncher.launch(
                                arrayOf(
                                    "android.permission.POST_NOTIFICATIONS",
                                    "android.permission.READ_MEDIA_IMAGES",
                                ),
                            )
                            step = FirstRunStep.PRIVACY
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Ask for permissions")
                    }
                    TextButton(
                        onClick = { step = FirstRunStep.PRIVACY },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Not now")
                    }
                }

                FirstRunStep.PRIVACY -> {
                    Text(text = "Privacy, in one paragraph", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "Memories stay on this device. When a feature needs AI (extraction, " +
                            "Ask), the request goes through one chokepoint that masks PII and logs " +
                            "every outbound call — you can read the log and turn any feature's " +
                            "egress off on the Privacy screen. Features degrade gracefully instead " +
                            "of sending when toggled off.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    TextButton(onClick = onOpenPrivacy) { Text("Open Privacy screen") }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { step = FirstRunStep.KEY }, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue")
                    }
                }

                FirstRunStep.KEY -> KeyStep(
                    controller = controller,
                    onSkip = {
                        scope.launch {
                            controller.completeOnboarding()
                            onDone()
                        }
                    },
                    onSaved = {
                        scope.launch {
                            controller.completeOnboarding()
                            onDone()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun KeyStep(
    controller: FirstRunController,
    onSkip: () -> Unit,
    onSaved: () -> Unit,
) {
    var useGemini by remember { mutableStateOf(true) }
    var geminiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var openAiKey by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Text(text = "Bring your own key (optional)", style = MaterialTheme.typography.headlineSmall)
    Text(
        text = "AI features use your own provider key. Without one, extraction dead-letters " +
            "with a visible reason and Ask falls back to local results — the app stays usable.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = useGemini, onClick = { useGemini = true })
        Text("Gemini (Google AI Studio)")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = !useGemini, onClick = { useGemini = false })
        Text("OpenAI-compatible (OpenRouter, Ollama, LM Studio…)")
    }

    if (useGemini) {
        OutlinedTextField(
            value = geminiKey,
            onValueChange = { geminiKey = it },
            label = { Text("Gemini API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL (e.g. https://openrouter.ai/api/v1)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model id") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = openAiKey,
            onValueChange = { openAiKey = it },
            label = { Text("API key (optional for local servers)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Button(
        onClick = {
            scope.launch {
                if (useGemini) {
                    controller.saveGeminiKey(geminiKey)
                } else {
                    controller.saveOpenAiCompatible(baseUrl, model, openAiKey)
                }
                onSaved()
            }
        },
        enabled = if (useGemini) geminiKey.isNotBlank() else baseUrl.isNotBlank() && model.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Save and finish")
    }
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
        Text("Skip — stay keyless")
    }
}

@Composable
private fun PermissionRow(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
