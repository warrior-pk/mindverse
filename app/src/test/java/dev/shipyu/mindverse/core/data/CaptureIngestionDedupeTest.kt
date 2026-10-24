package dev.shipyu.mindverse.core.data

import dev.shipyu.mindverse.core.data.db.CaptureKind
import dev.shipyu.mindverse.core.data.db.CaptureDao
import dev.shipyu.mindverse.core.data.ingest.CaptureIngestionService
import dev.shipyu.mindverse.core.data.ingest.IngestOutcome
import dev.shipyu.mindverse.core.data.ingest.ProposedCapture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Dedupe contract at the ingestion boundary (spec R5-3, ticket 02):
 * exact dup (byte hash) silently skipped; near-dup (same pHash) stored but flagged;
 * tombstoned captures do not shadow re-ingestion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureIngestionDedupeTest {
    private fun proposed(
        contentHash: String,
        phash: Long? = null,
        kind: CaptureKind = CaptureKind.IMAGE,
    ) = ProposedCapture(
        kind = kind,
        blobPath = if (kind == CaptureKind.TEXT) null else "/data/blobs/$contentHash.bin",
        sourceApp = "com.example.app",
        deepLink = "https://example.com/x",
        contentHash = contentHash,
        phash = phash,
        capturedAt = 42L,
    )

    @Test
    fun uniqueContentIsStored() {
        runBlocking {
            val db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
            val clock = FixedClock()
            val service = CaptureIngestionService(db, clock)

            val outcome = service.ingest(proposed(contentHash = "h1".repeat(32), phash = 0x11L))

            assertTrue(outcome is IngestOutcome.Stored)
            val capture = (outcome as IngestOutcome.Stored).capture
            assertEquals(CaptureKind.IMAGE, capture.kind)
            assertEquals("h1".repeat(32), capture.contentHash)
            assertEquals(0x11L, capture.phash)
            assertEquals("com.example.app", capture.sourceApp)
            assertEquals("https://example.com/x", capture.deepLink)
            assertNull(capture.nearDuplicateOf)
            // Sync-ready stamps: createdAt == updatedAt at insert (ADR-0003).
            assertEquals(clock.millis, capture.createdAt)
            assertEquals(clock.millis, capture.updatedAt)
            assertEquals(42L, capture.capturedAt)
            assertEquals(false, capture.deleted)
        }
    }

    @Test
    fun exactDuplicateIsSilentlySkipped() {
        runBlocking {
            val db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
            val clock = FixedClock()
            val service = CaptureIngestionService(db, clock)

            val first = service.ingest(proposed(contentHash = "h2".repeat(32)))
            val second = service.ingest(proposed(contentHash = "h2".repeat(32)))

            assertTrue(first is IngestOutcome.Stored)
            assertTrue(second is IngestOutcome.ExactDuplicate)
            val stored = (second as IngestOutcome.ExactDuplicate).existing
            assertEquals((first as IngestOutcome.Stored).capture.id, stored.id)
            // No second row was written (silent skip, spec R5-3).
            assertEquals(1, db.captureDao().countAll())
        }
    }

    @Test
    fun nearDuplicateBySamePHashIsStoredFlaggedForMerge() {
        runBlocking {
            val db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
            val clock = FixedClock()
            val service = CaptureIngestionService(db, clock)

            val first = service.ingest(proposed(contentHash = "h3".repeat(32), phash = 0xA1B2L))
            val second = service.ingest(proposed(contentHash = "h4".repeat(32), phash = 0xA1B2L))

            assertTrue(first is IngestOutcome.Stored)
            assertTrue(second is IngestOutcome.NearDuplicate)
            val (stored, similarTo) = second as IngestOutcome.NearDuplicate
            assertEquals((first as IngestOutcome.Stored).capture.id, similarTo.id)
            assertEquals(similarTo.id, stored.nearDuplicateOf)
            // Different byte hashes: both rows exist; the merge prompt resolves later.
            assertEquals(2, db.captureDao().countAll())
        }
    }

    @Test
    fun tombstonedCaptureDoesNotShadowReingestion() {
        runBlocking {
            val db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
            val clock = FixedClock()
            val service = CaptureIngestionService(db, clock)
            val captureDao = db.captureDao()

            val first = (service.ingest(proposed(contentHash = "h5".repeat(32))) as IngestOutcome.Stored).capture
            captureDao.tombstone(first.id, now = ++clock.millis)

            val reingest = service.ingest(proposed(contentHash = "h5".repeat(32)))
            assertTrue(reingest is IngestOutcome.Stored)
            assertEquals(2, captureDao.countAll())
            assertEquals(1, captureDao.countTombstoned())
        }
    }

    @Test
    fun phashMatchBeatsDifferentHashEvenWithoutPHashOnNew() {
        runBlocking {
            val db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
            val clock = FixedClock()
            val service = CaptureIngestionService(db, clock)

            service.ingest(proposed(contentHash = "h6".repeat(32), phash = 0xCAFEL))
            // New capture has no pHash: not flagged, stored unique.
            val noPHash = service.ingest(proposed(contentHash = "h7".repeat(32), phash = null))
            assertTrue(noPHash is IngestOutcome.Stored)
            assertNull((noPHash as IngestOutcome.Stored).capture.nearDuplicateOf)
        }
    }

    @Test
    fun tombstoneStampsUpdatedAtAndKeepsRow() {
        runBlocking {
            val db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
            val clock = FixedClock()
            val service = CaptureIngestionService(db, clock)
            val captureDao = db.captureDao()

            val capture = (service.ingest(proposed(contentHash = "h8".repeat(32))) as IngestOutcome.Stored).capture
            clock.advanceBy(5_000)
            captureDao.tombstone(capture.id, now = clock.millis)

            val tombstoned = captureDao.getCapture(capture.id)
            assertNotNull(tombstoned)
            assertEquals(true, tombstoned!!.deleted)
            assertEquals(clock.millis, tombstoned.updatedAt)
            assertEquals(capture.createdAt, tombstoned.createdAt)
        }
    }
}
