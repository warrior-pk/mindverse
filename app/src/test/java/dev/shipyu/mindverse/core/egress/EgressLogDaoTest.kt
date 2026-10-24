package dev.shipyu.mindverse.core.egress

import dev.shipyu.mindverse.core.data.TestDatabaseFactory
import dev.shipyu.mindverse.core.data.db.EgressLogEntity
import dev.shipyu.mindverse.core.data.db.EgressOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Egress-log storage seam (ticket 03 seam 4): newest-first chronological reads, live
 * observation for the Privacy screen, and the payload-class list round-trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EgressLogDaoTest {
    private lateinit var dao: dev.shipyu.mindverse.core.data.db.EgressLogDao

    @Before
    fun setUp() {
        val db = TestDatabaseFactory.inMemoryDatabase(RuntimeEnvironment.getApplication())
        dao = db.egressLogDao()
    }

    private fun row(id: String, at: Long) = EgressLogEntity(
        id = id,
        requestId = "req-$id",
        captureId = null,
        feature = EgressFeature.ASK.name,
        payloadClasses = listOf(PayloadClass.PHONE.name, PayloadClass.EMAIL.name),
        provider = "gemini",
        bytesOut = 100,
        at = at,
        outcome = EgressOutcome.SUCCESS,
        error = null,
    )

    @Test
    fun recentReturnsNewestFirst() = runTest {
        dao.insert(row("a", at = 100))
        dao.insert(row("b", at = 300))
        dao.insert(row("c", at = 200))

        assertEquals(listOf("b", "c", "a"), dao.recent(10).map { it.id })
    }

    @Test
    fun recentRespectsLimit() = runTest {
        repeat(5) { dao.insert(row("row-$it", at = it.toLong())) }
        assertEquals(3, dao.recent(3).size)
        assertEquals(5, dao.countAll())
    }

    @Test
    fun observeEmitsLiveForPrivacyScreen() = runTest {
        assertEquals(0, dao.observeRecent(100).first().size)
        dao.insert(row("a", at = 1))
        assertEquals(1, dao.observeRecent(100).first().size)
    }

    @Test
    fun payloadClassesRoundTrip() = runTest {
        dao.insert(row("a", at = 1))
        val stored = dao.recent(10).single()
        assertEquals(listOf(PayloadClass.PHONE.name, PayloadClass.EMAIL.name), stored.payloadClasses)
    }
}
