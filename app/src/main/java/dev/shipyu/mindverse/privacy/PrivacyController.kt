package dev.shipyu.mindverse.privacy

import dev.shipyu.mindverse.core.data.db.EgressLogDao
import dev.shipyu.mindverse.core.egress.EgressFeature
import dev.shipyu.mindverse.core.egress.EgressToggles
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Presentation source for the Privacy screen (ticket 03 seam 5): toggles + egress log
 * joined into one [PrivacyUiState] stream. UI logic stays in the composable; this class
 * is deliberately Compose-free so the grouping/gating state is JVM-testable.
 */
class PrivacyController(
    private val toggles: EgressToggles,
    logDao: EgressLogDao,
    private val zone: ZoneId,
) {
    /** Latest-first log slice; bounded to keep the screen snappy (scroll-back window). */
    private val logFlow = logDao.observeRecent(LOG_SLICE_LIMIT)

    val state: Flow<PrivacyUiState> = combine(toggles.states(), logFlow) { toggleMap, rows ->
        PrivacyUiState(
            featureToggles = toggleMap,
            days = groupByDay(rows, zone) { it.displayLabel },
        )
    }

    suspend fun setFeatureEnabled(feature: EgressFeature, enabled: Boolean) {
        toggles.setEnabled(feature, enabled)
    }

    companion object {
        const val LOG_SLICE_LIMIT = 300
    }
}
