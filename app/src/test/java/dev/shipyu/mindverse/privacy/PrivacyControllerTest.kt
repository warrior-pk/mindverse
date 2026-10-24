package dev.shipyu.mindverse.privacy

import dev.shipyu.mindverse.core.data.TestDatabaseFactory
import dev.shipyu.mindverse.core.data.db.EgressLogEntity
import dev.shipyu.mindverse.core.data.db.EgressOutcome
import dev.shipyu.mindverse.core.egress.EgressFeature
import dev.shipyu.mindverse.core.egress.EgressToggles
import dev.shipyu.mindverse.core.egress.PayloadClass
import java.time.ZoneId
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Privacy-controller seam (ticket 03 seam 5): day-grouped log state, live updates, and
 * toggle flips — all JVM-testable because the controller is Compose-free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivacyControllerTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var dao: dev.shipyu.mindverse.core.data.db.EgressLogDao
    private lateinit var toggles: EgressToggles
    private lateinit var controller: PrivacyController

    @Before
    fun setUp() {
        val db = TestDatabaseFactory.inMemoryDatabase(context)
        dao = db.egressLogDao()
        toggles = EgressToggles.create(context, settingsFileName = "privacy_toggles_${System.nanoTime()}")
        controller = PrivacyController(toggles, dao, ZoneId.of("UTC"))
    }

    private fun row(id: String, at: Long, feature: EgressFeature = EgressFeature.ASK) = EgressLogEntity(
        id = id,
        requestId = "req-$id",
        captureId = null,
        feature = feature.name,
        payloadClasses = listOf(PayloadClass.PHONE.name),
        provider = "gemini",
        bytesOut = 640,
        at = at,
        outcome = EgressOutcome.SUCCESS,
        error = null,
    )

    @Test
    fun logRendersGroupedByLocalDayNewestFirst() = runTest {
        // Oct 23 late evening + Oct 24 early morning (UTC) → two day groups.
        dao.insert(row("older", at = 1_761_388_800_000L)) // 2025-10-25T12:00Z
        dao.insert(row("newer", at = 1_761_401_000_000L)) // 2025-10-25T15:23:40Z
        dao.insert(row("older-day2", at = 1_761_300_000_000L)) // 2025-10-24T10:40Z

        val state = controller.state.filter { it.days.isNotEmpty() }.first()

        assertEquals(2, state.days.size)
        val today = state.days.first()
        assertEquals(listOf("newer", "older"), today.rows.map { it.id })
        val yesterday = state.days.last()
        assertEquals(listOf("older-day2"), yesterday.rows.map { it.id })
        assertEquals(listOf(PayloadClass.PHONE), today.rows.first().payloadClasses)
        assertEquals("Ask mode", today.rows.first().featureLabel)
    }

    @Test
    fun togglesStartsAllEnabledAndFlipsLive() = runTest {
        val initial = controller.state.filter { it.featureToggles.isNotEmpty() }.first()
        assertEquals(
            EgressFeature.entries.associateWith { true },
            initial.featureToggles,
        )

        controller.setFeatureEnabled(EgressFeature.VOICE_TRANSCRIPTION, enabled = false)

        val after = controller.state.filter {
            it.featureToggles[EgressFeature.VOICE_TRANSCRIPTION] == false
        }.first()
        assertTrue(after.featureToggles.getValue(EgressFeature.ASK))
        assertTrue(after.featureToggles.getValue(EgressFeature.MEMORY_EXTRACTION))
    }
}
