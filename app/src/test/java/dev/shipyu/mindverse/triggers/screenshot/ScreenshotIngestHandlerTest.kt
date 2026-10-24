package dev.shipyu.mindverse.triggers.screenshot

import android.graphics.Bitmap
import android.net.Uri
import dev.shipyu.mindverse.core.data.FixedClock
import dev.shipyu.mindverse.core.data.TestDatabaseFactory
import dev.shipyu.mindverse.core.data.db.CaptureKind
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.data.ingest.CaptureBlobStore
import dev.shipyu.mindverse.core.data.ingest.CaptureIngestionService
import dev.shipyu.mindverse.core.pipeline.PipelineController
import dev.shipyu.mindverse.core.pipeline.PipelineScheduler
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Watcher decision seam (ticket 05, spec R4-4): prompt-first by default; auto-ingest
 * (default OFF) skips the prompt. Both paths enqueue through the shared pipeline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenshotIngestHandlerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: MindverseDatabase
    private lateinit var scheduler: RecordingScheduler
    private lateinit var settings: WatcherSettings
    private lateinit var prompt: RecordingPrompt
    private lateinit var handler: ScreenshotIngestHandler

    private fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = TestDatabaseFactory.inMemoryDatabase(context)
        scheduler = RecordingScheduler()
        settings = WatcherSettings(context)
        prompt = RecordingPrompt()
        val clock = FixedClock()
        handler = ScreenshotIngestHandler(
            blobs = CaptureBlobStore(folder.newFolder("blobs")),
            controller = PipelineController(CaptureIngestionService(db, clock), scheduler),
            source = FakeSource(Screenshot(Uri.parse("content://media/screenshot/1"), "image/png")),
            reader = FakeReader(pngBytes()),
            settings = settings,
            prompt = prompt,
            clock = clock,
        )
    }

    @Test
    fun `prompt-first default does not ingest until accepted`() = runBlocking {
        setUp()
        handler.onScreenshotDetected()
        assertEquals(1, prompt.prompted.size)
        assertTrue(scheduler.enqueued.isEmpty())

        handler.accept(prompt.prompted.single().uri, prompt.prompted.single().mimeType)
        assertEquals(1, scheduler.enqueued.size)
        val capture = db.captureDao().listActive().single()
        assertEquals(CaptureKind.IMAGE, capture.kind)
        assertNotNull(capture.phash)
        assertTrue(capture.contentHash.length == 64)
    }

    @Test
    fun `auto-ingest enabled skips the prompt`() = runBlocking {
        setUp()
        settings.setAutoIngest(true)
        handler.onScreenshotDetected()
        assertTrue(prompt.prompted.isEmpty())
        assertEquals(1, scheduler.enqueued.size)
    }

    @Test
    fun `missing screenshot is a no-op`() = runBlocking {
        setUp()
        handler = ScreenshotIngestHandler(
            blobs = CaptureBlobStore(folder.newFolder("blobs-2")),
            controller = PipelineController(CaptureIngestionService(db, FixedClock()), scheduler),
            source = FakeSource(null),
            reader = FakeReader(pngBytes()),
            settings = settings,
            prompt = prompt,
            clock = FixedClock(),
        )
        handler.onScreenshotDetected()
        assertTrue(prompt.prompted.isEmpty())
        assertTrue(scheduler.enqueued.isEmpty())
    }

    private fun pngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        for (y in 0 until 24) for (x in 0 until 24) {
            bitmap.setPixel(x, y, if ((x + y) % 2 == 0) 0xFF222222.toInt() else 0xFFEEEEEE.toInt())
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private class FakeSource(private val screenshot: Screenshot?) : ScreenshotSource {
        override suspend fun latest(): Screenshot? = screenshot
    }

    private class FakeReader(private val bytes: ByteArray) : ScreenshotReader {
        override fun read(uri: Uri): ByteArray = bytes
        override fun mimeType(uri: Uri): String = "image/png"
    }

    private class RecordingPrompt : ScreenshotPrompt {
        val prompted = mutableListOf<Screenshot>()
        override fun prompt(screenshot: Screenshot) {
            prompted += screenshot
        }
    }

    private class RecordingScheduler : PipelineScheduler {
        val enqueued = mutableListOf<String>()
        override fun enqueue(captureId: String) {
            enqueued += captureId
        }
    }
}
