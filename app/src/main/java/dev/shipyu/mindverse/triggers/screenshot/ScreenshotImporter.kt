package dev.shipyu.mindverse.triggers.screenshot

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import dev.shipyu.mindverse.core.data.db.CaptureKind
import dev.shipyu.mindverse.core.data.ingest.CaptureBlobStore
import dev.shipyu.mindverse.core.data.ingest.ImageFingerprint
import dev.shipyu.mindverse.core.data.ingest.IngestOutcome
import dev.shipyu.mindverse.core.data.ingest.ProposedCapture
import dev.shipyu.mindverse.core.data.ingest.extensionForMime
import dev.shipyu.mindverse.core.pipeline.PipelineController
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * M1 slice of Import (spec: opt-in per album): scans the Screenshots album via
 * MediaStore and ingests the latest rows through the shared pipeline entry.
 * Dedupe (content-hash) silently skips already-imported screenshots, so repeated
 * taps are safe. Requires READ_MEDIA_IMAGES; without it returns 0.
 */
class ScreenshotImporter(
    private val context: Context,
    private val blobs: CaptureBlobStore,
    private val controller: PipelineController,
    private val clock: Clock,
) {
    suspend fun importLatest(limit: Int = 20): ImportResult = withContext(Dispatchers.IO) {
        var imported = 0
        var skipped = 0
        val rows = queryLatest(limit * 2) // over-fetch: dupes are skipped
        for (row in rows) {
            if (imported >= limit) break
            val bytes = runCatching {
                context.contentResolver.openInputStream(row.uri)?.use { it.readBytes() }
            }.getOrNull() ?: continue
            val mime = row.mimeType
                ?: runCatching { context.contentResolver.getType(row.uri) }.getOrNull()
                ?: "image/png"
            val stored = blobs.store(bytes, extensionForMime(mime))
            val outcome = controller.submit(
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
            when (outcome) {
                is IngestOutcome.Stored, is IngestOutcome.NearDuplicate -> imported++
                is IngestOutcome.ExactDuplicate -> skipped++
            }
        }
        ImportResult(imported = imported, skipped = skipped)
    }

    private fun queryLatest(limit: Int): List<Screenshot> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%Screenshots%")
        return runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val out = ArrayList<Screenshot>()
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                while (cursor.moveToNext() && out.size < limit) {
                    val id = cursor.getLong(idCol)
                    val mime = cursor.getString(mimeCol)
                    out += Screenshot(
                        uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id,
                        ),
                        mimeType = mime,
                    )
                }
                out
            } ?: emptyList()
        }.getOrNull() ?: emptyList()
    }
}

data class ImportResult(val imported: Int, val skipped: Int)
