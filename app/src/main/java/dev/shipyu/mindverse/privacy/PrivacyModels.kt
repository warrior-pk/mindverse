package dev.shipyu.mindverse.privacy

import dev.shipyu.mindverse.core.data.db.EgressLogEntity
import dev.shipyu.mindverse.core.data.db.EgressOutcome
import dev.shipyu.mindverse.core.egress.EgressFeature
import dev.shipyu.mindverse.core.egress.PayloadClass
import java.time.Instant
import java.time.ZoneId

/** One rendered egress-log row; timestamps stay raw (UI formats with device locale). */
data class PrivacyLogRow(
    val id: String,
    val featureLabel: String,
    val payloadClasses: List<PayloadClass>,
    val provider: String,
    val bytesOut: Int,
    val atMillis: Long,
    val outcome: EgressOutcome,
    val error: String?,
)

/** All rows of one local day (user's zone), newest-first inside the day. */
data class PrivacyDayGroup(
    /** java.time.LocalDate.toEpochDay() of the day the calls happened, in [zone]. */
    val epochDay: Long,
    val rows: List<PrivacyLogRow>,
)

data class PrivacyUiState(
    /** Per-feature egress toggles (spec R5-1); true = calls allowed. */
    val featureToggles: Map<EgressFeature, Boolean>,
    val days: List<PrivacyDayGroup>,
) {
    companion object {
        /** Neutral initial state while the first flows resolve. */
        fun idle(): PrivacyUiState = PrivacyUiState(
            featureToggles = EgressFeature.entries.associateWith { true },
            days = emptyList(),
        )
    }
}

internal fun EgressLogEntity.toRow(featureLabelOf: (EgressFeature) -> String): PrivacyLogRow = PrivacyLogRow(
    id = id,
    featureLabel = EgressFeature.entries.firstOrNull { it.name == feature }?.let(featureLabelOf) ?: feature,
    payloadClasses = payloadClasses.mapNotNull { name -> PayloadClass.entries.firstOrNull { it.name == name } },
    provider = provider,
    bytesOut = bytesOut,
    atMillis = at,
    outcome = outcome,
    error = error,
)

/**
 * Day grouping (ticket 03 seam 5): group log rows by local calendar day, days sorted
 * newest-first, rows within a day keep their newest-first order. Pure function so the
 * grouping rule is testable without Compose.
 */
internal fun groupByDay(rows: List<EgressLogEntity>, zone: ZoneId, featureLabelOf: (EgressFeature) -> String): List<PrivacyDayGroup> {
    val byDay = rows.groupBy { row -> Instant.ofEpochMilli(row.at).atZone(zone).toLocalDate().toEpochDay() }
    return byDay.keys.sortedDescending().map { day ->
        PrivacyDayGroup(
            epochDay = day,
            rows = byDay.getValue(day).map { it.toRow(featureLabelOf) },
        )
    }
}
