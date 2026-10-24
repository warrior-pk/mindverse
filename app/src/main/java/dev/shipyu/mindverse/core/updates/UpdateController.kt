package dev.shipyu.mindverse.core.updates

import dev.shipyu.mindverse.core.data.settings.AppSettings
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Update-check UI state (ticket 08's About card). */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val outcome: UpdateOutcome.Available) : UpdateUiState
    data class Failed(val reason: String) : UpdateUiState
}

/**
 * Update check driver (ticket 08): manual checks + a cold-start check debounced to one per
 * 24h (spec R6-3 — never a background poller, never WorkManager periodic). Ticket 10
 * piggybacks the Config fetch on this same path.
 */
class UpdateController(
    private val checker: ReleaseUpdateChecker,
    private val appSettings: AppSettings,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** Cold-start entry: checks only when the debounce window has elapsed. */
    fun checkIfNeeded() {
        scope.launch { checkIfDue() }
    }

    /** Manual entry (About card button): always checks. */
    suspend fun checkNow() {
        runCheck()
    }

    /** Suspend seam for tests and callers that own their scope. */
    suspend fun checkIfDue() {
        if (clock.millis() - appSettings.lastUpdateCheck() < CHECK_DEBOUNCE_MS) return
        runCheck()
    }

    private suspend fun runCheck() {
        if (_state.value is UpdateUiState.Checking) return
        _state.value = UpdateUiState.Checking
        val outcome = checker.check()
        appSettings.markUpdateChecked(clock.millis())
        _state.value = when (outcome) {
            is UpdateOutcome.Available -> UpdateUiState.Available(outcome)
            UpdateOutcome.UpToDate -> UpdateUiState.UpToDate
            is UpdateOutcome.Failed -> UpdateUiState.Failed(outcome.reason)
        }
    }

    companion object {
        const val CHECK_DEBOUNCE_MS = 24 * 60 * 60 * 1000L
    }
}
