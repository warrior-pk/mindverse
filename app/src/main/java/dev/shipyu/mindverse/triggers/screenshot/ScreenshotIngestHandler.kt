package dev.shipyu.mindverse.triggers.screenshot

import android.net.Uri
import dev.shipyu.mindverse.core.data.db.CaptureKind
import dev.shipyu.mindverse.core.data.ingest.CaptureBlobStore
import dev.shipyu.mindverse.core.data.ingest.ImageFingerprint
import dev.shipyu.mindverse.core.data.ingest.ProposedCapture
import dev.shipyu.mindverse.core.data.ingest.extensionForMime
import dev.shipyu.mindverse.core.pipeline.PipelineController
import java.time.Clock

/**
 * Screenshot-watcher decision seam (ticket 05, spec R4-4): prompt-first by default; when
 * auto-ingest is enabled locally the screenshot goes straight into the pipeline.
 * Both paths funnel through the same ingestion entry as every other trigger.
 */
class ScreenshotIngestHandler(
    private val blobs: CaptureBlobStore,
    private val controller: PipelineController,
    private val source: ScreenshotSource,
    private val reader: ScreenshotReader,
    private val settings: WatcherSettings,
    private val prompt: ScreenshotPrompt,
    private val clock: Clock,
) {
    /** Called from MainActivity's ScreenCaptureCallback (while the app is visible). */
    suspend fun onScreenshotDetected() {
        val screenshot = source.latest() ?: return
        if (settings.isAutoIngestEnabled()) {
            ingest(screenshot)
        } else {
            prompt.prompt(screenshot)
        }
    }

    /** Called by the prompt notification's "Add" action. */
    suspend fun accept(uri: Uri, mimeType: String?) {
        ingest(Screenshot(uri, mimeType))
    }

    private suspend fun ingest(screenshot: Screenshot) {
        val bytes = reader.read(screenshot.uri) ?: return
        val mime = screenshot.mimeType ?: reader.mimeType(screenshot.uri) ?: "image/png"
        val stored = blobs.store(bytes, extensionForMime(mime))
        controller.submit(
            ProposedCapture(
                kind = CaptureKind.IMAGE,
                blobPath = stored.path,
                sourceApp = null,
                deepLink = null,
                contentHash = stored.sha256,
                phash = ImageFingerprint.dhashBytes(bytes),
                capturedAt = clock.millis(),
                mimeType = mime,
            ),
        )
    }
}

/** Prompt surface for the watcher (production: a notification with an "Add" action). */
interface ScreenshotPrompt {
    fun prompt(screenshot: Screenshot)
}
