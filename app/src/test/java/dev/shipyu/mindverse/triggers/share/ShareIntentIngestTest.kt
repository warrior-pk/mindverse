package dev.shipyu.mindverse.triggers.share

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import dev.shipyu.mindverse.core.data.FixedClock
import dev.shipyu.mindverse.core.data.TestDatabaseFactory
import dev.shipyu.mindverse.core.data.db.CaptureKind
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.data.ingest.CaptureBlobStore
import dev.shipyu.mindverse.core.data.ingest.CaptureIngestionService
import dev.shipyu.mindverse.core.data.ingest.IngestOutcome
import dev.shipyu.mindverse.core.pipeline.PipelineController
import dev.shipyu.mindverse.core.pipeline.PipelineScheduler
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Share-sheet seam (ticket 05): intent → stored Capture through the pipeline entry, with
 * best-effort source context (referrer URL, deep link, MIME).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareIntentIngestTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: MindverseDatabase
    private lateinit var blobStore: CaptureBlobStore
    private lateinit var scheduler: RecordingScheduler
    private lateinit var ingest: ShareIntentIngest

    private fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = TestDatabaseFactory.inMemoryDatabase(context)
        blobStore = CaptureBlobStore(folder.newFolder("blobs"))
        scheduler = RecordingScheduler()
        val clock = FixedClock()
        ingest = ShareIntentIngest(
            context = context,
            blobs = blobStore,
            controller = PipelineController(CaptureIngestionService(db, clock), scheduler),
            clock = clock,
        )
    }

    @Test
    fun `text share stores the body, extracts the URL and queues the pipeline`() = runBlocking {
        setUp()
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Great read: https://example.com/article?id=7. Worth it")

        val outcome = ingest.ingest(intent) as IngestOutcome.Stored
        val capture = outcome.capture
        assertEquals(CaptureKind.SHARE, capture.kind)
        assertEquals("https://example.com/article?id=7", capture.deepLink)
        assertEquals("text/plain", capture.mimeType)
        assertEquals(64, capture.contentHash.length)
        assertEquals(listOf(capture.id), scheduler.enqueued)
        assertEquals(
            "Great read: https://example.com/article?id=7. Worth it",
            File(capture.blobPath!!).readText(),
        )
    }

    @Test
    fun `image share stores the blob with a perceptual hash`() = runBlocking {
        setUp()
        val png = folder.newFile("shared.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        for (y in 0 until 32) for (x in 0 until 32) {
            bitmap.setPixel(x, y, if (x < y) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            png.writeBytes(out.toByteArray())
        }
        bitmap.recycle()

        val intent = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, Uri.fromFile(png))

        val outcome = ingest.ingest(intent) as IngestOutcome.Stored
        assertEquals(CaptureKind.IMAGE, outcome.capture.kind)
        assertEquals("image/png", outcome.capture.mimeType)
        assertNotNull(outcome.capture.phash)
        assertTrue(File(outcome.capture.blobPath!!).exists())
    }

    @Test
    fun `pdf share is classified as PDF`() = runBlocking {
        setUp()
        val pdf = folder.newFile("doc.pdf").apply { writeBytes("%PDF-1.4 fake".toByteArray()) }
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, Uri.fromFile(pdf))

        val outcome = ingest.ingest(intent) as IngestOutcome.Stored
        assertEquals(CaptureKind.PDF, outcome.capture.kind)
        assertEquals("application/pdf", outcome.capture.mimeType)
    }

    @Test
    fun `duplicate share is deduped and not queued twice`() = runBlocking {
        setUp()
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "same note")
        assertTrue(ingest.ingest(intent) is IngestOutcome.Stored)
        assertTrue(ingest.ingest(intent) is IngestOutcome.ExactDuplicate)
        assertEquals(1, scheduler.enqueued.size)
    }

    @Test
    fun `empty share degrades to nothing`() = runBlocking {
        setUp()
        assertNull(ingest.ingest(Intent(Intent.ACTION_SEND).setType("text/plain")))
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `url extraction trims trailing punctuation`() {
        assertEquals("https://a.example/x", ShareIntentIngest.firstHttpUrl("see https://a.example/x."))
        assertEquals("https://b.example/y?q=1", ShareIntentIngest.firstHttpUrl("(https://b.example/y?q=1)"))
        assertNull(ShareIntentIngest.firstHttpUrl("no links here"))
    }

    private class RecordingScheduler : PipelineScheduler {
        val enqueued = mutableListOf<String>()
        override fun enqueue(captureId: String) {
            enqueued += captureId
        }
    }
}
