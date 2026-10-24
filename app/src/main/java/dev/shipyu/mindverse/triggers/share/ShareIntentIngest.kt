package dev.shipyu.mindverse.triggers.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.shipyu.mindverse.core.data.db.CaptureKind
import dev.shipyu.mindverse.core.data.ingest.CaptureBlobStore
import dev.shipyu.mindverse.core.data.ingest.ImageFingerprint
import dev.shipyu.mindverse.core.data.ingest.IngestOutcome
import dev.shipyu.mindverse.core.data.ingest.ProposedCapture
import dev.shipyu.mindverse.core.data.ingest.extensionForMime
import dev.shipyu.mindverse.core.pipeline.PipelineController
import java.time.Clock

/**
 * Share-sheet trigger (ticket 05): ACTION_SEND text / image / PDF → one Capture through the
 * shared pipeline entry. Best-effort source context — missing referrer, MIME type, or text
 * degrade to nulls rather than rejecting the capture.
 *
 * Text shares are stored as UTF-8 blobs so extraction can read them back; a URL found in
 * the text becomes the Capture's deep link (the "jump back to origin" surface in ticket 06).
 */
class ShareIntentIngest(
    private val context: Context,
    private val blobs: CaptureBlobStore,
    private val controller: PipelineController,
    private val clock: Clock,
) {

    /** Returns null when the intent carries neither a readable stream nor text. */
    suspend fun ingest(intent: Intent): IngestOutcome? {
        val type = intent.type
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val streamUri = streamUri(intent)
        val sourceApp = sourceApp(intent)
        val deepLink = text?.let(::firstHttpUrl)

        val proposed = when {
            streamUri != null -> {
                val mime = context.contentResolver.getType(streamUri) ?: type
                val bytes = runCatching {
                    context.contentResolver.openInputStream(streamUri)?.use { it.readBytes() }
                }.getOrNull() ?: return null
                val stored = blobs.store(bytes, extensionForMime(mime))
                ProposedCapture(
                    kind = kindForMime(mime),
                    blobPath = stored.path,
                    sourceApp = sourceApp,
                    deepLink = deepLink,
                    contentHash = stored.sha256,
                    phash = if (mime?.startsWith("image/") == true) ImageFingerprint.dhashBytes(bytes) else null,
                    capturedAt = clock.millis(),
                    mimeType = mime,
                )
            }
            !text.isNullOrBlank() -> {
                val stored = blobs.storeText(text)
                ProposedCapture(
                    kind = CaptureKind.SHARE,
                    blobPath = stored.path,
                    sourceApp = sourceApp,
                    deepLink = deepLink,
                    contentHash = stored.sha256,
                    phash = null,
                    capturedAt = clock.millis(),
                    mimeType = "text/plain",
                )
            }
            else -> null
        }
        return proposed?.let { controller.submit(it) }
    }

    private fun streamUri(intent: Intent): Uri? {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { return it }
        val clip = intent.clipData ?: return null
        return if (clip.itemCount > 0) clip.getItemAt(0).uri else null
    }

    /** Best-effort source identity: share-sheet referrer, else the referrer name extra. */
    private fun sourceApp(intent: Intent): String? =
        intent.getParcelableExtra(Intent.EXTRA_REFERRER, Uri::class.java)?.authority
            ?: intent.getStringExtra(Intent.EXTRA_REFERRER_NAME)

    private fun kindForMime(mime: String?): CaptureKind = when {
        mime == null -> CaptureKind.SHARE
        mime.startsWith("image/") -> CaptureKind.IMAGE
        mime == "application/pdf" -> CaptureKind.PDF
        else -> CaptureKind.SHARE
    }

    companion object {
        private val HTTP_URL = Regex("""https?://[^\s<>"']+""")

        /** First http(s) URL in shared text, trimmed of trailing punctuation. */
        fun firstHttpUrl(text: String): String? =
            HTTP_URL.find(text)?.value?.trimEnd('.', ',', ')', ']', '}', ';', ':', '!', '?')
    }
}
