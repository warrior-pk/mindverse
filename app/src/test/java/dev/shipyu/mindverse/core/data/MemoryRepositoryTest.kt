package dev.shipyu.mindverse.core.data

import dev.shipyu.mindverse.core.data.db.MemoryItemKind
import dev.shipyu.mindverse.core.data.db.MindverseDatabaseSchema
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * MemoryRepository seam: tombstones drop rows out of both indexes while the row itself
 * persists (ADR-0003); mutation timestamps land on update/tombstone; typed Entity rows
 * (dates/prices/places/tickets/people) round-trip with the card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryRepositoryTest {
    /** One-hot at [variant]: distinct variants are orthogonal (cosine distance 1.0). */
    private fun embedding(variant: Int): FloatArray =
        FloatArray(MindverseDatabaseSchema.EMBEDDING_DIMENSION) { index -> if (index == variant) 1f else 0f }

    private suspend fun seedMemory(
        repository: MemoryRepository,
        title: String = "Flight to Tokyo",
        tags: List<String> = listOf("flight", "tokyo"),
        embedding: FloatArray? = null,
    ): dev.shipyu.mindverse.core.data.db.MemoryEntity =
        repository.createMemory(
            MemoryDraft(
                sourceCaptureIds = listOf("cap-1"),
                title = title,
                summary = "Boarding pass for tomorrow",
                category = "travel",
                tags = tags,
                extractedText = "Gate 12 Seat 14A",
                embedding = embedding,
                items = listOf(
                    MemoryItemDraft(kind = MemoryItemKind.TICKET, text = "PNR 4XYZ21", ticketCode = "4XYZ21", ticketSeat = "14A", ticketGate = "12"),
                    MemoryItemDraft(kind = MemoryItemKind.DEADLINE, text = "departure", timestampStartMs = 1_800_000_000_000),
                    MemoryItemDraft(kind = MemoryItemKind.PLACE, locationName = "Narita", latitude = 35.772, longitude = 140.393),
                ),
            ),
        )

    @Test
    fun typedEntityRowsRoundTrip() {
        runBlocking {
            val db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
            val clock = FixedClock()
            val repository = MemoryRepository(db, clock)

            val memory = seedMemory(repository, embedding = embedding(1))
            val items = repository.itemsFor(memory.id)

            assertEquals(3, items.size)
            assertEquals(listOf(MemoryItemKind.TICKET, MemoryItemKind.DEADLINE, MemoryItemKind.PLACE), items.map { it.kind })
            assertEquals("4XYZ21", items[0].ticketCode)
            assertEquals("14A", items[0].ticketSeat)
            assertEquals("12", items[0].ticketGate)
            assertEquals(1_800_000_000_000L, items[1].timestampStartMs)
            assertEquals("Narita", items[2].locationName)
            assertEquals(35.772, items[2].latitude!!, 1e-9)
            assertEquals(140.393, items[2].longitude!!, 1e-9)
            // Sync-ready stamps on child rows.
            assertEquals(clock.millis, items[0].createdAt)
        }
    }

    @Test
    fun updateStampsUpdatedAtWithoutTouchingCreatedAt() {
        runBlocking {
            val db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
            val clock = FixedClock()
            val repository = MemoryRepository(db, clock)

            val memory = seedMemory(repository, embedding = embedding(1))
            clock.advanceBy(10_000)

            val updated = memory.copy(title = "Flight to Tokyo (delayed)")
            repository.updateMemory(updated)
            val reloaded = repository.getMemory(memory.id)

            assertNotNull(reloaded)
            assertEquals("Flight to Tokyo (delayed)", reloaded!!.title)
            assertEquals(memory.createdAt, reloaded.createdAt)
            assertEquals(clock.millis, reloaded.updatedAt)
            assertTrue(reloaded.updatedAt > reloaded.createdAt)
        }
    }

    @Test
    fun reEmbeddingReplacesVectorRow() {
        runBlocking {
            val db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
            val clock = FixedClock()
            val repository = MemoryRepository(db, clock)

            val memory = seedMemory(repository, embedding = embedding(1))
            assertEquals(1, db.vectorCount())

            repository.updateMemory(memory, newEmbedding = embedding(2))
            assertEquals(1, db.vectorCount())

            // New vector finds the card as its own nearest neighbor (distance ~0).
            val hits = repository.searchByVector(embedding(2), k = 3)
            assertEquals(memory.id, hits.first().memoryId)
            // Old vector no longer matches exactly.
            val oldHits = repository.searchByVector(embedding(1), k = 3)
            assertTrue(oldHits.first().distance > 0.01)
        }
    }

    @Test
    fun tombstonedMemoryDropsFromBothIndexesButRowPersists() {
        runBlocking {
            val db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
            val clock = FixedClock()
            val repository = MemoryRepository(db, clock)

            val memory = seedMemory(repository, embedding = embedding(1))
            assertEquals(1, db.vectorCount())
            clock.advanceBy(1_000)
            repository.tombstoneMemory(memory.id)

            // Row persists as a tombstone (ADR-0003), stamped.
            val tombstoned = repository.getMemory(memory.id)
            assertNotNull(tombstoned)
            assertTrue(tombstoned!!.deleted)
            assertEquals(clock.millis, tombstoned.updatedAt)
            assertEquals(1, db.memoryDao().countAll())
            assertEquals(1, db.memoryDao().countTombstoned())

            // Vector row removed; nothing left to load.
            assertEquals(0, db.vectorCount())
            assertTrue(repository.searchByVector(embedding(1), k = 5).isEmpty())

            // FTS no longer surfaces it.
            assertTrue(repository.searchByFullText("tokyo").isEmpty())
            assertTrue(repository.searchByFullText("gate 12").isEmpty())
        }
    }

    @Test
    fun listActiveExcludesTombstones() {
        runBlocking {
            val db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
            val clock = FixedClock()
            val repository = MemoryRepository(db, clock)

            val keep = seedMemory(repository, title = "Keep me", embedding = embedding(1))
            val drop = seedMemory(repository, title = "Drop me", embedding = embedding(2))
            repository.tombstoneMemory(drop.id)

            val active = repository.listActive()
            assertEquals(listOf(keep.id), active.map { it.id })
            assertFalse(active.any { it.deleted })
        }
    }

    @Test
    fun ftsSearchNeverSurfacesDeletedEvenAfterUpdate() {
        runBlocking {
            val db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
            val clock = FixedClock()
            val repository = MemoryRepository(db, clock)

            val memory = seedMemory(repository, title = "Gym membership card", tags = listOf("receipt"))
            // Update re-syncs the FTS row via trigger (new title becomes searchable).
            clock.advanceBy(1_000)
            repository.updateMemory(memory.copy(summary = "renewed at Fred Meyer"))
            assertEquals(listOf(memory.id), repository.searchByFullText("fred meyer").map { it.id })

            repository.tombstoneMemory(memory.id)
            assertTrue(repository.searchByFullText("fred meyer").isEmpty())
        }
    }

    @Test
    fun ftsPhraseIsQuotedSoUserTextCannotInjectQuerySyntax() {
        runBlocking {
            val db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
            val clock = FixedClock()
            val repository = MemoryRepository(db, clock)

            seedMemory(repository, title = "Flight to Tokyo", embedding = embedding(1))
            // FTS5 query syntax in user input is inert inside the quoted phrase.
            assertEquals(
                emptyList<String>(),
                repository.searchByFullText("tokyo\" OR \"x").map { it.id },
            )
            // Exact phrase requires full token sequence.
            assertEquals(emptyList<String>(), repository.searchByFullText("tokyo flight").map { it.id })
        }
    }

    @Test
    fun getMemoryReturnsNullForUnknownId() {
        runBlocking {
            val db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
            val repository = MemoryRepository(db, FixedClock())
            assertNull(repository.getMemory("nope"))
        }
    }
}
