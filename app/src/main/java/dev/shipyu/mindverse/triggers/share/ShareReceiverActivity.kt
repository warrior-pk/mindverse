package dev.shipyu.mindverse.triggers.share

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.shipyu.mindverse.core.data.ingest.IngestOutcome
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Share-target trampoline (ticket 05): receives ACTION_SEND, stores the Capture through
 * [ShareIntentIngest] (pipeline queue follows), shows a toast, and finishes. No UI of its
 * own — the full review sheet belongs to ticket 06.
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    @Inject
    lateinit var ingest: ShareIntentIngest

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep a visible surface for the split second the share hand-off takes.
        setContent {
            MaterialTheme {
                Surface { Text("Saving to Mindverse…") }
            }
        }
        lifecycleScope.launch {
            val outcome = runCatching { ingest.ingest(intent) }.getOrNull()
            Toast.makeText(this@ShareReceiverActivity, messageFor(outcome), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun messageFor(outcome: IngestOutcome?): String = when (outcome) {
        null -> "Nothing to save"
        is IngestOutcome.ExactDuplicate -> "Already in Mindverse"
        is IngestOutcome.Stored, is IngestOutcome.NearDuplicate -> "Saved to Mindverse"
    }
}
