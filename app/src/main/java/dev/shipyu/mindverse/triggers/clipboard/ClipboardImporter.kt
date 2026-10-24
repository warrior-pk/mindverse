package dev.shipyu.mindverse.triggers.clipboard

import android.content.ClipboardManager
import android.content.Context
import dev.shipyu.mindverse.core.data.db.CaptureKind
import dev.shipyu.mindverse.core.data.ingest.CaptureBlobStore
import dev.shipyu.mindverse.core.data.ingest.IngestOutcome
import dev.shipyu.mindverse.core.data.ingest.ProposedCapture
import dev.shipyu.mindverse.core.pipeline.PipelineController
import dev.shipyu.mindverse.triggers.share.ShareIntentIngest
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Clipboard auto-add (opt-in, off by default): reads the primary clip while the app
 * is in the foreground (background reads are blocked by the OS) and ingests new
 * text through the shared pipeline entry. Dedupe silently skips unchanged clips,
 * so repeated Brain opens are safe. Non-text clips are ignored.
 */
class ClipboardImporter(
    private val context: Context,
    private val blobs: CaptureBlobStore,
    private val controller: PipelineController,
    private val clock: Clock,
) {
    suspend fun importOnce(): ImportResult = withContext(Dispatchers.IO) {
        val text = readClipboardText()?.take(MAX_CHARS).orEmpty()
        if (text.isEmpty()) return@withContext ImportResult(imported = 0, skipped = 0)
        val stored = blobs.storeText(text)
        val outcome = controller.submit(
            ProposedCapture(
                kind = CaptureKind.TEXT,
                blobPath = stored.path,
                sourceApp = "clipboard",
                deepLink = ShareIntentIngest.firstHttpUrl(text),
                contentHash = stored.sha256,
                phash = null,
                capturedAt = clock.millis(),
                mimeType = "text/plain",
            ),
        )
        when (outcome) {
            is IngestOutcome.Stored, is IngestOutcome.NearDuplicate ->
                ImportResult(imported = 1, skipped = 0)
            is IngestOutcome.ExactDuplicate ->
                ImportResult(imported = 0, skipped = 1)
        }
    }

    private fun readClipboardText(): String? = runCatching {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
        if (!manager.hasPrimaryClip()) return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        clip.getItemAt(0).coerceToText(context)?.toString()?.trim()?.takeUnless { it.isEmpty() }
    }.getOrNull()

    companion object {
        /** Clipboard can be large; cap what we persist per capture. */
        const val MAX_CHARS = 20_000
    }
}

data class ImportResult(val imported: Int, val skipped: Int)
