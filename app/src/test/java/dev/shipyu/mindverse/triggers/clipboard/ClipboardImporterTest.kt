package dev.shipyu.mindverse.triggers.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dev.shipyu.mindverse.core.data.FixedClock
import dev.shipyu.mindverse.core.data.TestDatabaseFactory
import dev.shipyu.mindverse.core.data.db.CaptureProcessingState
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.data.ingest.CaptureIngestionService
import dev.shipyu.mindverse.core.pipeline.PipelineController
import dev.shipyu.mindverse.core.pipeline.PipelineScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Clipboard auto-add: new text ingests once through the shared pipeline,
 * unchanged clips dedupe-skip, empty clipboard is a no-op.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardImporterTest {

    private fun setUp(): Triple<MindverseDatabase, RecordingScheduler, ClipboardImporter> {
        val context = RuntimeEnvironment.getApplication()
        val db = TestDatabaseFactory.inMemoryDatabase(context)
        val clock = FixedClock(1_000_000L)
        val scheduler = RecordingScheduler()
        val controller = PipelineController(CaptureIngestionService(db, clock), scheduler)
        return Triple(db, scheduler, ClipboardImporter(context, controller = controller, blobs = blobStore(context), clock = clock))
    }

    private fun blobStore(context: Context) =
        dev.shipyu.mindverse.core.data.ingest.CaptureBlobStore.forApp(context)

    private fun setClipboard(text: String?) {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(ClipboardManager::class.java)
        if (text == null) {
            manager.clearPrimaryClip()
        } else {
            manager.setPrimaryClip(ClipData.newPlainText("test", text))
        }
    }

    @Test
    fun `new clipboard text imports once then dedupes`() = runBlocking {
        val (db, scheduler, importer) = setUp()
        setClipboard("remember to buy milk https://example.com/list")

        val first = importer.importOnce()
        assertEquals(1, first.imported)
        assertEquals(1, scheduler.enqueued.size)

        val capture = db.captureDao().listActive().single()
        assertEquals("clipboard", capture.sourceApp)
        assertEquals("https://example.com/list", capture.deepLink)
        assertEquals(CaptureProcessingState.PENDING, capture.processingState)

        val second = importer.importOnce()
        assertEquals(0, second.imported)
        assertEquals(1, second.skipped)
    }

    @Test
    fun `empty clipboard is a no-op`() = runBlocking {
        val (_, scheduler, importer) = setUp()
        setClipboard(null)

        val result = importer.importOnce()
        assertEquals(0, result.imported)
        assertEquals(0, result.skipped)
        assertEquals(0, scheduler.enqueued.size)
    }

    private class RecordingScheduler : PipelineScheduler {
        val enqueued = mutableListOf<String>()
        override fun enqueue(captureId: String) {
            enqueued += captureId
        }
    }
}
